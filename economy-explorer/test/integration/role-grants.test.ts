import { beforeAll, afterAll, describe, it, expect } from 'vitest';
import { sql } from 'kysely';
import { db, uuidToBin } from '@/lib/db';
import { listRoleGrants } from '@/lib/sql/role-grants';
import { resolveAuthorization } from '@/lib/sql/role';
import { HAS_DB, resetDb, ALICE } from './db';
const suite = HAS_DB ? describe : describe.skip;
beforeAll(async () => { if (HAS_DB) await resetDb(); }, 60000);
afterAll(async () => { if (HAS_DB) await db.destroy(); });
suite('role provenance', () => {
  it('retains multiple sources and mirrors lease/source validity without inventing an inheritance path', async () => {
    await sql`INSERT INTO explorer_role (player_uuid_bin, role) VALUES (${uuidToBin(ALICE)}, 'moderator')`.execute(db);
    const ids: number[] = [];
    for (const [name, node, age] of [['Inherited parent', 'parent', 0], ['Second group', 'group.second', 0], ['Expired group', 'old', 2], ['Disabled group', null, 0]] as const) {
      const g = await sql`INSERT INTO explorer_group (name, luckperms_node) VALUES (${name}, ${node})`.execute(db);
      const id = Number(g.insertId); ids.push(id);
      await sql`INSERT INTO explorer_group_capability (group_id, capability) VALUES (${id}, 'moderator')`.execute(db);
      await sql`INSERT INTO explorer_group_member (group_id, player_uuid_bin, source, synced_at)
        VALUES (${id}, ${uuidToBin(ALICE)}, 'luckperms', CURRENT_TIMESTAMP - INTERVAL ${age} HOUR)`.execute(db);
    }
    const result = await listRoleGrants({ q: ALICE, role: 'moderator' });
    expect(result.rows).toHaveLength(5);
    expect(result.rows.filter(r => r.active).map(r => r.source).sort()).toEqual(['direct', 'luckperms', 'luckperms']);
    expect(result.rows.find(r => r.group === 'Inherited parent')).toMatchObject({ selector: 'parent', active: true });
    expect((await resolveAuthorization(ALICE)).roles).toContain('moderator');
    await sql`UPDATE explorer_group_member SET synced_at = NULL WHERE group_id = ${ids[3]}`.execute(db);
    expect((await listRoleGrants({ q: 'Disabled group', status: 'inactive' })).rows).toHaveLength(1);
    expect((await listRoleGrants({ q: 'Expired group', status: 'active' })).rows).toEqual([]);
    expect((await listRoleGrants({ q: 'Expired group', status: 'inactive' })).rows).toHaveLength(1);
    expect((await listRoleGrants({ q: 'Second', source: 'luckperms', role: 'moderator' })).rows).toHaveLength(1);
    expect((await listRoleGrants({ q: "' OR 1=1 --" })).rows).toEqual([]);
    expect((await listRoleGrants({ q: ALICE, source: 'baseline' })).rows).toHaveLength(1);
    for (const id of ids) await sql`DELETE FROM explorer_group WHERE group_id = ${id}`.execute(db);
    await sql`DELETE FROM explorer_role WHERE player_uuid_bin = ${uuidToBin(ALICE)} AND role = 'moderator'`.execute(db);
  });
  it('pages grant sources without losing rows and fences changed derived selectors', async () => {
    const g = await sql`INSERT INTO explorer_group (name) VALUES ('Provenance pagination')`.execute(db);
    const id = Number(g.insertId);
    await sql`INSERT INTO explorer_group_capability VALUES (${id}, 'moderator')`.execute(db);
    await sql`INSERT INTO explorer_group_sync VALUES (${id}, 'business', '7')`.execute(db);
    for (let n = 1; n <= 51; n++) {
      const uuid = `a1930000-0000-0000-0000-${String(n).padStart(12, '0')}`;
      await sql`INSERT INTO explorer_group_member (group_id, player_uuid_bin, source, synced_at, sync_selector)
        VALUES (${id}, ${uuidToBin(uuid)}, 'business', CURRENT_TIMESTAMP, '7')`.execute(db);
    }
    const first = await listRoleGrants({ q: 'Provenance pagination', source: 'business', status: 'active' });
    const second = await listRoleGrants({ q: 'Provenance pagination', source: 'business', status: 'active', page: 2 });
    expect(first.rows).toHaveLength(50); expect(first.hasMore).toBe(true);
    expect(second.rows).toHaveLength(1); expect(second.hasMore).toBe(false);
    expect(new Set([...first.rows, ...second.rows].map(r => r.uuid)).size).toBe(51);
    await sql`UPDATE explorer_group_sync SET selector = '8' WHERE group_id = ${id}`.execute(db);
    expect((await listRoleGrants({ q: 'Provenance pagination', status: 'active' })).rows).toEqual([]);
    expect((await listRoleGrants({ q: 'Provenance pagination', status: 'inactive' })).hasMore).toBe(true);
    await sql`DELETE FROM explorer_group WHERE group_id = ${id}`.execute(db);
  });

});
