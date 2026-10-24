import 'server-only';
import { sql } from 'kysely';
import { z } from 'zod';
import { db, uuidToBin, binToUuid } from '@/lib/db';
import { getViewer, type Viewer } from '@/lib/auth/viewer';
import { requirePermission } from '@/lib/auth/access';
import { isPermission, type Permission } from '@/lib/auth/policy';
import { ForbiddenError } from '@/lib/errors';
import { listRoleDefinitions, permissionsForRoles, resolveAuthorization, type Executor } from '@/lib/sql/role';
import { assertCanGrantRoles, withPolicyMutation } from '@/lib/auth/delegation';
import { auditView } from '@/lib/audit';

const roleKey = z.string().regex(/^[a-z][a-z0-9_-]{0,31}$/);
const roleInput = z.object({
  key: roleKey,
  name: z.string().trim().min(1).max(64),
  description: z.string().trim().max(255).nullable(),
  permissions: z.array(z.string().refine(isPermission, 'Unknown permission.')).max(100),
});
export type RoleInput = z.infer<typeof roleInput>;

export async function listRolesForAdmin() {
  const viewer = await getViewer();
  requirePermission(viewer, 'roles.read');
  await auditView(viewer, { path: '/admin/roles', targetType: 'global' });
  return listRoleDefinitions();
}

async function mutate<T>(permission: Permission, key: string, operation: (trx: Executor, actor: Viewer) => Promise<T>): Promise<T> {
  const result = await withPolicyMutation(permission, async (trx, actor) => {
    const result = await operation(trx, actor);
    const remaining = await permissionsForRoles(actor.capabilities, trx);
    if (!remaining.includes(permission)) throw new ForbiddenError('This change would remove your own access to manage it. Grant another role first.');
    return result;
  });
  await auditView(await getViewer(), { method: 'POST', path: `/admin/roles/${permission}`, targetType: 'global', targetId: `role:${key}` });
  return result;
}

export async function createRole(input: RoleInput): Promise<void> {
  const value = roleInput.parse(input);
  await mutate('roles.create', value.key, async (trx, actor) => {
    if (value.permissions.some(p => !actor.permissions.includes(p as Permission))) throw new ForbiddenError('You can only grant permissions you hold.');
    const found = await sql`SELECT 1 FROM explorer_role_definition WHERE role_key = ${value.key}`.execute(trx);
    if (found.rows.length) throw new Error('A role with that key already exists.');
    await sql`INSERT INTO explorer_role_definition (role_key, name, description) VALUES (${value.key}, ${value.name}, ${value.description})`.execute(trx);
    for (const permission of new Set(value.permissions)) {
      await sql`INSERT INTO explorer_role_permission (role_key, permission) VALUES (${value.key}, ${permission})`.execute(trx);
    }
  });
}

export async function updateRole(input: RoleInput, revision: number): Promise<void> {
  z.number().int().positive().parse(revision);
  const value = roleInput.parse(input);
  await mutate('roles.update', value.key, async (trx, actor) => {
    await assertCanGrantRoles(actor, [value.key], trx);
    if (value.permissions.some(p => !actor.permissions.includes(p as Permission))) throw new ForbiddenError('You can only grant permissions you hold.');
    const changed = await sql`UPDATE explorer_role_definition SET name = ${value.name}, description = ${value.description}, revision = revision + 1
      WHERE role_key = ${value.key} AND revision = ${revision}`.execute(trx);
    if (Number(changed.numAffectedRows) !== 1) throw new Error('This role changed since you opened it. Refresh before saving.');
    await sql`DELETE FROM explorer_role_permission WHERE role_key = ${value.key}`.execute(trx);
    for (const permission of new Set(value.permissions)) {
      await sql`INSERT INTO explorer_role_permission (role_key, permission) VALUES (${value.key}, ${permission})`.execute(trx);
    }
  });
}

export async function deleteRole(key: string, revision: number): Promise<void> {
  roleKey.parse(key);
  z.number().int().positive().parse(revision);
  if (key === 'player') throw new Error('The player baseline cannot be deleted; its permissions can be edited.');
  await mutate('roles.delete', key, async (trx, actor) => {
    await assertCanGrantRoles(actor, [key], trx);
    const result = await sql`DELETE FROM explorer_role_definition WHERE role_key = ${key} AND revision = ${revision}`.execute(trx);
    if (Number(result.numAffectedRows) !== 1) throw new Error('This role changed since you opened it. Refresh before deleting.');
    // Foreign keys remove permissions AND direct/group assignments, preventing
    // deletion followed by re-creation of a key from resurrecting old grants.
  });
}

export async function listDirectRoleMembers(key: string) {
  const viewer = await getViewer();
  requirePermission(viewer, 'roles.read');
  await auditView(viewer, { path: `/admin/roles/${key}`, targetType: 'global', targetId: `role:${key}` });
  const result = await sql<{ player_uuid_bin: Buffer; name: string | null }>`
    SELECT r.player_uuid_bin, p.current_name AS name FROM explorer_role r
    LEFT JOIN economy_players p ON p.player_uuid_bin = r.player_uuid_bin WHERE r.role = ${key}
    ORDER BY p.current_name, r.player_uuid_bin
  `.execute(db);
  return result.rows.map(row => ({ uuid: binToUuid(row.player_uuid_bin), name: row.name }));
}
export async function setDirectRoleMember(key: string, uuid: string, grant: boolean): Promise<void> {
  roleKey.parse(key);
  z.string().uuid().parse(uuid);
  await mutate('roles.assign', key, async (trx, actor) => {
    await assertCanGrantRoles(actor, [key], trx);
    if (grant) {
      await sql`INSERT IGNORE INTO explorer_role (player_uuid_bin, role, granted_by_uuid_bin)
        VALUES (${uuidToBin(uuid)}, ${key}, ${actor.minecraftUuid ? uuidToBin(actor.minecraftUuid) : null})`.execute(trx);
    } else {
      await sql`DELETE FROM explorer_role WHERE player_uuid_bin = ${uuidToBin(uuid)} AND role = ${key}`.execute(trx);
      if (actor.minecraftUuid?.toLowerCase() === uuid.toLowerCase()) {
        const remaining = await resolveAuthorization(uuid, trx);
        if (!remaining.permissions.includes('roles.assign')) throw new ForbiddenError('Cannot remove your last role-assignment permission.');
      }
    }
  });
}
