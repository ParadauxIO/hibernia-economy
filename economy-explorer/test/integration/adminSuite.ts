// Audit log and admin table queries (PAR-542) against the seeded MariaDB.
// Registered from sql.test.ts so it shares that file's single resetDb() and runs
// serially with it.
import { sql } from 'kysely';
import { afterAll, beforeAll, expect, it } from 'vitest';
import { db, uuidToBin } from '@/lib/db';
import type { Viewer } from '@/lib/auth/viewer';
import { audit, auditMutation, auditRelationshipRead, setRelationshipReadThrottleForTests } from '@/lib/audit';
import { memoryThrottleStore } from '@/lib/auditThrottle';
import { parseAuditValue } from '@/lib/auditDiff';
import { countAuditByAction, findAuditEntry, listAuditPage } from '@/lib/sql/audit';
import {
  countAdminApiKeys,
  countAdminGroups,
  countAdminRoles,
  countAdminPlayers,
  countAdminWebhooks,
  findPlayersByPrefix,
  listAdminApiKeyPage,
  listAdminGroups,
  listAdminPlayers,
  listAdminRoles,
  listAdminWebhooks,
} from '@/lib/sql/admin';
import { listGroupMembers, countGroupMembers, isManualGroupMember, clearSourceMembers, countGroupMembersBySource, selectGroupById, upsertGroupSync, deleteGroupSync } from '@/lib/sql/group';
import { countDirectRoleMembers, listDirectRoleMemberPage } from '@/lib/sql/role';
import { countRoleGrants, listRoleGrants } from '@/lib/sql/role-grants';
import { auditTable } from '@/lib/tables/audit';
import { adminGroupMembersTable, adminRoleMembersTable, adminApiKeysTable, adminGroupsTable, adminPlayersTable, adminRolesTable, adminWebhooksTable } from '@/lib/tables/admin';
import { parseTableState, stateCursor, withCursor } from '@/lib/tables/core';
import { permissionsFor } from '../role-fixtures';
import { ALICE, BOB, CAROL } from './db';

type SignedIn = Extract<Viewer, { anon: false }>;
const viewer = (over: Partial<SignedIn> = {}): SignedIn => ({
  anon: false, keycloakSub: 'audit-suite-admin', minecraftUuid: ALICE, minecraftName: 'Alice', linked: true,
  role: 'admin', capabilities: ['player', 'admin'], permissions: permissionsFor('player', 'admin'), ...over,
});
const player = (sub: string, uuid = BOB): SignedIn => ({
  anon: false, keycloakSub: sub, minecraftUuid: uuid, minecraftName: 'Bob', linked: true,
  role: 'player', capabilities: ['player'], permissions: permissionsFor('player'),
});
const auditState = (qs: string) => parseTableState(new URLSearchParams(qs), auditTable);

async function rowsFor(sub: string) {
  const r = await sql<{ action: string; target_type: string; target_id: string; before_json: string | null; after_json: string | null }>`
    SELECT action, target_type, target_id, before_json, after_json FROM explorer_audit WHERE actor_sub = ${sub} ORDER BY audit_id
  `.execute(db);
  return r.rows;
}

