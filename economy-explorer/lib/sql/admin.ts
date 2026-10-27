import 'server-only';
import { sql, type RawBuilder, type SqlBool } from 'kysely';
import { db, binToUuid, uuidToBin } from '@/lib/db';
import { compileTableState, defineColumnMap, likeContains } from '@/lib/sql/tableState';
import type { EntityOption } from '@/lib/tables/core';
import { ADMIN_JOINS, toAdminRow, type AdminRawRow, type AdminWebhookRow } from '@/lib/sql/webhook';
import { countGroupsWhere, listGroupsWhere, type GroupSummary } from '@/lib/sql/group';
import {
  adminApiKeysTable,
  adminGroupsTable,
  adminPlayersTable,
  adminRolesTable,
  adminWebhooksTable,
  type AdminApiKeysState,
  type AdminGroupsState,
  type AdminPlayersState,
  type AdminRolesState,
  type AdminWebhooksState,
} from '@/lib/tables/admin';

// Admin list queries behind the chip tables (spec §5.5, §12). Pure Kysely: the
// table state is compiled by lib/sql/tableState and every value is bound.

const UUID_RE = /^[0-9a-f]{8}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{12}$/i;
const ID_RE = /^#?\d{1,10}$/;

function likePrefix(text: string): string {
  return `${text.replace(/[\\%_]/g, (c) => `\\${c}`)}%`;
}

/** Player chip values are UUIDs or names; names resolve through the unique name index. */
function playerCondition(column: RawBuilder<unknown>, values: readonly string[]): RawBuilder<SqlBool> | null {
  const uuids = values.filter((v) => UUID_RE.test(v));
  const names = values.filter((v) => !UUID_RE.test(v)).map((v) => v.toLowerCase());
  const parts: RawBuilder<SqlBool>[] = [];
  if (uuids.length) parts.push(sql<SqlBool>`${column} IN (${sql.join(uuids.map(uuidToBin))})`);
  if (names.length) parts.push(sql<SqlBool>`${column} IN (SELECT np.player_uuid_bin FROM economy_players np WHERE np.name_lower IN (${sql.join(names)}))`);
  if (!parts.length) return null;
  return parts.length === 1 ? (parts[0] as RawBuilder<SqlBool>) : sql<SqlBool>`(${sql.join(parts, sql` OR `)})`;
}

// ── Players ─────────────────────────────────────────────────────────────────

export interface AdminPlayerRow {
  uuid: string;
  name: string;
  firstSeen: Date;
  lastSeen: Date;
}

export const adminPlayersColumnMap = defineColumnMap({
  table: adminPlayersTable,
  filters: {
    firstSeen: { column: sql`p.first_seen` },
    lastSeen: { column: sql`p.last_seen` },
  },
  sorts: { name: sql`p.name_lower`, lastSeen: sql`p.last_seen`, firstSeen: sql`p.first_seen` },
  tiebreak: { column: sql`p.player_uuid_bin`, dir: 'asc' },
  // A UUID is an exact key lookup; anything else is a name prefix on the unique name index.
  search: (q) => {
    const term = q.trim();
    if (UUID_RE.test(term)) return sql<SqlBool>`p.player_uuid_bin = ${uuidToBin(term.toLowerCase())}`;
    if (!term) return null;
    return sql<SqlBool>`p.name_lower LIKE ${likePrefix(term.toLowerCase().slice(0, 32))}`;
  },
});

export async function listAdminPlayers(state: AdminPlayersState): Promise<AdminPlayerRow[]> {
  const c = compileTableState(state, adminPlayersColumnMap);
  const r = await sql<{ player_uuid_bin: Buffer; current_name: string; first_seen: Date; last_seen: Date }>`
    SELECT p.player_uuid_bin, p.current_name, p.first_seen, p.last_seen
    FROM economy_players p
    ${c.where}
    ${c.orderBy}
    LIMIT ${state.limit} OFFSET ${(state.page - 1) * state.limit}
  `.execute(db);
  return r.rows.map((row) => ({ uuid: binToUuid(row.player_uuid_bin), name: row.current_name, firstSeen: row.first_seen, lastSeen: row.last_seen }));
}

export async function countAdminPlayers(state: AdminPlayersState): Promise<number> {
  const c = compileTableState(state, adminPlayersColumnMap);
  const r = await sql<{ c: string | number }>`SELECT COUNT(*) AS c FROM economy_players p ${c.where}`.execute(db);
  return Number(r.rows[0]?.c ?? 0);
}

// ── Webhooks ────────────────────────────────────────────────────────────────

