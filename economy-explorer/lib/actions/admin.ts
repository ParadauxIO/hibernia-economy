'use server';
import { revalidatePath } from 'next/cache';
import { getViewer } from '@/lib/auth/viewer';
import {
  setRateLimitOverride,
  clearRateLimitOverride,
  revokeApiKey,
  rotateApiKey,
} from '@/lib/treasury';
import { auditMutation } from '@/lib/audit';
import {
  createGroup,
  deleteGroup,
  getGroup,
  listGroupMembers,
  setGroupCapabilities,
  setGroupSyncSource,
  setLuckpermsNode,
  addManualMember,
  removeManualMember,
  resolvePlayerUuid,
  type GroupSummary,
} from '@/lib/services/group';
import { findAdminApiKey, findRateLimitOverride } from '@/lib/sql/admin';
import { requirePermission } from '@/lib/auth/access';

// Every action records an audit row with the values before and after the change
// (lib/audit.ts auditMutation). The before value is read just before the write;
// the after value is what the write returned or a fresh read of the same thing.

type ActionResult = { ok: boolean; error?: string };

function failure(e: unknown): ActionResult {
  return { ok: false, error: e instanceof Error ? e.message : String(e) };
}

/** The parts of a group an admin can change, as recorded in the audit log. */
function groupSnapshot(g: GroupSummary | null) {
  if (!g) return null;
  return {
    name: g.name,
    description: g.description,
    roles: g.capabilities,
    luckpermsNode: g.luckpermsNode,
    syncSources: Object.fromEntries(g.syncSources.map((s) => [s.source, s.selector])),
    members: g.memberCount,
  };
}

async function isManualMember(groupId: number, uuid: string): Promise<boolean> {
  return (await listGroupMembers(groupId)).some((m) => m.uuid === uuid && m.source === 'manual');
}

// ---- Group RBAC management -------------------------------------------------

export async function createGroupAction(args: { name: string; description: string | null }): Promise<ActionResult> {
  const viewer = await getViewer();
  requirePermission(viewer, 'groups.create');
  try {
    const name = args.name.trim();
    if (!name) return { ok: false, error: 'Name is required.' };
    const description = args.description?.trim() || null;
    const groupId = await createGroup({ name, description, createdBy: viewer.minecraftUuid });
    await auditMutation(viewer, {
      action: 'group.create', path: '/admin/groups/create', targetType: 'group', targetId: groupId || name,
      before: null, after: { name, description },
    });
    revalidatePath('/admin/groups');
    return { ok: true };
  } catch (e) {
    return failure(e);
  }
}

export async function deleteGroupAction(groupId: number): Promise<ActionResult> {
  const viewer = await getViewer();
  requirePermission(viewer, 'groups.delete');
  try {
    const before = groupSnapshot(await getGroup(groupId));
    await deleteGroup(groupId);
    await auditMutation(viewer, { action: 'group.delete', path: '/admin/groups/delete', targetType: 'group', targetId: groupId, before, after: null });
    revalidatePath('/admin/groups');
    return { ok: true };
  } catch (e) {
    return failure(e);
  }
}

export async function setGroupCapabilitiesAction(groupId: number, capabilities: string[]): Promise<ActionResult> {
  const viewer = await getViewer();
  requirePermission(viewer, 'groups.roles.update');
  try {
    const before = (await getGroup(groupId))?.capabilities ?? null;
    await setGroupCapabilities(groupId, capabilities);
    await auditMutation(viewer, {
      action: 'group.roles.update', path: '/admin/groups/capabilities', targetType: 'group', targetId: groupId,
      before: before && { roles: before }, after: { roles: [...new Set(capabilities)].sort() },
    });
    revalidatePath(`/admin/groups/${groupId}`);
    revalidatePath('/admin/groups');
    return { ok: true };
  } catch (e) {
    return failure(e);
  }
}

export async function setLuckpermsNodeAction(groupId: number, node: string | null): Promise<ActionResult> {
  const viewer = await getViewer();
  requirePermission(viewer, 'groups.sync.update');
  try {
    const before = await getGroup(groupId);
    await setLuckpermsNode(groupId, node?.trim() || null);
    const after = await getGroup(groupId);
    await auditMutation(viewer, {
      action: 'group.sync.update', path: '/admin/groups/luckperms-node', targetType: 'group', targetId: groupId,
      before: before && { luckpermsNode: before.luckpermsNode, syncedMembers: before.luckpermsMemberCount },
      after: after && { luckpermsNode: after.luckpermsNode, syncedMembers: after.luckpermsMemberCount },
    });
    revalidatePath(`/admin/groups/${groupId}`);
    revalidatePath('/admin/groups');
    return { ok: true };
  } catch (e) {
    return failure(e);
  }
}

/** Set or clear a group's firm (`business`) or region (`realty`) source. */
export async function setGroupSyncSourceAction(groupId: number, source: 'business' | 'realty', selector: string | null): Promise<ActionResult> {
  const viewer = await getViewer();
  requirePermission(viewer, 'groups.sync.update');
  try {
    const pick = (g: GroupSummary | null) => g?.syncSources.find((s) => s.source === source)?.selector ?? null;
    const before = pick(await getGroup(groupId));
    await setGroupSyncSource(groupId, source, selector);
    const after = pick(await getGroup(groupId));
    await auditMutation(viewer, {
      action: 'group.source.update', path: '/admin/groups/sync-source', targetType: 'group', targetId: groupId,
      before: { source, selector: before }, after: { source, selector: after },
    });
    revalidatePath(`/admin/groups/${groupId}`);
    revalidatePath('/admin/groups');
    return { ok: true };
  } catch (e) {
    return failure(e);
  }
}

