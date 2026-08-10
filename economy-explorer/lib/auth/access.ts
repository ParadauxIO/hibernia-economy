import 'server-only';
import type { Viewer } from '@/lib/auth/viewer';
import type { Capability } from '@/lib/auth/capabilities';
import { hasFirmFinancialAccess } from '@/lib/sql/firm';

/** Does the viewer hold a given capability (via any group or legacy role)? */
export function hasCapability(v: Viewer, cap: Capability): boolean {
  return !v.anon && v.capabilities.includes(cap);
}

/**
 * Financial-privacy model (see wiki / memory): balances are public, but
 * per-entity money & quantity drilldown (sales, volume, profitability,
 * who-paid-whom) is not. Aggregates stay public — market/item totals and
 * *who* the top sellers are — just not the figures attributed to each seller.
 */

/**
 * Global oversight roles. Staff see every per-entity financial figure,
 * including the cross-firm leaderboards where no single firm employee could
 * legitimately have access to every row.
 */
export function isStaff(v: Viewer): boolean {
  return hasCapability(v, 'viewer');
}

/**
 * Can this viewer see a single firm's financial drilldown (its ChestShop
 * sales / volume / quantity)? True for staff, or a current employee of that
 * firm whose role grants the FINANCIAL/ADMIN permission.
 */
export async function canViewFirmFinancials(firmId: number, v: Viewer): Promise<boolean> {
  if (isStaff(v)) return true;
  if (v.anon || !v.minecraftUuid) return false;
  return hasFirmFinancialAccess(firmId, v.minecraftUuid);
}
