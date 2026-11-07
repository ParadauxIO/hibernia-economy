import { activeGroupMembership } from '@/lib/sql/group-membership';
import 'server-only';
import { adminRoleMembersTable, type AdminRoleMembersState } from '@/lib/tables/admin';
import { compileTableState, defineColumnMap, likeContains } from '@/lib/sql/tableState';
import { sql, type SqlBool, type Kysely } from 'kysely';
import { db, uuidToBin, binToUuid, type DB } from '@/lib/db';
import { isPermission, type Permission } from '@/lib/auth/policy';
export type ExplorerRole = string;
export type RoleDefinition = { key: string; name: string; description: string | null; revision: number; permissions: Permission[] };
export type Executor = Kysely<DB>;

/** Baseline plus direct and active source-scoped group roles, resolved per request. */
export async function resolveAuthorization(uuid: string | null, executor: Executor = db) {
  const result = await sql<{ role_key: string; permission: string | null }>`
    SELECT DISTINCT d.role_key, p.permission FROM explorer_role_definition d
    LEFT JOIN explorer_role_permission p ON p.role_key = d.role_key
    WHERE d.role_key = 'player' OR ${uuid ? sql`(
      EXISTS (SELECT 1 FROM explorer_role r WHERE r.role = d.role_key AND r.player_uuid_bin = ${uuidToBin(uuid)})
      OR EXISTS (SELECT 1 FROM explorer_group_capability gc
        JOIN explorer_group_member gm ON gm.group_id = gc.group_id
        JOIN explorer_group g ON g.group_id = gm.group_id
        WHERE gc.capability = d.role_key AND gm.player_uuid_bin = ${uuidToBin(uuid)}
          AND ${activeGroupMembership})
    )` : sql`FALSE`}
  `.execute(executor);
  return {
    roles: [...new Set(result.rows.map(r => r.role_key))],
    permissions: [...new Set(result.rows.map(r => r.permission).filter((p): p is Permission => p !== null && isPermission(p)))],
  };
}
export async function permissionsForRoles(roles: readonly string[], executor: Executor = db): Promise<Permission[]> {
  if (!roles.length) return [];
  const result = await sql<{ permission: string }>`SELECT DISTINCT permission FROM explorer_role_permission
    WHERE role_key IN (${sql.join(roles)})`.execute(executor);
  return result.rows.map(r => r.permission).filter(isPermission);
}
export async function listRoleDefinitions(executor: Executor = db): Promise<RoleDefinition[]> {
  const result = await sql<{ role_key: string; name: string; description: string | null; revision: number; permission: string | null }>`
    SELECT d.*, p.permission FROM explorer_role_definition d
    LEFT JOIN explorer_role_permission p ON p.role_key = d.role_key ORDER BY d.name, p.permission
  `.execute(executor);
  const roles = new Map<string, RoleDefinition>();
  for (const row of result.rows) {
    let role = roles.get(row.role_key);
    if (!role) { role = { key: row.role_key, name: row.name, description: row.description, revision: row.revision, permissions: [] }; roles.set(row.role_key, role); }
    if (row.permission && isPermission(row.permission)) role.permissions.push(row.permission);
  }
  return [...roles.values()];
}

const directMemberColumns = defineColumnMap({
  table: adminRoleMembersTable, filters: {}, sorts: { name: sql`p.current_name` },
  tiebreak: { column: sql`r.player_uuid_bin`, dir: 'asc' },
  search: (q) => sql<SqlBool>`(p.current_name LIKE ${likeContains(q)} OR HEX(r.player_uuid_bin) LIKE ${likeContains(q.replaceAll('-', ''))})`,
});

export async function countDirectRoleMembers(key: string, state: AdminRoleMembersState): Promise<number> {
  const c = compileTableState(state, directMemberColumns);
  const result = await sql<{ total: number | string }>`
    SELECT COUNT(*) AS total FROM explorer_role r LEFT JOIN economy_players p ON p.player_uuid_bin = r.player_uuid_bin
    WHERE r.role = ${key} ${c.conditions.length ? sql`AND ${sql.join(c.conditions, sql` AND `)}` : sql``}
  `.execute(db);
  return Number(result.rows[0]?.total ?? 0);
}

export async function listDirectRoleMemberPage(key: string, state: AdminRoleMembersState) {
  const c = compileTableState(state, directMemberColumns);
  const result = await sql<{ player_uuid_bin: Buffer; name: string | null }>`
    SELECT r.player_uuid_bin, p.current_name AS name FROM explorer_role r
    LEFT JOIN economy_players p ON p.player_uuid_bin = r.player_uuid_bin
    WHERE r.role = ${key} ${c.conditions.length ? sql`AND ${sql.join(c.conditions, sql` AND `)}` : sql``}
    ${c.orderBy} LIMIT ${state.limit} OFFSET ${(state.page - 1) * state.limit}
  `.execute(db);
  return result.rows.map(row => ({ uuid: binToUuid(row.player_uuid_bin), name: row.name }));
}
