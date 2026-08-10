'use server';
import { revalidatePath } from 'next/cache';
import { getViewer } from '@/lib/auth/viewer';
import {
  setRateLimitOverride,
  clearRateLimitOverride,
  revokeApiKey,
  rotateApiKey,
} from '@/lib/treasury';
import { auditView } from '@/lib/audit';
import {
  createGroup,
  deleteGroup,
  setGroupCapabilities,
  setLuckpermsNode,
  addManualMember,
  removeManualMember,
  resolvePlayerUuid,
} from '@/lib/services/group';
import { isCapability } from '@/lib/auth/capabilities';
import { requireAdmin } from '@/lib/auth/requireAdmin';

// ---- Group RBAC management -------------------------------------------------

type ActionResult = { ok: boolean; error?: string };

export async function createGroupAction(args: { name: string; description: string | null }): Promise<ActionResult> {
  const viewer = await getViewer();
  requireAdmin(viewer);
  try {
    const name = args.name.trim();
    if (!name) return { ok: false, error: 'Name is required.' };
    await createGroup({ name, description: args.description?.trim() || null, createdBy: viewer.minecraftUuid });
    await auditView(viewer, { method: 'POST', path: '/admin/groups/create', targetType: 'global', targetId: `group:${name}` });
    revalidatePath('/admin/groups');
    return { ok: true };
  } catch (e) {
    return { ok: false, error: e instanceof Error ? e.message : String(e) };
  }
}

export async function deleteGroupAction(groupId: number): Promise<ActionResult> {
  const viewer = await getViewer();
  requireAdmin(viewer);
  try {
    await deleteGroup(groupId);
    await auditView(viewer, { method: 'POST', path: '/admin/groups/delete', targetType: 'global', targetId: `group:${groupId}` });
    revalidatePath('/admin/groups');
    return { ok: true };
  } catch (e) {
    return { ok: false, error: e instanceof Error ? e.message : String(e) };
  }
}

export async function setGroupCapabilitiesAction(groupId: number, capabilities: string[]): Promise<ActionResult> {
  const viewer = await getViewer();
  requireAdmin(viewer);
  try {
    const valid = capabilities.filter(isCapability);
    await setGroupCapabilities(groupId, valid);
    await auditView(viewer, { method: 'POST', path: '/admin/groups/capabilities', targetType: 'global', targetId: `group:${groupId}` });
    revalidatePath(`/admin/groups/${groupId}`);
    revalidatePath('/admin/groups');
    return { ok: true };
  } catch (e) {
    return { ok: false, error: e instanceof Error ? e.message : String(e) };
  }
}

export async function setLuckpermsNodeAction(groupId: number, node: string | null): Promise<ActionResult> {
  const viewer = await getViewer();
  requireAdmin(viewer);
  try {
    await setLuckpermsNode(groupId, node?.trim() || null);
    await auditView(viewer, { method: 'POST', path: '/admin/groups/luckperms-node', targetType: 'global', targetId: `group:${groupId}` });
    revalidatePath(`/admin/groups/${groupId}`);
    revalidatePath('/admin/groups');
    return { ok: true };
  } catch (e) {
    return { ok: false, error: e instanceof Error ? e.message : String(e) };
  }
}

export async function addGroupMemberAction(groupId: number, identifier: string): Promise<ActionResult> {
  const viewer = await getViewer();
  requireAdmin(viewer);
  try {
    const uuid = await resolvePlayerUuid(identifier);
    if (!uuid) return { ok: false, error: `No player found for "${identifier}".` };
    await addManualMember(groupId, uuid, viewer.minecraftUuid);
    await auditView(viewer, { method: 'POST', path: '/admin/groups/member-add', targetType: 'player', targetId: uuid });
    revalidatePath(`/admin/groups/${groupId}`);
    return { ok: true };
  } catch (e) {
    return { ok: false, error: e instanceof Error ? e.message : String(e) };
  }
}

export async function removeGroupMemberAction(groupId: number, playerUuid: string): Promise<ActionResult> {
  const viewer = await getViewer();
  requireAdmin(viewer);
  try {
    await removeManualMember(groupId, playerUuid);
    await auditView(viewer, { method: 'POST', path: '/admin/groups/member-remove', targetType: 'player', targetId: playerUuid });
    revalidatePath(`/admin/groups/${groupId}`);
    return { ok: true };
  } catch (e) {
    return { ok: false, error: e instanceof Error ? e.message : String(e) };
  }
}

export async function revokeApiKeyAction(keyId: number): Promise<{ ok: boolean; error?: string }> {
  const viewer = await getViewer();
  requireAdmin(viewer);
  try {
    // ADT-14: route through the REST admin API (kysely stays read-only).
    await revokeApiKey(keyId);
    await auditView(viewer, { method: 'POST', path: '/admin/api-keys/revoke', targetType: 'global', targetId: `key:${keyId}` });
    revalidatePath('/admin/api-keys');
    return { ok: true };
  } catch (e) {
    return { ok: false, error: e instanceof Error ? e.message : String(e) };
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
  requireAdmin(viewer);
  try {
    const { expiresAt } = await rotateApiKey(keyId);
    await auditView(viewer, { method: 'POST', path: '/admin/api-keys/rotate', targetType: 'global', targetId: `key:${keyId}` });
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
  requireAdmin(viewer);
  try {
    const mult = clamp(args.multiplier, 0.1, 1000);
    // Route the write through the REST admin API (ADT-14) — kysely stays read-only.
    if (mult === 1 && (!args.note || !args.note.trim())) {
      await clearRateLimitOverride(args.ownerUuid);
    } else {
      await setRateLimitOverride(args.ownerUuid, mult.toFixed(2), args.note?.trim() || null);
    }
    await auditView(viewer, { method: 'POST', path: '/admin/api-keys/rate-limit', targetType: 'player', targetId: args.ownerUuid });
    revalidatePath('/admin/api-keys');
    return { ok: true };
  } catch (e) {
    return { ok: false, error: e instanceof Error ? e.message : String(e) };
  }
}

function clamp(v: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, v));
}