export async function addGroupMemberAction(groupId: number, identifier: string): Promise<ActionResult> {
  const viewer = await getViewer();
  requirePermission(viewer, 'groups.members.update');
  try {
    const uuid = await resolvePlayerUuid(identifier);
    if (!uuid) return { ok: false, error: `No player found for "${identifier}".` };
    const was = await isManualMember(groupId, uuid);
    await addManualMember(groupId, uuid, viewer.minecraftUuid);
    await auditMutation(viewer, {
      action: 'group.member.add', path: '/admin/groups/member-add', targetType: 'group', targetId: groupId,
      before: { player: uuid, manualMember: was }, after: { player: uuid, manualMember: true },
    });
    revalidatePath(`/admin/groups/${groupId}`);
    return { ok: true };
  } catch (e) {
    return failure(e);
  }
}

export async function removeGroupMemberAction(groupId: number, playerUuid: string): Promise<ActionResult> {
  const viewer = await getViewer();
  requirePermission(viewer, 'groups.members.update');
  try {
    const was = await isManualMember(groupId, playerUuid);
    await removeManualMember(groupId, playerUuid);
    await auditMutation(viewer, {
      action: 'group.member.remove', path: '/admin/groups/member-remove', targetType: 'group', targetId: groupId,
      before: { player: playerUuid, manualMember: was }, after: { player: playerUuid, manualMember: false },
    });
    revalidatePath(`/admin/groups/${groupId}`);
    return { ok: true };
  } catch (e) {
    return failure(e);
  }
}

// ---- API keys and issuer limits -------------------------------------------

function keySnapshot(k: Awaited<ReturnType<typeof findAdminApiKey>>) {
  return k && { revoked: k.revoked === 1, expiresAt: k.expires_at?.toISOString() ?? null, owner: k.owner_uuid, type: k.key_type };
}

export async function revokeApiKeyAction(keyId: number): Promise<{ ok: boolean; error?: string }> {
  const viewer = await getViewer();
  requirePermission(viewer, 'api_keys.revoke');
  try {
    const before = keySnapshot(await findAdminApiKey(keyId));
    // ADT-14: route through the REST admin API (kysely stays read-only).
    await revokeApiKey(keyId);
    await auditMutation(viewer, {
      action: 'api_key.revoke', path: '/admin/api-keys/revoke', targetType: 'api_key', targetId: keyId,
      before, after: keySnapshot(await findAdminApiKey(keyId)),
    });
    revalidatePath('/admin/api-keys');
    return { ok: true };
  } catch (e) {
    return failure(e);
  }
}

/**
 * Admin force-rotate via the REST admin API (ADT-14). The REST side
 * (AuthService.adminForceRotate) replaces the jti to invalidate the current
 * token and returns only the new expiry — it never mints/persists a token
 * (ADT-6), so the explorer no longer signs JWTs itself. The owner reissues
 * the new token in-game.
 */
export async function rotateApiKeyAction(keyId: number): Promise<{ ok: boolean; expiresAt?: string; error?: string }> {
  const viewer = await getViewer();
  requirePermission(viewer, 'api_keys.rotate');
  try {
    const before = keySnapshot(await findAdminApiKey(keyId));
    const { expiresAt } = await rotateApiKey(keyId);
    await auditMutation(viewer, {
      action: 'api_key.rotate', path: '/admin/api-keys/rotate', targetType: 'api_key', targetId: keyId,
      before, after: before && { ...before, expiresAt, tokenReplaced: true },
    });
    revalidatePath('/admin/api-keys');
    return { ok: true, expiresAt };
  } catch (e) {
    return { ok: false, error: e instanceof Error ? e.message : String(e) };
  }
}

/**
 * Set or clear a per-issuer rate-limit multiplier. multiplier = 1 with empty
 * note clears the row (returns to default); otherwise upserts. Clamped to
 * 0.10..1000, mirroring AdminApiKeyController.setRateLimit.
 */
export async function setRateLimitAction(args: {
  ownerUuid: string;
  multiplier: number;
  note: string | null;
}): Promise<{ ok: boolean; error?: string }> {
  const viewer = await getViewer();
  requirePermission(viewer, 'rate_limits.update');
  try {
    const mult = clamp(args.multiplier, 0.1, 1000);
    const note = args.note?.trim() || null;
    const before = (await findRateLimitOverride(args.ownerUuid)) ?? { multiplier: '1.00', note: null };
    // Route the write through the REST admin API (ADT-14) — kysely stays read-only.
    const cleared = mult === 1 && !note;
    if (cleared) {
      await clearRateLimitOverride(args.ownerUuid);
    } else {
      await setRateLimitOverride(args.ownerUuid, mult.toFixed(2), note);
    }
    await auditMutation(viewer, {
      action: 'rate_limit.update', path: '/admin/api-keys/rate-limit', targetType: 'player', targetId: args.ownerUuid,
      before, after: cleared ? { multiplier: '1.00', note: null } : { multiplier: mult.toFixed(2), note },
    });
    revalidatePath('/admin/api-keys');
    return { ok: true };
  } catch (e) {
    return failure(e);
  }
}

function clamp(v: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, v));
}