export function adminSuite(d: (name: string, fn: () => void) => void): void {
  d('audit log (PAR-542)', () => {
    beforeAll(async () => {
      await sql`DELETE FROM explorer_audit WHERE actor_sub LIKE 'audit-suite-%'`.execute(db);
    });
    afterAll(() => setRelationshipReadThrottleForTests(null));

    it('records a mutation with its before and after values as JSON', async () => {
      await auditMutation(viewer(), {
        action: 'account.rename', path: '/admin/accounts/rename', targetType: 'account', targetId: 1,
        before: { displayName: 'Old', when: new Date('2026-01-02T03:04:05Z') }, after: { displayName: 'New', cents: 10n },
      });
      const [row] = await rowsFor('audit-suite-admin');
      expect(row).toMatchObject({ action: 'account.rename', target_type: 'account', target_id: '1' });
      expect(parseAuditValue(row!.before_json)).toEqual({ displayName: 'Old', when: '2026-01-02T03:04:05.000Z' });
      expect(parseAuditValue(row!.after_json)).toEqual({ displayName: 'New', cents: '10' });
    });

    it('keeps views free of before/after values and defaults their action to view', async () => {
      await audit({ viewer: viewer(), method: 'GET', path: '/admin/players', targetType: 'global', targetId: null, outcome: 200, sourceIp: null });
      const rows = await rowsFor('audit-suite-admin');
      expect(rows.at(-1)).toMatchObject({ action: 'view', before_json: null, after_json: null });
    });

    it('is fail-open: an insert error never throws', async () => {
      await expect(auditMutation(viewer({ keycloakSub: 'x'.repeat(200) }), {
        action: 'account.rename', path: '/p', targetType: 'account', targetId: 1, before: null, after: null,
      })).resolves.toBeUndefined();
    });

    it('filters the feed by actor, action, target and time, newest first', async () => {
      await auditMutation(viewer(), { action: 'group.create', path: '/admin/groups/create', targetType: 'group', targetId: 77, before: null, after: { name: 'G' } });
      await auditMutation(viewer({ keycloakSub: 'audit-suite-bob', minecraftUuid: BOB, minecraftName: 'Bob' }), {
        action: 'firm.rename', path: '/admin/firms/rename', targetType: 'firm', targetId: 1, before: { displayName: 'A' }, after: { displayName: 'B' },
      });
      const mine = (await listAuditPage(auditState(`f=actor:in:${ALICE}`), null)).filter((e) => e.actor_sub.startsWith('audit-suite'));
      expect(mine.map((e) => e.action)).toEqual(['group.create', 'view', 'account.rename']);
      expect((await listAuditPage(auditState('f=actor:in:Bob'), null)).map((e) => e.action)).toContain('firm.rename');
      const byAction = await listAuditPage(auditState('f=action:in:firm-rename'), null);
      expect(byAction.every((e) => e.action === 'firm.rename')).toBe(true);
      expect(byAction.length).toBeGreaterThan(0);
      const byTarget = await listAuditPage(auditState('f=target:in:group&f=targetId:eq:77'), null);
      expect(byTarget.map((e) => e.action)).toEqual(['group.create']);
      expect(byTarget[0]?.has_detail).toBe(true);
      expect(await listAuditPage(auditState('f=at:lt:2001'), null)).toEqual([]);
      expect((await listAuditPage(auditState('q=Bo'), null)).some((e) => e.actor_name === 'Bob')).toBe(true);
    });

    it('pages with a keyset cursor without repeating or skipping rows', async () => {
      const all = (await listAuditPage(auditState('limit=200'), null)).map((e) => e.audit_id);
      let state = auditState('limit=2');
      const seen: number[] = [];
      for (let i = 0; i < 50; i++) {
        const page = await listAuditPage(state, stateCursor(state));
        seen.push(...page.slice(0, 2).map((e) => e.audit_id));
        if (page.length <= 2) break;
        const last = page[1]!;
        state = withCursor(state, { at: last.at, id: last.audit_id });
      }
      expect(seen).toEqual(all);
    });

    it('reads one entry with its values and counts rows per action', async () => {
      const [first] = await listAuditPage(auditState('f=action:in:account-rename&f=actor:in:' + ALICE), null);
      const entry = await findAuditEntry(first!.audit_id);
      expect(parseAuditValue(entry!.after_json)).toMatchObject({ displayName: 'New' });
      expect((await countAuditByAction())['account-rename']).toBeGreaterThanOrEqual(1);
      expect(await findAuditEntry(2_000_000_000)).toBeNull();
    });

    it('records relationship reads of government and business accounts once per viewer, account and hour', async () => {
      setRelationshipReadThrottleForTests(memoryThrottleStore());
      const bob = player('audit-suite-rel');
      await auditRelationshipRead(bob, [
        { accountId: 5, accountType: 'GOVERNMENT' },
        { accountId: 3, accountType: 'BUSINESS' },
        { accountId: 1, accountType: 'PERSONAL' },
        { accountId: 3, accountType: 'BUSINESS' },
      ], '/gov/accounts');
      await auditRelationshipRead(bob, { accountId: 5, accountType: 'GOVERNMENT' }, '/gov/accounts');
      const rows = await rowsFor('audit-suite-rel');
      expect(rows.map((r) => [r.action, r.target_type, r.target_id])).toEqual([
        ['relationship.read', 'account', '5'],
        ['relationship.read', 'account', '3'],
      ]);
      // A different viewer is throttled separately.
      await auditRelationshipRead(player('audit-suite-rel2', CAROL), { accountId: 5, accountType: 'GOVERNMENT' });
      expect(await rowsFor('audit-suite-rel2')).toHaveLength(1);
    });

    it('skips reads a global permission already covers, and anonymous viewers', async () => {
      setRelationshipReadThrottleForTests(memoryThrottleStore());
      const all = { ...player('audit-suite-global'), permissions: permissionsFor('player', 'moderator') };
      await auditRelationshipRead(all, [{ accountId: 5, accountType: 'GOVERNMENT' }, { accountId: 3, accountType: 'BUSINESS' }]);
      expect(await rowsFor('audit-suite-global')).toEqual([]);
      const govOnly = { ...player('audit-suite-govall'), permissions: [...permissionsFor('player'), 'government.read.all' as const] };
      await auditRelationshipRead(govOnly, [{ accountId: 5, accountType: 'GOVERNMENT' }, { accountId: 3, accountType: 'BUSINESS' }]);
      expect((await rowsFor('audit-suite-govall')).map((r) => r.target_id)).toEqual(['3']);
      await auditRelationshipRead({ anon: true, role: null, capabilities: [], permissions: [], keycloakSub: null, minecraftUuid: null, minecraftName: null, linked: false },
        { accountId: 5, accountType: 'GOVERNMENT' });
    });
  });

  d('admin tables (PAR-542)', () => {
    it('lists players by name prefix or exact UUID, with dates as filters', async () => {
      const page = (qs: string) => listAdminPlayers(parseTableState(new URLSearchParams(qs), adminPlayersTable));
      expect((await page('q=al')).map((p) => p.name)).toEqual(['Alice']);
      expect((await page(`q=${BOB}`)).map((p) => p.name)).toEqual(['Bob']);
      expect((await page('')).length).toBeGreaterThanOrEqual(5);
      expect(await page('f=lastSeen:lt:2001')).toEqual([]);
      expect(await countAdminPlayers(parseTableState(new URLSearchParams('q=%25'), adminPlayersTable))).toBe(0);
      expect((await findPlayersByPrefix('ca')).map((p) => p.label)).toEqual(['Carol']);
    });

    it('filters webhooks and API keys on the declared chips only', async () => {
      await sql`INSERT INTO api_keys (key_id, key_type, owner_uuid_bin, account_id, jwt_id, revoked, issued_at, expires_at)
        VALUES (9901, 'PERSONAL', ${uuidToBin(ALICE)}, 1, 'audit-suite-jti-1', 0, NOW(), NOW() + INTERVAL 30 DAY),
               (9902, 'SERVICE', ${uuidToBin(BOB)}, NULL, 'audit-suite-jti-2', 1, NOW(), NOW() + INTERVAL 30 DAY)`.execute(db);
      await sql`INSERT INTO webhook_subscription (subscription_id, owner_uuid_bin, key_type, account_id, target_url, secret, active, consecutive_failures, disabled_at)
        VALUES (9901, ${uuidToBin(ALICE)}, 'PERSONAL', 1, 'https://example.com/a', 's', 1, 0, NULL),
               (9902, ${uuidToBin(BOB)}, 'PERSONAL', 2, 'https://example.com/b', 's', 0, 7, NOW())`.execute(db);
      try {
        const keys = (qs: string) => listAdminApiKeyPage(parseTableState(new URLSearchParams(qs), adminApiKeysTable));
        expect((await keys('f=type:in:service')).map((k) => k.key_id)).toEqual([9902]);
        expect((await keys('f=status:in:active')).map((k) => k.key_id)).toContain(9901);
        expect((await keys('f=status:in:active')).map((k) => k.key_id)).not.toContain(9902);
        expect((await keys(`f=owner:in:${ALICE}`)).map((k) => k.key_id)).toEqual([9901]);
        expect((await keys('f=owner:in:Bob')).map((k) => k.key_id)).toEqual([9902]);
        expect((await keys('q=%239902')).map((k) => k.key_id)).toEqual([9902]);
        expect(await countAdminApiKeys(parseTableState(new URLSearchParams('f=status:in:revoked'), adminApiKeysTable))).toMatchObject({ total: 1, active: 0 });

        const hooks = (qs: string) => listAdminWebhooks(parseTableState(new URLSearchParams(qs), adminWebhooksTable));
        expect((await hooks('f=status:in:disabled')).map((h) => h.id)).toEqual([9902]);
        expect((await hooks('f=failures:gt:0')).map((h) => h.id)).toEqual([9902]);
        expect((await hooks('q=example.com/a')).map((h) => h.id)).toEqual([9901]);
        expect((await hooks('status=active')).map((h) => h.id)).toEqual([9901]); // legacy ?status= link
        expect(await countAdminWebhooks(parseTableState(new URLSearchParams('f=source:in:apikey'), adminWebhooksTable))).toBe(0);
      } finally {
        await sql`DELETE FROM webhook_subscription WHERE subscription_id IN (9901, 9902)`.execute(db);
        await sql`DELETE FROM api_keys WHERE key_id IN (9901, 9902)`.execute(db);
      }
    });

    it('lists groups and roles with live counts, sources and role filters', async () => {
      const groups = (qs: string) => listAdminGroups(parseTableState(new URLSearchParams(qs), adminGroupsTable));
      expect((await groups('f=source:in:luckperms')).map((g) => g.name)).toContain('Players');
      expect(await groups('f=source:in:business')).toEqual([]);
      expect((await groups('f=role:eq:player')).map((g) => g.name)).toEqual(['Players']);
      expect(await groups('f=role:eq:nope')).toEqual([]);
      const roles = (qs: string) => listAdminRoles(parseTableState(new URLSearchParams(qs), adminRolesTable));
      expect((await roles('f=permission:eq:audit.read')).map((r) => r.key)).toEqual(['admin']);
      expect((await roles('q=moder')).map((r) => r.key)).toEqual(['moderator']);
      expect((await roles('sort=-permissions'))[0]?.key).toBe('admin');
    });

    it('pages group members with tied names and multiple sources, without dropping unknown players (PAR-545)', async () => {
      const groupId = 99545;
      const unknown = '00000000-0000-0000-0000-000000054545';
      await sql`INSERT INTO explorer_group (group_id, name, luckperms_node) VALUES (${groupId}, 'Pagination fixture', 'default')`.execute(db);
      try {
        await sql`INSERT INTO explorer_group_member (group_id, player_uuid_bin, source, synced_at) VALUES
          (${groupId}, ${uuidToBin(ALICE)}, 'manual', NULL),
          (${groupId}, ${uuidToBin(ALICE)}, 'luckperms', NOW()),
          (${groupId}, ${uuidToBin(BOB)}, 'manual', NULL),
          (${groupId}, ${uuidToBin(unknown)}, 'luckperms', NOW() - INTERVAL 2 HOUR)`.execute(db);
        const state = (qs: string) => parseTableState(new URLSearchParams(qs), adminGroupMembersTable);
        expect(await countGroupMembers(groupId, state(''))).toBe(4);
        const pages = await Promise.all([1, 2, 3, 4].map(page => listGroupMembers(groupId, state(`limit=1&page=${page}`))));
        expect(pages.map(rows => rows.length)).toEqual([1, 1, 1, 1]);
        expect(pages.flat().map(m => `${m.uuid}:${m.source}`)).toEqual([
          `${unknown}:luckperms`, `${ALICE}:manual`, `${ALICE}:luckperms`, `${BOB}:manual`,
        ]);
        expect(pages[0]![0]).toMatchObject({ name: null, syncActive: false });
        expect(await listGroupMembers(groupId, state('limit=1&page=5'))).toEqual([]);
        expect(await countGroupMembers(groupId, state('q=Alice&f=source:in:manual'))).toBe(1);
        expect((await listGroupMembers(groupId, state('q=Alice&f=source:in:manual'))).map(m => m.source)).toEqual(['manual']);
        expect((await listGroupMembers(groupId, state(`q=${unknown}`))).map(m => m.uuid)).toEqual([unknown]);
        expect(await countGroupMembers(groupId, state('q=%25'))).toBe(0);
        expect(await isManualGroupMember(groupId, BOB)).toBe(true);
        expect(await isManualGroupMember(groupId, unknown)).toBe(false);
        expect(await isManualGroupMember(groupId + 1, BOB)).toBe(false);
      } finally { await sql`DELETE FROM explorer_group WHERE group_id = ${groupId}`.execute(db); }
    });

    it('bounds group and role directories while counting all filtered results (PAR-545)', async () => {
      await sql`INSERT INTO explorer_group (group_id, name) VALUES (99546, 'Paging A'), (99547, 'Paging B')`.execute(db);
      try {
        const groups = parseTableState(new URLSearchParams('limit=1&q=Paging'), adminGroupsTable);
        const groupTotal = await countAdminGroups(groups);
        expect(groupTotal).toBeGreaterThan(1);
        const first = await listAdminGroups(groups);
        const second = await listAdminGroups({ ...groups, page: 2 });
        expect(first).toHaveLength(1); expect(second).toHaveLength(1);
        expect(first[0]?.groupId).not.toBe(second[0]?.groupId);
        const roles = parseTableState(new URLSearchParams('limit=1'), adminRolesTable);
        expect(await countAdminRoles(roles)).toBeGreaterThan(1);
        const r1 = await listAdminRoles(roles), r2 = await listAdminRoles({ ...roles, page: 2 });
        expect(r1).toHaveLength(1); expect(r2).toHaveLength(1);
        expect(r1[0]?.key).not.toBe(r2[0]?.key);
        const filtered = parseTableState(new URLSearchParams('f=permission:eq:audit.read'), adminRolesTable);
        expect(await countAdminRoles(filtered)).toBe(1);
      } finally { await sql`DELETE FROM explorer_group WHERE group_id IN (99546, 99547)`.execute(db); }
    });

    it('pages direct role assignments and searches beyond the first page (PAR-545)', async () => {
      await sql`INSERT INTO explorer_role_definition (role_key, name) VALUES ('pagination_test', 'Pagination test')`.execute(db);
      try {
        await sql`INSERT INTO explorer_role (player_uuid_bin, role) VALUES (${uuidToBin(ALICE)}, 'pagination_test'), (${uuidToBin(BOB)}, 'pagination_test')`.execute(db);
        const state = parseTableState(new URLSearchParams('limit=1'), adminRoleMembersTable);
        expect(await countDirectRoleMembers('pagination_test', state)).toBe(2);
        expect(await listDirectRoleMemberPage('pagination_test', state)).toEqual([{ uuid: ALICE, name: 'Alice' }]);
        expect(await listDirectRoleMemberPage('pagination_test', { ...state, page: 2 })).toEqual([{ uuid: BOB, name: 'Bob' }]);
        expect(await listDirectRoleMemberPage('pagination_test', { ...state, q: BOB })).toEqual([{ uuid: BOB, name: 'Bob' }]);
        expect(await countDirectRoleMembers('pagination_test', { ...state, q: 'Nobody' })).toBe(0);
      } finally { await sql`DELETE FROM explorer_role_definition WHERE role_key = 'pagination_test'`.execute(db); }
    });

    it('sets, changes and clears a firm source, dropping that source’s synced members only', async () => {
      await upsertGroupSync(1, 'business', '1');
      await sql`INSERT INTO explorer_group_member (group_id, player_uuid_bin, source, synced_at, sync_selector)
        VALUES (1, ${uuidToBin(ALICE)}, 'business', NOW(), '1')`.execute(db);
      try {
        expect((await selectGroupById(1))?.syncSources).toEqual([{ source: 'business', selector: '1' }]);
        const counts = await countGroupMembersBySource(1);
        expect(counts.find((c) => c.source === 'business')).toEqual({ source: 'business', total: 1, active: 1 });
        expect((await groupsLive())).toBe(3);
        await upsertGroupSync(1, 'business', '2');
        // The old selector's members stop granting as soon as the source changes.
        expect((await countGroupMembersBySource(1)).find((c) => c.source === 'business')?.active).toBe(0);
        await clearSourceMembers(1, 'business');
        expect((await countGroupMembersBySource(1)).find((c) => c.source === 'business')).toBeUndefined();
        expect((await countGroupMembersBySource(1)).find((c) => c.source === 'manual')?.total).toBe(1);
      } finally {
        await deleteGroupSync(1, 'business');
        await clearSourceMembers(1, 'business');
      }
      expect((await selectGroupById(1))?.syncSources).toEqual([]);
    });

    it('counts and sorts role grants with several sources at once', async () => {
      const filters = { sources: ['manual', 'luckperms'], role: 'player' };
      const page = await listRoleGrants(filters);
      expect(await countRoleGrants(filters)).toBe(page.rows.length);
      expect(new Set(page.rows.map((r) => r.source))).toEqual(new Set(['manual', 'luckperms']));
      const bySource = await listRoleGrants({ ...filters, sort: 'source', dir: 'desc' });
      expect(bySource.rows[0]?.source).toBe('manual');
    });
  });
}

/** Live members of the seeded Players group (manual Bob + synced Carol + the test's Alice). */
async function groupsLive(): Promise<number> {
  return (await selectGroupById(1))?.activeMemberCount ?? -1;
}
