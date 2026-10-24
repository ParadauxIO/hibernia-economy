import 'server-only';
import { getViewer } from '@/lib/auth/viewer';
import { requirePermission } from '@/lib/auth/access';
import { assertCanGrantRoles, withPolicyMutation } from '@/lib/auth/delegation';
import type { Permission } from '@/lib/auth/policy';
import type { Executor } from '@/lib/sql/role';
import type { Viewer } from '@/lib/auth/viewer';
import {
  clearSyncedMembers,
  listGroups as listGroupsDal,
  selectGroupById,
  createGroup as createGroupRow,
  deleteGroup as deleteGroupRow,
  deleteGroupCapabilities,
  insertGroupCapability,
  setLuckpermsNode as setLuckpermsNodeRow,
  listGroupMembers as listGroupMembersDal,
  addManualMember as addManualMemberRow,
  removeManualMember as removeManualMemberRow,
  findPlayerUuidByName,
  type GroupSummary,
  type GroupMember,
} from '@/lib/sql/group';

/**
 * Group RBAC service. Owns the orchestration and validation for the group admin
 * tools — the multi-statement capability replacement and the admin identifier
 * resolution — and exposes the rest of the domain as thin pass-throughs over the
 * pure {@code lib/sql/group} DAL, so pages and server actions depend on this
 * service rather than reaching the DAL directly.
 */

export type { GroupSummary, GroupMember };

const UUID_RE =
  /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

export function listGroups(): Promise<GroupSummary[]> {
  return listGroupsDal();
}

export function getGroup(groupId: number): Promise<GroupSummary | null> {
  return selectGroupById(groupId);
}

export async function createGroup(args: {
  name: string;
  description: string | null;
  createdBy: string | null;
}): Promise<void> {
  requirePermission(await getViewer(), 'groups.create');
  return createGroupRow(args);
}

async function mutateGroup<T>(groupId: number, permission: Permission, operation: (trx: Executor, actor: Viewer) => Promise<T>): Promise<T> {
  return withPolicyMutation(permission, async (trx, actor) => {
    const group = await selectGroupById(groupId, trx);
    if (!group) throw new Error('Group not found.');
    await assertCanGrantRoles(actor, group.capabilities, trx);
    return operation(trx, actor);
  });
}

export async function deleteGroup(groupId: number): Promise<void> {
  await mutateGroup(groupId, 'groups.delete', trx => deleteGroupRow(groupId, trx));
}

export async function setLuckpermsNode(groupId: number, node: string | null): Promise<void> {
  const normalized = node?.trim().toLowerCase() || null;
  if (normalized && !/^[a-z0-9_.*-]+$/.test(normalized)) throw new Error('Invalid LuckPerms node.');
  await mutateGroup(groupId, 'groups.sync.update', async trx => {
    await setLuckpermsNodeRow(groupId, normalized, trx);
    await clearSyncedMembers(groupId, trx);
  });
}

export function listGroupMembers(groupId: number): Promise<GroupMember[]> {
  return listGroupMembersDal(groupId);
}

export async function addManualMember(groupId: number, playerUuid: string, addedBy: string | null): Promise<void> {
  await mutateGroup(groupId, 'groups.members.update', trx => addManualMemberRow(groupId, playerUuid, addedBy, trx));
}

export async function removeManualMember(groupId: number, playerUuid: string): Promise<void> {
  await mutateGroup(groupId, 'groups.members.update', trx => removeManualMemberRow(groupId, playerUuid, trx));
}

/** Replace grants atomically under the same policy lock as role edits. */
export async function setGroupCapabilities(groupId: number, capabilities: string[]): Promise<void> {
  await mutateGroup(groupId, 'groups.roles.update', async (trx, actor) => {
    await assertCanGrantRoles(actor, capabilities, trx);
    await deleteGroupCapabilities(groupId, trx);
    for (const cap of new Set(capabilities)) await insertGroupCapability(groupId, cap, trx);
  });
}

/**
 * Resolves an admin-typed member identifier to a UUID: a canonical UUID is
 * accepted (and lower-cased) verbatim; anything else is looked up by current
 * name. Returns null if unresolved.
 */
export async function resolvePlayerUuid(input: string): Promise<string | null> {
  const trimmed = input.trim();
  if (UUID_RE.test(trimmed)) {
    return trimmed.toLowerCase();
  }
  return findPlayerUuidByName(trimmed);
}
