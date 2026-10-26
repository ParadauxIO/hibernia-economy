import 'server-only';
import { getViewer, hasPermission } from '@/lib/auth';
import type { Viewer } from '@/lib/auth/viewer';
import { auditView } from '@/lib/audit';
import {
  assignGovUnitAccount, deleteGovUnit, insertGovUnit, isActiveGovernmentAccount, listAssignableGovAccounts, listGovUnits,
  unassignGovUnitAccount, updateGovUnit, type AssignableAccountRow, type GovUnitInput, type GovUnitRow,
} from '@/lib/sql/govUnits';
import { validateUnitInput, type UnitFormInput } from './governmentModel';

// /admin/gov-units (spec §10.2): manage the presentation grouping of
// GOVERNMENT accounts. Gated by gov_units.manage; every change is audited.
// Units never grant access, so managing them needs no account relationship.

export type Result = { ok: true; id?: number } | { ok: false; error: string };

async function manager(): Promise<Extract<Viewer, { anon: false }> | null> {
  const viewer = await getViewer();
  return !viewer.anon && hasPermission(viewer, 'gov_units.manage') ? viewer : null;
}

export async function canManageGovUnits(): Promise<boolean> {
  return (await manager()) !== null;
}

export interface GovUnitsAdmin {
  units: (GovUnitRow & { accounts: number; parent_name: string | null })[];
  accounts: AssignableAccountRow[];
}

export async function getGovUnitsAdmin(): Promise<GovUnitsAdmin | null> {
  const viewer = await manager();
  if (!viewer) return null;
  const [units, accounts] = await Promise.all([listGovUnits(), listAssignableGovAccounts()]);
  const count = new Map<number, number>();
  for (const a of accounts) if (a.unit_id !== null) count.set(a.unit_id, (count.get(a.unit_id) ?? 0) + 1);
  const names = new Map(units.map((u) => [u.unit_id, u.name]));
  await auditView(viewer, { path: '/admin/gov-units', targetType: 'global' });
  return {
    units: units.map((u) => ({ ...u, accounts: count.get(u.unit_id) ?? 0, parent_name: u.parent_unit_id === null ? null : names.get(u.parent_unit_id) ?? null })),
    accounts,
  };
}

function duplicate(err: unknown): boolean {
  return typeof err === 'object' && err !== null && 'errno' in err && (err as { errno: number }).errno === 1062;
}

export async function saveGovUnit(unitId: number | null, form: UnitFormInput): Promise<Result> {
  const viewer = await manager();
  if (!viewer) return { ok: false, error: 'You need the gov_units.manage permission.' };
  const units = await listGovUnits();
  const checked = validateUnitInput(form, unitId, units);
  if (!checked.ok) return checked;
  const input: GovUnitInput = checked.value;
  try {
    let id = unitId;
    if (unitId === null) id = await insertGovUnit(input, viewer.minecraftUuid);
    else if (await updateGovUnit(unitId, input) === 0) return { ok: false, error: 'That unit no longer exists.' };
    await auditView(viewer, { path: '/admin/gov-units', targetType: 'global', targetId: `unit:${id}`, method: 'POST' });
    return { ok: true, ...(id === null ? {} : { id }) };
  } catch (err) {
    if (duplicate(err)) return { ok: false, error: `Another unit already uses the slug “${input.slug}”.` };
    throw err;
  }
}

export async function removeGovUnit(unitId: number): Promise<Result> {
  const viewer = await manager();
  if (!viewer) return { ok: false, error: 'You need the gov_units.manage permission.' };
  if (!Number.isSafeInteger(unitId) || await deleteGovUnit(unitId) === 0) return { ok: false, error: 'That unit no longer exists.' };
  await auditView(viewer, { path: '/admin/gov-units', targetType: 'global', targetId: `unit:${unitId}`, method: 'DELETE' });
  return { ok: true };
}

/** Puts an account in a unit (null: no unit). Only active GOVERNMENT accounts. */
export async function setAccountUnit(accountId: number, unitId: number | null): Promise<Result> {
  const viewer = await manager();
  if (!viewer) return { ok: false, error: 'You need the gov_units.manage permission.' };
  if (!Number.isSafeInteger(accountId) || !await isActiveGovernmentAccount(accountId)) return { ok: false, error: 'Only active government accounts can be put in a unit.' };
  if (unitId === null) {
    await unassignGovUnitAccount(accountId);
  } else {
    if (!(await listGovUnits()).some((u) => u.unit_id === unitId)) return { ok: false, error: 'That unit no longer exists.' };
    await assignGovUnitAccount(unitId, accountId);
  }
  await auditView(viewer, { path: '/admin/gov-units', targetType: 'account', targetId: String(accountId), method: 'POST' });
  return { ok: true };
}
