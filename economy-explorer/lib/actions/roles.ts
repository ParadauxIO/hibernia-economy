'use server';
import { revalidatePath } from 'next/cache';
import { requireLinkedViewer } from '@/lib/auth/linked';
import { requirePermission } from '@/lib/auth/access';
import { createRole, updateRole, deleteRole, setDirectRoleMember, type RoleInput } from '@/lib/services/role';
import { resolvePlayerUuid } from '@/lib/services/group';
import type { Permission } from '@/lib/auth/policy';

async function run(permission: Permission, operation: () => Promise<void>) {
  try {
    requirePermission(await requireLinkedViewer(), permission);
    await operation();
    revalidatePath('/admin', 'layout');
    return { ok: true };
  } catch (error) { return { ok: false, error: error instanceof Error ? error.message : 'Role update failed.' }; }
}
export async function createRoleAction(input: RoleInput) { return run('roles.create', () => createRole(input)); }
export async function updateRoleAction(input: RoleInput, revision: number) { return run('roles.update', () => updateRole(input, revision)); }
export async function deleteRoleAction(key: string, revision: number) { return run('roles.delete', () => deleteRole(key, revision)); }
export async function setDirectRoleMemberAction(key: string, identifier: string, grant: boolean) {
  return run('roles.assign', async () => {
    const uuid = await resolvePlayerUuid(identifier);
    if (!uuid) throw new Error('Player not found. Enter a known name or full UUID.');
    await setDirectRoleMember(key, uuid, grant);
  });
}
