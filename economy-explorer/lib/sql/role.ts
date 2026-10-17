import 'server-only';
import { sql, type Kysely } from 'kysely';
import { db, uuidToBin, type DB } from '@/lib/db';
import { isPermission, type Permission } from '@/lib/auth/policy';
export type ExplorerRole = string;
export type RoleDefinition = { key: string; name: string; description: string | null; revision: number; permissions: Permission[] };
export type Executor = Kysely<DB>;

export async function findRoles(uuid: string): Promise<string[]> {
  const result = await sql<{ role: string }>`SELECT r.role FROM explorer_role r
    JOIN explorer_role_definition d ON d.role_key = r.role
    WHERE r.player_uuid_bin = ${uuidToBin(uuid)}`.execute(db);
  return result.rows.map(r => r.role);
}

/** Baseline plus direct and fresh LP/manual group roles, resolved per request. */
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
          AND (gm.source = 'manual' OR (g.luckperms_node IS NOT NULL AND gm.synced_at > CURRENT_TIMESTAMP - INTERVAL 1 HOUR)))
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
