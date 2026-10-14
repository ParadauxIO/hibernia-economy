import 'server-only';
import type { Viewer } from './viewer';
import type { Capability, Role } from './capabilities';
import { hasPermission, hasAnyPermission, type Permission } from './policy';
import { ForbiddenError, UnauthorizedError } from '@/lib/errors';
import { hasFirmFinancialAccess, isFirmMember } from '@/lib/sql/firm';
import { findAccount, canReadAccount } from '@/lib/sql/ledger';
export { hasPermission } from './policy';

export function hasCapability(v: Viewer, cap: Capability): boolean {
  return !v.anon && v.capabilities.includes(cap);
}
export function requirePermission(v: Viewer, permission: Permission): asserts v is Extract<Viewer, { anon: false }> {
  if (v.anon) throw new UnauthorizedError();
  if (!hasPermission(v, permission)) throw new ForbiddenError();
}
export function hasElevatedRead(v: Viewer): boolean {
  return hasAnyPermission(v, ['player.read.all', 'account.read.all', 'firm.read.all', 'firm.financials.read.all', 'government.read.all', 'transactions.read', 'market.sales.read', 'market.figures.read']);
}
/** Audit predicate only. Never use this as a resource authorization gate. */
export const isStaff = hasElevatedRead;
export function auditRole(v: Extract<Viewer, { anon: false }>): Role { return v.role; }

/** RBAC AND ReBAC. A scoped account grant never grants access to the whole firm. */
export async function canViewAccount(accountId: number, v: Viewer): Promise<boolean> {
  if (hasPermission(v, 'account.read.all')) return true;
  if (!hasPermission(v, 'account.read')) return false;
  if (!v.minecraftUuid) return false;
  const account = await findAccount(accountId);
  if (!account) return false;
  if (account.owner_uuid === v.minecraftUuid) return true;
  return canReadAccount(accountId, v.minecraftUuid);
}
export async function requireAccountRead(accountId: number, v: Viewer): Promise<void> {
  if (v.anon) throw new UnauthorizedError();
  if (!await canViewAccount(accountId, v)) throw new ForbiddenError();
}
export async function canViewFirmInternals(firmId: number, v: Viewer): Promise<boolean> {
  if (hasPermission(v, 'firm.read.all')) return true;
  if (!hasPermission(v, 'firm.read')) return false;
  if (!v.minecraftUuid) return false;
  return await isFirmMember(firmId, v.minecraftUuid) || await hasFirmFinancialAccess(firmId, v.minecraftUuid);
}
export async function canViewFirmFinancials(firmId: number, v: Viewer): Promise<boolean> {
  if (hasPermission(v, 'firm.financials.read.all')) return true;
  if (!hasPermission(v, 'firm.financials.read')) return false;
  return !!v.minecraftUuid && hasFirmFinancialAccess(firmId, v.minecraftUuid);
}

/** Own-player data is never authorized by a client-supplied `self` flag. */
export function requirePlayerRead(uuid: string, v: Viewer): void {
  if (hasPermission(v, 'player.read.all')) return;
  requirePermission(v, 'player.read');
  if (v.minecraftUuid !== uuid) throw new ForbiddenError();
}
