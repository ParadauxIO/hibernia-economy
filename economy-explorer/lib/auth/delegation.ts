import 'server-only';
import { sql } from 'kysely';
import { db } from '@/lib/db';
import { getViewer, type Viewer } from './viewer';
import { requirePermission } from './access';
import type { Permission } from './policy';
import { ForbiddenError, NotFoundError } from '@/lib/errors';
import { listRoleDefinitions, permissionsForRoles, resolveAuthorization, type Executor } from '@/lib/sql/role';

/** Delegation can never grant permissions the operator does not currently hold. */
export async function assertCanGrantRoles(viewer: Viewer, keys: readonly string[], executor: Executor = db): Promise<void> {
  const roles = await listRoleDefinitions(executor);
  const actorPermissions = await permissionsForRoles(viewer.capabilities, executor);
  for (const key of new Set(keys)) {
    const role = roles.find(r => r.key === key);
    if (!role) throw new NotFoundError(`Unknown role: ${key}`);
    if (role.permissions.some(p => !actorPermissions.includes(p))) {
      throw new ForbiddenError(`You cannot grant or change a role with permissions you do not hold (${role.name}).`);
    }
  }
}

/** Serialize authorization edits so concurrent role/group edits cannot bypass delegation checks. */
export async function withPolicyMutation<T>(permission: Permission, operation: (trx: Executor, actor: Viewer) => Promise<T>): Promise<T> {
  const viewer = await getViewer();
  requirePermission(viewer, permission);
  return db.transaction().execute(async trx => {
    await sql`SELECT role_key FROM explorer_role_definition ORDER BY role_key FOR UPDATE`.execute(trx);
    const authorization = await resolveAuthorization(viewer.minecraftUuid, trx);
    const current = { ...viewer, capabilities: authorization.roles, permissions: authorization.permissions };
    requirePermission(current, permission);
    return operation(trx, current);
  });
}
