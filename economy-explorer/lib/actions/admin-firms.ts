'use server';

import { revalidatePath } from 'next/cache';
import { getViewer } from '@/lib/auth/viewer';
import { requireAdmin } from '@/lib/auth/requireAdmin';
import { auditView } from '@/lib/audit';
import { disbandFirm, renameFirm, updateFirmDetails, type DisbandResult, type FirmResult } from '@/lib/treasury';
import { findFirmById } from '@/lib/sql/firm';

/**
 * Disband a firm via the ledger-authoritative treasury-rest-api admin endpoint.
 * Requires the admin to retype the firm's exact name (type-to-confirm) before the
 * destructive action runs. Records an audit row with the per-account sweep result.
 */
export async function disbandFirmAction(
  firmId: number,
  confirmName: string,
): Promise<{ ok: boolean; error?: string; result?: DisbandResult }> {
  const viewer = await getViewer();
  requireAdmin(viewer);
  try {
    const firm = await findFirmById(firmId);
    if (!firm) return { ok: false, error: 'Firm not found.' };
    if (firm.archived) return { ok: false, error: 'Firm is already disbanded.' };
    if (confirmName.trim() !== firm.display_name) {
      return { ok: false, error: 'Confirmation text must match the firm name exactly.' };
    }
    const result = await disbandFirm(firmId);
    await auditView(viewer, {
      method: 'POST',
      path: '/admin/firms/disband',
      targetType: 'firm',
      targetId: String(firmId),
    });
    revalidatePath('/admin/firms');
    return { ok: true, result };
  } catch (e) {
    return { ok: false, error: e instanceof Error ? e.message : String(e) };
  }
}

/** Rename a firm via the treasury-rest-api admin endpoint (backend enforces the
 *  in-game name rules + case-insensitive uniqueness). */
export async function renameFirmAction(
  firmId: number,
  newName: string,
): Promise<{ ok: boolean; error?: string; result?: FirmResult }> {
  const viewer = await getViewer();
  requireAdmin(viewer);
  try {
    const name = newName.trim();
    if (!name) return { ok: false, error: 'A new name is required.' };
    const result = await renameFirm(firmId, name);
    await auditView(viewer, {
      method: 'POST',
      path: '/admin/firms/rename',
      targetType: 'firm',
      targetId: String(firmId),
    });
    revalidatePath('/admin/firms');
    return { ok: true, result };
  } catch (e) {
    return { ok: false, error: e instanceof Error ? e.message : String(e) };
  }
}

/** Update a firm's business details (HQ region, Discord URL). */
export async function updateFirmDetailsAction(
  firmId: number,
  body: { discordUrl: string | null; hqRegion: string | null },
): Promise<{ ok: boolean; error?: string; result?: FirmResult }> {
  const viewer = await getViewer();
  requireAdmin(viewer);
  try {
    const result = await updateFirmDetails(firmId, body);
    await auditView(viewer, {
      method: 'POST',
      path: '/admin/firms/details',
      targetType: 'firm',
      targetId: String(firmId),
    });
    revalidatePath('/admin/firms');
    return { ok: true, result };
  } catch (e) {
    return { ok: false, error: e instanceof Error ? e.message : String(e) };
  }
}
