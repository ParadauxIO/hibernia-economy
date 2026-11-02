'use server';

import { z } from 'zod';
import { revalidatePath } from 'next/cache';
import { requireLinkedViewer } from '@/lib/auth/linked';
import { requirePermission } from '@/lib/auth/access';
import { auditMutation } from '@/lib/audit';
import { changeFirmProprietor, releaseFirmName, setFirmTaxExempt, syncFirmAccounts, type FirmOperationResult, disbandFirm, renameFirm, updateFirmDetails, type DisbandResult, type FirmResult } from '@/lib/treasury';
import { getFirmOperationState, findFirmById, getFirmHeader, type FirmReportHeader } from '@/lib/sql/firm';

/** The firm fields admin tools change, as recorded before and after. */
function firmSnapshot(f: FirmReportHeader | null) {
  return f && {
    displayName: f.display_name,
    discordUrl: f.discord_url,
    hqRegion: f.hq_region,
    archived: f.archived === 1,
    proprietorUuid: f.proprietor_uuid,
    totalBalance: f.total_balance,
  };
}

/**
 * Disband a firm via the ledger-authoritative treasury-rest-api admin endpoint.
 * Requires the admin to retype the firm's exact name (type-to-confirm) before the
 * destructive action runs. Records an audit row with the per-account sweep result.
 */
export async function disbandFirmAction(
  firmId: number,
  confirmName: string,
): Promise<{ ok: boolean; error?: string; result?: DisbandResult }> {
  const viewer = await requireLinkedViewer();
  requirePermission(viewer, 'firm.disband');
  try {
    const firm = await findFirmById(firmId);
    if (!firm) return { ok: false, error: 'Firm not found.' };
    if (firm.archived) return { ok: false, error: 'Firm is already disbanded.' };
    if (confirmName.trim() !== firm.display_name) {
      return { ok: false, error: 'Confirmation text must match the firm name exactly.' };
    }
    const before = firmSnapshot(await getFirmHeader(firmId));
    const result = await disbandFirm(firmId);
    await auditMutation(viewer, {
      action: 'firm.disband', path: '/admin/firms/disband', targetType: 'firm', targetId: firmId,
      before, after: { ...firmSnapshot(await getFirmHeader(firmId)), sweep: result.accounts },
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
  const viewer = await requireLinkedViewer();
  requirePermission(viewer, 'firm.rename');
  try {
    const name = newName.trim();
    if (!name) return { ok: false, error: 'A new name is required.' };
    const before = firmSnapshot(await getFirmHeader(firmId));
    const result = await renameFirm(firmId, name);
    await auditMutation(viewer, {
      action: 'firm.rename', path: '/admin/firms/rename', targetType: 'firm', targetId: firmId,
      before: before && { displayName: before.displayName }, after: { displayName: result.displayName },
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
  const viewer = await requireLinkedViewer();
  requirePermission(viewer, 'firm.details.update');
  try {
    const before = firmSnapshot(await getFirmHeader(firmId));
    const result = await updateFirmDetails(firmId, body);
    const after = firmSnapshot(await getFirmHeader(firmId));
    await auditMutation(viewer, {
      action: 'firm.details.update', path: '/admin/firms/details', targetType: 'firm', targetId: firmId,
      before: before && { discordUrl: before.discordUrl, hqRegion: before.hqRegion },
      after: after && { discordUrl: after.discordUrl, hqRegion: after.hqRegion },
    });
    revalidatePath('/admin/firms');
    return { ok: true, result };
  } catch (e) {
    return { ok: false, error: e instanceof Error ? e.message : String(e) };
  }
}

const firmOperationSchema = z.discriminatedUnion('operation', [
  z.object({ operation: z.literal('proprietor'), owner: z.string().trim().min(1), confirmName: z.string() }),
  z.object({ operation: z.literal('release-name'), confirmName: z.string() }),
  z.object({ operation: z.literal('tax-exemption'), exempt: z.boolean() }),
  z.object({ operation: z.literal('sync-accounts') }),
]);
export async function operateFirmAction(firmId: number, raw: unknown): Promise<{ ok: boolean; error?: string; result?: FirmOperationResult }> {
  const viewer = await requireLinkedViewer();
  const permissions = {
    proprietor: 'firm.owner.change', 'release-name': 'firm.name.release',
    'tax-exemption': 'firm.tax.update', 'sync-accounts': 'firm.accounts.sync',
  } as const;
  const parsed = firmOperationSchema.safeParse(raw);
  if (!parsed.success || !Number.isSafeInteger(firmId) || firmId <= 0) return { ok: false, error: 'Invalid operation.' };
  const input = parsed.data;
  requirePermission(viewer, permissions[input.operation]);
  try {
    const firm = await getFirmHeader(firmId);
    if (!firm) return { ok: false, error: 'Firm not found.' };
    if ((input.operation === 'proprietor' || input.operation === 'release-name') && input.confirmName.trim() !== firm.display_name) {
      return { ok: false, error: 'Retype the firm name exactly.' };
    }
    const before = { ...firmSnapshot(firm), ...await getFirmOperationState(firmId) };
    let result: FirmOperationResult;
    switch (input.operation) {
      case 'proprietor': result = await changeFirmProprietor(firmId, input.owner.trim()); break;
      case 'release-name': result = await releaseFirmName(firmId); break;
      case 'tax-exemption': result = await setFirmTaxExempt(firmId, input.exempt); break;
      case 'sync-accounts': result = await syncFirmAccounts(firmId); break;
    }
    await auditMutation(viewer, {
      action: permissions[input.operation], path: `/admin/firms/${firmId}`, targetType: 'firm', targetId: firmId,
      before, after: { ...firmSnapshot(await getFirmHeader(firmId)), ...await getFirmOperationState(firmId), result },
    });
    revalidatePath('/admin/firms');
    revalidatePath(`/admin/firms/${firmId}`);
    revalidatePath('/admin/accounts');
    return { ok: true, result };
  } catch (e) { return { ok: false, error: e instanceof Error ? e.message : String(e) }; }
}