export const adminWebhooksColumnMap = defineColumnMap({
  table: adminWebhooksTable,
  filters: {
    scope: {
      compile: (f) => {
        const parts = f.values.map((v) => (v === 'firm' ? sql<SqlBool>`s.firm_id IS NOT NULL` : sql<SqlBool>`s.firm_id IS NULL`));
        return parts.length ? sql<SqlBool>`(${sql.join(parts, sql` OR `)})` : null;
      },
    },
    status: {
      compile: (f) => {
        const parts = f.values.map((v) => (v === 'active' ? sql<SqlBool>`s.active = 1`
          : v === 'paused' ? sql<SqlBool>`(s.active = 0 AND s.disabled_at IS NULL)`
          : sql<SqlBool>`s.disabled_at IS NOT NULL`));
        return parts.length ? sql<SqlBool>`(${sql.join(parts, sql` OR `)})` : null;
      },
    },
    source: {
      compile: (f) => {
        const parts = f.values.map((v) => (v === 'apikey' ? sql<SqlBool>`s.api_key_id IS NOT NULL` : sql<SqlBool>`s.api_key_id IS NULL`));
        return parts.length ? sql<SqlBool>`(${sql.join(parts, sql` OR `)})` : null;
      },
    },
    failures: { column: sql`s.consecutive_failures` },
    created: { column: sql`s.created_at` },
  },
  sorts: { id: sql`s.subscription_id`, created: sql`s.created_at`, failures: sql`s.consecutive_failures` },
  tiebreak: { column: sql`s.subscription_id`, dir: 'desc' },
  search: (q) => {
    const term = q.trim().slice(0, 200);
    if (!term) return null;
    const like = likeContains(term);
    const id = ID_RE.test(term) ? Number(term.replace('#', '')) : null;
    return sql<SqlBool>`(s.target_url LIKE ${like} OR fp.current_name LIKE ${like}
      OR a.display_name LIKE ${like} OR fm.display_name LIKE ${like}
      ${id !== null ? sql`OR s.subscription_id = ${id} OR s.account_id = ${id} OR s.firm_id = ${id}` : sql``})`;
  },
});

const WEBHOOK_COLS = sql`s.subscription_id, s.api_key_id, s.owner_uuid_bin, s.key_type, s.account_id, s.firm_id,
  s.target_url, s.active, s.consecutive_failures, s.disabled_at, s.created_at,
  a.display_name AS account_name, fm.display_name AS firm_name, fp.current_name AS owner_name`;

export async function listAdminWebhooks(state: AdminWebhooksState): Promise<AdminWebhookRow[]> {
  const c = compileTableState(state, adminWebhooksColumnMap);
  const r = await sql<AdminRawRow>`
    SELECT ${WEBHOOK_COLS} ${ADMIN_JOINS}
    ${c.where}
    ${c.orderBy}
    LIMIT ${state.limit} OFFSET ${(state.page - 1) * state.limit}
  `.execute(db);
  return r.rows.map(toAdminRow);
}

export async function countAdminWebhooks(state: AdminWebhooksState): Promise<number> {
  const c = compileTableState(state, adminWebhooksColumnMap);
  const r = await sql<{ c: string | number }>`SELECT COUNT(*) AS c ${ADMIN_JOINS} ${c.where}`.execute(db);
  return Number(r.rows[0]?.c ?? 0);
}

/** One webhook by id (before-state for audited admin changes). */
export async function findAdminWebhook(id: number): Promise<AdminWebhookRow | null> {
  const r = await sql<AdminRawRow>`SELECT ${WEBHOOK_COLS} ${ADMIN_JOINS} WHERE s.subscription_id = ${id}`.execute(db);
  return r.rows[0] ? toAdminRow(r.rows[0]) : null;
}

// ── API keys ────────────────────────────────────────────────────────────────

export interface AdminApiKeyRow {
  key_id: number;
  key_type: string;
  owner_uuid: string | null;
  owner_name: string | null;
  account_id: number | null;
  account_name: string | null;
  firm_id: number | null;
  firm_name: string | null;
  revoked: number;
  issued_at: Date | null;
  expires_at: Date | null;
  multiplier: string | null;
}

export const adminApiKeysColumnMap = defineColumnMap({
  table: adminApiKeysTable,
  filters: {
    type: { column: sql`k.key_type`, values: { personal: 'PERSONAL', business: 'BUSINESS', government: 'GOVERNMENT', service: 'SERVICE' } },
    status: {
      compile: (f) => {
        if (f.values.length !== 1) return null;
        return f.values[0] === 'revoked' ? sql<SqlBool>`k.revoked = 1` : sql<SqlBool>`k.revoked = 0`;
      },
    },
    owner: { compile: (f) => playerCondition(sql`k.owner_uuid_bin`, f.values) },
    issued: { column: sql`k.issued_at` },
    expires: { column: sql`k.expires_at` },
  },
  sorts: { issued: sql`k.issued_at`, expires: sql`k.expires_at`, id: sql`k.key_id` },
  tiebreak: { column: sql`k.key_id`, dir: 'desc' },
  search: (q) => {
    const term = q.trim().slice(0, 64);
    if (!term) return null;
    const like = likeContains(term);
    const id = ID_RE.test(term) ? Number(term.replace('#', '')) : null;
    return sql<SqlBool>`(fp.current_name LIKE ${like} OR a.display_name LIKE ${like} OR f.display_name LIKE ${like}
      ${id !== null ? sql`OR k.key_id = ${id} OR k.account_id = ${id} OR k.firm_id = ${id}` : sql``})`;
  },
});

