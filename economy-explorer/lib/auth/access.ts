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
 * Does this viewer hold ANY global elevation that lets them read other people's
 * data — the `viewer` capability (which admin and legacy government imply) or
 * the admin/government tier itself? This is the audit predicate: every read a
 * page grants on the strength of elevation must be audited, and it has to cover
 * the read-only `viewer` role (PAR-265), whose `role` collapses to 'player' —
 * so a `role === 'admin' || role === 'government'` check silently skips it.
 */
export function hasElevatedRead(v: Viewer): boolean {
  if (v.anon) return false;
  return isStaff(v) || v.role === 'admin' || v.role === 'government';
}

/**
 * The role to record against an audited access. `viewer.role` is the legacy
 * three-tier enum, which reports a viewer-only user as 'player'; the audit log
 * should name the elevation that was actually used.
 */
export function auditRole(v: Extract<Viewer, { anon: false }>): 'admin' | 'government' | 'viewer' | 'player' {
  if (v.role === 'player' && v.capabilities.includes('viewer')) return 'viewer';
  return v.role;
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
