import 'server-only';
import type { AdminGroupMembersState } from '@/lib/tables/admin';
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
  upsertGroupSync,
  deleteGroupSync,
  clearSourceMembers,
  countGroupMembersBySource,
  countGroupMembers,
  isManualGroupMember,
  type GroupSummary,
  type GroupMember,
  type GroupSyncSource,
} from '@/lib/sql/group';
import { findFirmById } from '@/lib/sql/firm';

/**
 * Group RBAC service. Owns the orchestration and validation for the group admin
 * tools — the multi-statement capability replacement and the admin identifier
 * resolution — and exposes the rest of the domain as thin pass-throughs over the
 * pure {@code lib/sql/group} DAL, so pages and server actions depend on this
 * service rather than reaching the DAL directly.
 */

export type { GroupSummary, GroupMember, GroupSyncSource };

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
}): Promise<number> {
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

const REALTY_SELECTOR = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\/[^\s/][^\s]{0,200}$/;
const FIRM_SELECTOR = /^[1-9]\d{0,9}$/;

/**
 * Validate a firm or region source selector, as the plugin will parse it:
 * a firm id (BusinessGroupMembers) or `world-uuid/region-id` (RealtyGroupMembers).
 * Region ids are case-sensitive and kept as typed.
 */
export async function normaliseSyncSelector(source: GroupSyncSource['source'], raw: string): Promise<string> {
  const selector = raw.trim();
  if (source === 'business') {
    if (!FIRM_SELECTOR.test(selector)) throw new Error('Enter the firm id (a number), e.g. 7.');
    if (!await findFirmById(Number(selector))) throw new Error(`No firm has id ${selector}.`);
    return selector;
  }
  if (!REALTY_SELECTOR.test(selector)) throw new Error('Enter world-uuid/region-id, e.g. 2b7c…-…/market_plot.');
  const [world = '', region = ''] = selector.split('/', 2);
  return `${world.toLowerCase()}/${region}`;
}

/**
 * Set or clear one firm/region source. Changing or clearing it removes the
 * source's old synced members in the same transaction; manual and LuckPerms
 * members are untouched. Gated by groups.sync.update and the delegation check
 * on the group's roles, like the LuckPerms source.
 */
export async function setGroupSyncSource(groupId: number, source: GroupSyncSource['source'], selector: string | null): Promise<void> {
  if (!['business', 'realty'].includes(source)) throw new Error('Unknown source.');
  const value = selector === null || selector.trim() === '' ? null : await normaliseSyncSelector(source, selector);
  await mutateGroup(groupId, 'groups.sync.update', async trx => {
    const current = (await selectGroupById(groupId, trx))?.syncSources.find(s => s.source === source)?.selector ?? null;
    if (current === value) return;
    if (value === null) await deleteGroupSync(groupId, source, trx);
    else await upsertGroupSync(groupId, source, value, trx);
    await clearSourceMembers(groupId, source, trx);
  });
}

export function groupMemberCountsBySource(groupId: number) {
  return countGroupMembersBySource(groupId);
}

export async function listGroupMembers(groupId: number, state: AdminGroupMembersState) {
  requirePermission(await getViewer(), 'groups.read');
  const total = await countGroupMembers(groupId, state);
  const page = Math.min(state.page, Math.max(1, Math.ceil(total / state.limit)));
  const rows = await listGroupMembersDal(groupId, { ...state, page });
  return { rows, total, page };
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

export function isManualMember(groupId: number, uuid: string): Promise<boolean> {
  return isManualGroupMember(groupId, uuid);
}
