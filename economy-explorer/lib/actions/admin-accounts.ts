'use server';

import { revalidatePath } from 'next/cache';
import { getViewer, type Viewer } from '@/lib/auth/viewer';
import { requireAdmin } from '@/lib/auth/requireAdmin';
import { auditView } from '@/lib/audit';
import {
  adminTransfer, renameAccount, changeAccountOwner, archiveAccount, unarchiveAccount,
  type TransferResult, type AccountResult,
} from '@/lib/treasury';

type Result<T> = { ok: boolean; error?: string; result?: T };

async function run<T>(
  audit: { path: string; targetId: string | null },
  op: (viewer: Extract<Viewer, { anon: false }>) => Promise<T>,
): Promise<Result<T>> {
  const viewer = await getViewer();
  requireAdmin(viewer);
  try {
    const result = await op(viewer);
    await auditView(viewer, { method: 'POST', path: audit.path, targetType: 'account', targetId: audit.targetId });
    revalidatePath('/admin/accounts');
    return { ok: true, result };
  } catch (e) {
    return { ok: false, error: e instanceof Error ? e.message : String(e) };
  }
}

/** Move money between two accounts with a memo (admin). */
export async function adminTransferAction(args: {
  fromAccountId: number; toAccountId: number; amount: string; memo: string;
}): Promise<Result<TransferResult>> {
  return run<TransferResult>(
    { path: '/admin/accounts/transfer', targetId: `${args.fromAccountId}->${args.toAccountId}` },
    () => adminTransfer(args),
  );
}

export async function renameAccountAction(accountId: number, displayName: string): Promise<Result<AccountResult>> {
  return run<AccountResult>({ path: '/admin/accounts/rename', targetId: String(accountId) },
    () => renameAccount(accountId, displayName.trim()));
}

export async function changeAccountOwnerAction(accountId: number, owner: string): Promise<Result<AccountResult>> {
  return run<AccountResult>({ path: '/admin/accounts/owner', targetId: String(accountId) },
    () => changeAccountOwner(accountId, owner.trim()));
}

export async function archiveAccountAction(accountId: number): Promise<Result<AccountResult>> {
  return run<AccountResult>({ path: '/admin/accounts/archive', targetId: String(accountId) },
    () => archiveAccount(accountId));
}

export async function unarchiveAccountAction(accountId: number): Promise<Result<AccountResult>> {
  return run<AccountResult>({ path: '/admin/accounts/unarchive', targetId: String(accountId) },
    () => unarchiveAccount(accountId));
}
