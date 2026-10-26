import 'server-only';
import { cache } from 'react';
import type { Viewer } from './viewer';
import type { Capability, Role } from './capabilities';
import { hasPermission, hasAnyPermission, type Permission } from './policy';
import { ForbiddenError, UnauthorizedError } from '@/lib/errors';
import * as firmSql from '@/lib/sql/firm';
import * as ledgerSql from '@/lib/sql/ledger';
export { hasPermission } from './policy';

// Request-scoped dedupe (React cache(), spec §4.3). One render often asks the
// same question twice: the account page loads the account and then
// canViewAccount loads it again; the firm page checks finance access for both
// internals and financials. Within a Server Component render these return the
// first answer; outside one (route handlers, server actions, tests) cache() is a
// pass-through, so nothing is ever reused across requests and a revoked
// relationship is seen on the next request. Pages should import these rather
// than the raw lib/sql functions.
export const findAccount = cache(ledgerSql.findAccount);
export const canReadAccount = cache(ledgerSql.canReadAccount);
export const findFirmByDisplayName = cache(firmSql.findFirmByDisplayName);
export const findFirmById = cache(firmSql.findFirmById);
export const isFirmMember = cache(firmSql.isFirmMember);
export const hasFirmFinancialAccess = cache(firmSql.hasFirmFinancialAccess);
const findTransactionAccountIds = cache(ledgerSql.findTransactionAccountIds);

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

/**
 * Batched canViewAccount: the subset of `accountIds` the viewer may read, in one
 * query rather than two per id. Same rule: RBAC (`account.read.all`, or
 * `account.read` with a linked identity) AND ReBAC (ownership or an
 * account_read_access_web relationship). Without `account.read.all`, ids that do
 * not exist are never returned; with it every id is, without a lookup, exactly as
 * canViewAccount answers true for any id.
 */
export async function readableAccountIds(v: Viewer, accountIds: readonly number[]): Promise<Set<number>> {
  if (accountIds.length === 0) return new Set();
  if (hasPermission(v, 'account.read.all')) return new Set(accountIds);
  if (!hasPermission(v, 'account.read') || !v.minecraftUuid) return new Set();
  return new Set(await ledgerSql.findReadableAccountIds(accountIds, v.minecraftUuid));
}

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
/** One readable participating account exposes this transaction, not other account histories. */
export async function canViewTransaction(txnId: number, v: Viewer): Promise<boolean> {
  if (hasPermission(v, 'transactions.read')) return true;
  if (!hasAnyPermission(v, ['account.read', 'account.read.all'])) return false;
  if (!hasPermission(v, 'account.read.all') && !v.minecraftUuid) return false;
  return (await readableAccountIds(v, await findTransactionAccountIds(txnId))).size > 0;
}
export async function canViewFirmInternals(firmId: number, v: Viewer): Promise<boolean> {
  if (hasPermission(v, 'firm.read.all')) return true;
  if (!hasPermission(v, 'firm.read')) return false;
  if (!v.minecraftUuid) return false;
  // Both in parallel: the firm page asks for financials next, and the cached
  // hasFirmFinancialAccess answer is reused there.
  const [member, finance] = await Promise.all([
    isFirmMember(firmId, v.minecraftUuid),
    hasFirmFinancialAccess(firmId, v.minecraftUuid),
  ]);
  return member || finance;
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
