import 'server-only';
import { sql } from 'kysely';
import { db, binToUuid } from '@/lib/db';
import { activeGroupMembership } from './group-membership';

export type GrantSort = 'name' | 'role' | 'source';
export type GrantFilters = {
  q?: string;
  role?: string;
  /** One source, or several (`sources` wins when both are given). */
  source?: string;
  sources?: readonly string[];
  status?: string;
  page?: number;
  pageSize?: number;
  sort?: GrantSort;
  dir?: 'asc' | 'desc';
};
export const GRANT_PAGE_SIZE = 50;

/** One row per role source per user: direct grants, group memberships and the player baseline. */
const GRANTS = sql`(
  SELECT r.player_uuid_bin, d.role_key, d.name AS role_name, NULL AS group_id,
    NULL AS group_name, 'direct' AS source, NULL AS selector, 1 AS active,
    NULL AS synced_at, r.granted_at AS added_at, r.granted_by_uuid_bin AS added_by
  FROM explorer_role r JOIN explorer_role_definition d ON d.role_key = r.role
  UNION ALL
  SELECT gm.player_uuid_bin, d.role_key, d.name, g.group_id, g.name, gm.source,
    CASE WHEN gm.source = 'luckperms' THEN g.luckperms_node ELSE gm.sync_selector END,
    COALESCE(${activeGroupMembership}, 0), gm.synced_at, gm.added_at, gm.added_by_uuid_bin
  FROM explorer_group_member gm JOIN explorer_group g ON g.group_id = gm.group_id
  JOIN explorer_group_capability gc ON gc.group_id = g.group_id
  JOIN explorer_role_definition d ON d.role_key = gc.capability
  UNION ALL
  SELECT users.player_uuid_bin, d.role_key, d.name, NULL, NULL, 'baseline', NULL, 1, NULL, NULL, NULL
  FROM (SELECT player_uuid_bin FROM economy_players UNION SELECT player_uuid_bin FROM explorer_identity
    UNION SELECT player_uuid_bin FROM explorer_role UNION SELECT player_uuid_bin FROM explorer_group_member) users
  JOIN explorer_role_definition d ON d.role_key = 'player'
) grants LEFT JOIN economy_players p ON p.player_uuid_bin = grants.player_uuid_bin`;

function grantWhere(filters: GrantFilters) {
  const q = (filters.q ?? '').trim().slice(0, 100);
  const sources = filters.sources?.length ? filters.sources : filters.source ? [filters.source] : [];
  return sql`${q ? sql`(LOCATE(LOWER(${q}), LOWER(COALESCE(p.current_name, ''))) > 0
      OR LOCATE(LOWER(${q}), LOWER(CONCAT(SUBSTR(HEX(grants.player_uuid_bin),1,8), '-', SUBSTR(HEX(grants.player_uuid_bin),9,4), '-', SUBSTR(HEX(grants.player_uuid_bin),13,4), '-', SUBSTR(HEX(grants.player_uuid_bin),17,4), '-', SUBSTR(HEX(grants.player_uuid_bin),21)))) > 0
      OR LOCATE(LOWER(${q}), LOWER(COALESCE(grants.group_name, ''))) > 0
      OR LOCATE(LOWER(${q}), LOWER(COALESCE(grants.selector, ''))) > 0)` : sql`TRUE`}
      AND ${filters.role ? sql`grants.role_key = ${filters.role.slice(0, 64)}` : sql`TRUE`}
      AND ${sources.length ? sql`grants.source IN (${sql.join(sources)})` : sql`TRUE`}
      AND ${filters.status === 'active' ? sql`grants.active = 1` : filters.status === 'inactive' ? sql`grants.active = 0` : sql`TRUE`}`;
}

function grantOrder(filters: GrantFilters) {
  const dir = sql.raw(filters.dir === 'desc' ? 'DESC' : 'ASC');
  const name = sql`COALESCE(p.current_name, '') ${dir}, grants.player_uuid_bin ${dir}`;
  switch (filters.sort) {
    case 'role': return sql`grants.role_key ${dir}, ${name}, grants.source, grants.group_id`;
    case 'source': return sql`grants.source ${dir}, ${name}, grants.role_key, grants.group_id`;
    default: return sql`${name}, grants.role_key, grants.source, grants.group_id`;
  }
}

export async function listRoleGrants(filters: GrantFilters = {}) {
  const page = Math.max(1, Math.min(10000, Math.floor(filters.page || 1)));
  const size = Math.max(1, Math.min(200, Math.floor(filters.pageSize || GRANT_PAGE_SIZE)));
  const result = await sql<{
    player_uuid_bin: Buffer; name: string | null; role_key: string; role_name: string;
    group_id: number | null; group_name: string | null; source: string;
    selector: string | null; active: number; synced_at: Date | null; added_at: Date | null; added_by: Buffer | null;
  }>`
    SELECT grants.*, p.current_name AS name FROM ${GRANTS}
    WHERE ${grantWhere(filters)}
    ORDER BY ${grantOrder(filters)}
    LIMIT ${size + 1} OFFSET ${(page - 1) * size}
  `.execute(db);
  return { page, hasMore: result.rows.length > size, rows: result.rows.slice(0, size).map(row => ({
    uuid: binToUuid(row.player_uuid_bin), name: row.name, role: row.role_key, roleName: row.role_name,
    groupId: row.group_id, group: row.group_name, source: row.source, selector: row.selector,
    active: Boolean(row.active), addedBy: row.added_by ? binToUuid(row.added_by) : null, syncedAt: row.synced_at?.toISOString() ?? null, addedAt: row.added_at?.toISOString() ?? null,
  })) };
}

/** Matching grant rows, for the table's page count. */
export async function countRoleGrants(filters: GrantFilters = {}): Promise<number> {
  const r = await sql<{ c: string | number }>`SELECT COUNT(*) AS c FROM ${GRANTS} WHERE ${grantWhere(filters)}`.execute(db);
  return Number(r.rows[0]?.c ?? 0);
}
