import 'server-only';
import { cache } from 'react';
import { getViewer, hasPermission } from '@/lib/auth';
import type { Viewer } from '@/lib/auth/viewer';
import { auditView } from '@/lib/audit';
import { getGovernmentAccounts, type GovAccountRow } from '@/lib/sql/government';
import { listGovUnitAccounts, listGovUnits, type GovUnitRow } from '@/lib/sql/govUnits';
import { selectUnit, unassignedIds, unitOfAccount, visibleUnits, type Selection, type UnitView } from './governmentModel';

// The one place /gov decides what a viewer may see (spec §10.1). Every /gov
// page, export route and server action starts here, and every number it shows
// is computed over `selection.ids`, which is always a subset of `scope.ids`.
//
//   RBAC  government.read opens the workspace; government.read.all puts every
//         active GOVERNMENT account in scope.
//   ReBAC otherwise the scope is the GOVERNMENT accounts the viewer owns or
//         can read through account_read_access_web (governmentRelationship in
//         lib/sql/government.ts, the same rule the top bar uses).
//
// Units (V44) only group what is already in scope; they never add an account.

export type GovViewer = Extract<Viewer, { anon: false }>;

export interface GovScope {
  viewer: GovViewer;
  /** government.read.all: every GOVERNMENT account, no relationship needed. */
  all: boolean;
  accounts: GovAccountRow[];
  ids: number[];
  units: UnitView[];
  unassigned: number[];
  /** Unit of each in-scope account (its own unit, not an ancestor). */
  unitOf: Map<number, GovUnitRow>;
  canAudit: boolean;
  canManageUnits: boolean;
}

export type GovAccess =
  | { ok: true; scope: GovScope }
  | { ok: false; reason: 'login' | 'permission' | 'unlinked' };

/** Request-scoped: the layout and the page share one resolution. */
export const resolveGovScope = cache(async (): Promise<GovAccess> => {
  const viewer = await getViewer();
  if (viewer.anon) return { ok: false, reason: 'login' };
  const all = hasPermission(viewer, 'government.read.all');
  if (!all && !hasPermission(viewer, 'government.read')) return { ok: false, reason: 'permission' };
  if (!all && !viewer.minecraftUuid) return { ok: false, reason: 'unlinked' };
  const [accounts, units, memberships] = await Promise.all([
    getGovernmentAccounts(all ? { all: true } : { playerUuid: viewer.minecraftUuid! }),
    listGovUnits(),
    listGovUnitAccounts(),
  ]);
  const ids = accounts.map((a) => a.account_id);
  const inScope = new Set(ids);
  const own = unitOfAccount(units, memberships);
  const unitOf = new Map<number, GovUnitRow>();
  for (const id of ids) {
    const u = own.get(id);
    if (u) unitOf.set(id, u as GovUnitRow);
  }
  return {
    ok: true,
    scope: {
      viewer,
      all,
      accounts,
      ids,
      units: visibleUnits(units, memberships, inScope),
      unassigned: unassignedIds(ids, memberships),
      unitOf,
      canAudit: hasPermission(viewer, 'government.audit.read'),
      canManageUnits: hasPermission(viewer, 'gov_units.manage'),
    },
  };
});

/** The scope, or null when the viewer may not open /gov. */
export async function govScopeOrNull(): Promise<GovScope | null> {
  const access = await resolveGovScope();
  return access.ok ? access.scope : null;
}

/** The accounts a page computes over for `?unit=`. Always within scope. */
export function govSelection(scope: GovScope, unitParam: string | string[] | undefined): Selection {
  const param = Array.isArray(unitParam) ? unitParam[0] : unitParam;
  return selectUnit(param, scope.ids, scope.units, scope.unassigned);
}

/** Unit key of an account for per-unit breakdowns: its unit's slug or `unassigned`. */
export function unitKeyOf(scope: GovScope): (accountId: number) => string {
  return (id) => scope.unitOf.get(id)?.slug ?? 'unassigned';
}

/** Restricts caller-supplied account ids (a chip, a form field) to the scope. */
export function withinScope(scope: GovScope, accountIds: readonly number[]): number[] {
  const inScope = new Set(scope.ids);
  return accountIds.filter((id) => inScope.has(id));
}

/**
 * Records a /gov read in explorer_audit (spec §10.1, closing the §1.6 gap).
 * A relationship-scoped viewer gets one `account` row per account the page
 * read, so the Audit tab (and /admin/audit) can show who looked at which
 * treasury. A government.read.all viewer gets one `global` row for the page.
 * Fail-open, like every audit write.
 *
 * TODO(PAR-542): switch the relationship rows to ADMIN's
 * auditRelationshipRead(viewer, target) once it lands in lib/audit.ts.
 */
export async function auditGovRead(scope: GovScope, path: string, accountIds: readonly number[], method = 'GET'): Promise<void> {
  if (scope.all) {
    await auditView(scope.viewer, { path, targetType: 'global', method });
    return;
  }
  // Bounded by the scope, which for a relationship viewer is a handful of accounts.
  await Promise.all(withinScope(scope, accountIds).map((id) => auditView(scope.viewer, { path, targetType: 'account', targetId: String(id), method })));
}