const KEY_FROM = sql`FROM api_keys k
  LEFT JOIN economy_players fp ON fp.player_uuid_bin = k.owner_uuid_bin
  LEFT JOIN accounts a ON a.account_id = k.account_id
  LEFT JOIN firm f ON f.firm_id = k.firm_id
  LEFT JOIN api_rate_limit_override o ON o.owner_uuid_bin = k.owner_uuid_bin`;

type RawKey = Omit<AdminApiKeyRow, 'owner_uuid'> & { owner_uuid_bin: Buffer | null };
const KEY_COLS = sql`k.key_id, k.key_type, k.owner_uuid_bin, fp.current_name AS owner_name,
  k.account_id, a.display_name AS account_name, k.firm_id, f.display_name AS firm_name,
  k.revoked, k.issued_at, k.expires_at, o.multiplier`;

function mapKey(row: RawKey): AdminApiKeyRow {
  const { owner_uuid_bin, ...rest } = row;
  return { ...rest, owner_uuid: owner_uuid_bin ? binToUuid(owner_uuid_bin) : null, revoked: Number(row.revoked) };
}

export async function listAdminApiKeyPage(state: AdminApiKeysState): Promise<AdminApiKeyRow[]> {
  const c = compileTableState(state, adminApiKeysColumnMap);
  const r = await sql<RawKey>`
    SELECT ${KEY_COLS} ${KEY_FROM}
    ${c.where}
    ${c.orderBy}
    LIMIT ${state.limit} OFFSET ${(state.page - 1) * state.limit}
  `.execute(db);
  return r.rows.map(mapKey);
}

export async function countAdminApiKeys(state: AdminApiKeysState): Promise<{ total: number; active: number }> {
  const c = compileTableState(state, adminApiKeysColumnMap);
  const r = await sql<{ c: string | number; active: string | number | null }>`
    SELECT COUNT(*) AS c, SUM(k.revoked = 0) AS active ${KEY_FROM} ${c.where}
  `.execute(db);
  return { total: Number(r.rows[0]?.c ?? 0), active: Number(r.rows[0]?.active ?? 0) };
}

/** One key by id (before-state for audited revoke/rotate). */
export async function findAdminApiKey(keyId: number): Promise<AdminApiKeyRow | null> {
  const r = await sql<RawKey>`SELECT ${KEY_COLS} ${KEY_FROM} WHERE k.key_id = ${keyId}`.execute(db);
  return r.rows[0] ? mapKey(r.rows[0]) : null;
}

/** An issuer's rate-limit override (before-state for audited limit changes), or null for the default. */
export async function findRateLimitOverride(ownerUuid: string): Promise<{ multiplier: string; note: string | null } | null> {
  const r = await sql<{ multiplier: string; note: string | null }>`
    SELECT multiplier, note FROM api_rate_limit_override WHERE owner_uuid_bin = ${uuidToBin(ownerUuid)}
  `.execute(db);
  return r.rows[0] ?? null;
}

/** Names for player UUIDs on player chips. */
export async function findPlayerNamesByUuid(uuids: readonly string[]): Promise<Map<string, string>> {
  const valid = uuids.filter((u) => UUID_RE.test(u));
  if (!valid.length) return new Map();
  const r = await sql<{ player_uuid_bin: Buffer; current_name: string }>`
    SELECT player_uuid_bin, current_name FROM economy_players WHERE player_uuid_bin IN (${sql.join(valid.map(uuidToBin))})
  `.execute(db);
  return new Map(r.rows.map((row) => [binToUuid(row.player_uuid_bin), row.current_name]));
}

/** Players whose name starts with `prefix` (unique name_lower index), for player typeaheads. */
export async function findPlayersByPrefix(prefix: string, limit = 8): Promise<EntityOption[]> {
  const term = prefix.trim().toLowerCase().slice(0, 32);
  if (!term) return [];
  const r = await sql<{ player_uuid_bin: Buffer; current_name: string }>`
    SELECT p.player_uuid_bin, p.current_name FROM economy_players p
    WHERE p.name_lower LIKE ${likePrefix(term)}
    ORDER BY p.name_lower
    LIMIT ${limit}
  `.execute(db);
  return r.rows.map((row) => {
    const uuid = binToUuid(row.player_uuid_bin);
    return { value: uuid, label: row.current_name, detail: `${uuid.slice(0, 8)}…` };
  });
}

