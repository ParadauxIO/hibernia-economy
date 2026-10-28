'use server';

import { revalidatePath } from 'next/cache';
import type { Viewer } from '@/lib/auth/viewer';
import { requireLinkedViewer } from '@/lib/auth/linked';
import { requirePermission } from '@/lib/auth/access';
import type { Permission } from '@/lib/auth/policy';
import { auditMutation } from '@/lib/audit';
import { findAccount, type ExplorerAccountRow } from '@/lib/sql/ledger';
import {
  adminTransfer, renameAccount, changeAccountOwner, archiveAccount, unarchiveAccount,
  type TransferResult, type AccountResult,
} from '@/lib/treasury';

type Result<T> = { ok: boolean; error?: string; result?: T };

/** The account fields admin tools change, as recorded before and after. */
function accountSnapshot(a: ExplorerAccountRow | null) {
  return a && { displayName: a.display_name, ownerUuid: a.owner_uuid, ownerName: a.owner_name, archived: a.archived === 1, balance: a.balance };
}

async function run<T>(
  permission: Permission,
  audit: { action: string; path: string; targetId: string; before: () => Promise<unknown>; after: (result: T) => Promise<unknown> },
  op: (viewer: Extract<Viewer, { anon: false }>) => Promise<T>,
): Promise<Result<T>> {
  const viewer = await requireLinkedViewer();
  requirePermission(viewer, permission);
  try {
    const before = await audit.before();
    const result = await op(viewer);
    await auditMutation(viewer, {
      action: audit.action, path: audit.path, targetType: 'account', targetId: audit.targetId,
      before, after: await audit.after(result),
    });
    revalidatePath('/admin/accounts');
    return { ok: true, result };
  } catch (e) {
    return { ok: false, error: e instanceof Error ? e.message : String(e) };
  }
}

/** Before/after for one account change: the account as read, then as read again. */
function accountAudit(action: string, path: string, accountId: number) {
  return {
    action, path, targetId: String(accountId),
    before: async () => accountSnapshot(await findAccount(accountId)),
    after: async () => accountSnapshot(await findAccount(accountId)),
  };
}

/** Move money between two accounts with a memo (admin). The acting player is recorded as the initiator. */
export async function adminTransferAction(args: {
  fromAccountId: number; toAccountId: number; amount: string; memo: string;
}): Promise<Result<TransferResult>> {
  const balances = async () => {
    const [from, to] = await Promise.all([findAccount(args.fromAccountId), findAccount(args.toAccountId)]);
    return { fromBalance: from?.balance ?? null, toBalance: to?.balance ?? null };
  };
  return run<TransferResult>('account.transfer',
    {
      action: 'account.transfer',
      path: '/admin/accounts/transfer',
      targetId: `${args.fromAccountId}->${args.toAccountId}`,
      before: balances,
      after: async (r) => ({ txnId: r.txnId, amount: r.amount, memo: r.memo, ...(await balances()) }),
    },
    () => adminTransfer(args),
  );
}

export async function renameAccountAction(accountId: number, displayName: string): Promise<Result<AccountResult>> {
  return run<AccountResult>('account.rename', accountAudit('account.rename', '/admin/accounts/rename', accountId),
    () => renameAccount(accountId, displayName.trim()));
}

export async function changeAccountOwnerAction(accountId: number, owner: string): Promise<Result<AccountResult>> {
  return run<AccountResult>('account.owner.change', accountAudit('account.owner.change', '/admin/accounts/owner', accountId),
    () => changeAccountOwner(accountId, owner.trim()));
}

export async function archiveAccountAction(accountId: number): Promise<Result<AccountResult>> {
  return run<AccountResult>('account.archive', accountAudit('account.archive', '/admin/accounts/archive', accountId),
    () => archiveAccount(accountId));
}

export async function unarchiveAccountAction(accountId: number): Promise<Result<AccountResult>> {
  return run<AccountResult>('account.archive', accountAudit('account.unarchive', '/admin/accounts/unarchive', accountId),
    () => unarchiveAccount(accountId));
}