// ── Groups ──────────────────────────────────────────────────────────────────

export const adminGroupsColumnMap = defineColumnMap({
  table: adminGroupsTable,
  filters: {
    source: {
      compile: (f) => {
        const parts = f.values.map((v) => {
          if (v === 'manual') return sql<SqlBool>`(g.luckperms_node IS NULL AND g.sync_sources IS NULL)`;
          if (v === 'luckperms') return sql<SqlBool>`g.luckperms_node IS NOT NULL`;
          return sql<SqlBool>`EXISTS (SELECT 1 FROM explorer_group_sync s WHERE s.group_id = g.group_id AND s.source = ${v})`;
        });
        return parts.length ? sql<SqlBool>`(${sql.join(parts, sql` OR `)})` : null;
      },
    },
    role: {
      compile: (f) => {
        const v = f.values[0];
        if (!v) return null;
        const match = f.op === 'contains' ? sql`gc.capability LIKE ${likeContains(v)}` : sql`gc.capability = ${v}`;
        return sql<SqlBool>`EXISTS (SELECT 1 FROM explorer_group_capability gc WHERE gc.group_id = g.group_id AND ${match})`;
      },
    },
  },
  sorts: { name: sql`g.name`, members: sql`g.active_count` },
  tiebreak: { column: sql`g.group_id`, dir: 'asc' },
  search: (q) => {
    const like = likeContains(q.trim().slice(0, 100));
    return sql<SqlBool>`(g.name LIKE ${like} OR g.description LIKE ${like} OR g.luckperms_node LIKE ${like})`;
  },
});

export function listAdminGroups(state: AdminGroupsState): Promise<GroupSummary[]> {
  const c = compileTableState(state, adminGroupsColumnMap);
  return listGroupsWhere(c.where, c.orderBy, state.limit, (state.page - 1) * state.limit);
}

export function countAdminGroups(state: AdminGroupsState): Promise<number> {
  return countGroupsWhere(compileTableState(state, adminGroupsColumnMap).where);
}

// ── Roles ───────────────────────────────────────────────────────────────────

export interface AdminRoleRow {
  key: string;
  name: string;
  description: string | null;
  permissionCount: number;
  memberCount: number;
  groupCount: number;
}

export const adminRolesColumnMap = defineColumnMap({
  table: adminRolesTable,
  filters: {
    permission: {
      compile: (f) => {
        const v = f.values[0];
        if (!v) return null;
        const match = f.op === 'contains' ? sql`p.permission LIKE ${likeContains(v)}` : sql`p.permission = ${v}`;
        return sql<SqlBool>`EXISTS (SELECT 1 FROM explorer_role_permission p WHERE p.role_key = r.role_key AND ${match})`;
      },
    },
  },
  sorts: { name: sql`r.name`, permissions: sql`r.permission_count`, members: sql`r.member_count` },
  tiebreak: { column: sql`r.role_key`, dir: 'asc' },
  search: (q) => {
    const like = likeContains(q.trim().slice(0, 100));
    return sql<SqlBool>`(r.role_key LIKE ${like} OR r.name LIKE ${like} OR r.description LIKE ${like})`;
  },
});

const ROLE_SUMMARY = sql`(
      SELECT d.role_key, d.name, d.description,
             (SELECT COUNT(*) FROM explorer_role_permission p WHERE p.role_key = d.role_key) AS permission_count,
             (SELECT COUNT(*) FROM explorer_role er WHERE er.role = d.role_key) AS member_count,
             (SELECT COUNT(*) FROM explorer_group_capability gc WHERE gc.capability = d.role_key) AS group_count
      FROM explorer_role_definition d
) r`;

export async function countAdminRoles(state: AdminRolesState): Promise<number> {
  const c = compileTableState(state, adminRolesColumnMap);
  const r = await sql<{ total: number | string }>`SELECT COUNT(*) AS total FROM ${ROLE_SUMMARY} ${c.where}`.execute(db);
  return Number(r.rows[0]?.total ?? 0);
}

export async function listAdminRoles(state: AdminRolesState): Promise<AdminRoleRow[]> {
  const c = compileTableState(state, adminRolesColumnMap);
  const r = await sql<{ role_key: string; name: string; description: string | null; permission_count: number | string; member_count: number | string; group_count: number | string }>`
    SELECT r.* FROM ${ROLE_SUMMARY}
    ${c.where}
    ${c.orderBy}
    LIMIT ${state.limit} OFFSET ${(state.page - 1) * state.limit}
  `.execute(db);
  return r.rows.map((row) => ({
    key: row.role_key,
    name: row.name,
    description: row.description,
    permissionCount: Number(row.permission_count),
    memberCount: Number(row.member_count),
    groupCount: Number(row.group_count),
  }));
}
