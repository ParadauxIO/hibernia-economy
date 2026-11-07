import 'server-only';
import { canViewAccount, findAccount } from '@/lib/auth/access';
import type { KeysetCursor } from '@/lib/pagination/cursor';
import { exportAccountBatch, getAccountContext, type TxnView } from '@/lib/services/account';
import { accountTxnsExportTable, type AccountTxnsExportState, type AccountTxnsState } from '@/lib/tables/accountTxns';
import { defineExport } from '../registry';

// One account's history (/accounts/[id]), with the page's own filters. The
// account travels as an `account` filter; access is the page's check
// (canViewAccount: RBAC and the account relationship).

/** The single account id an export state names, or null. */
export function exportAccountId(state: AccountTxnsExportState): number | null {
  const ids = state.filters.filter((f) => f.field === 'account').flatMap((f) => f.values);
  return ids.length === 1 && /^[1-9]\d{0,9}$/.test(ids[0]!) ? Number(ids[0]) : null;
}

/** The page's state without the account filter, as the history service takes it. */
export function historyState(state: AccountTxnsExportState): AccountTxnsState {
  return { ...state, filters: state.filters.filter((f) => f.field !== 'account') } as unknown as AccountTxnsState;
}

export const TXN_COLUMNS = [
  { key: 'txn_id', header: 'Transaction', value: (t: TxnView) => t.txnId },
  { key: 'settled_at', header: 'Settled (UTC)', value: (t: TxnView) => t.at.toISOString() },
  { key: 'amount', header: 'Amount', value: (t: TxnView) => t.amount },
  { key: 'balance_after', header: 'Balance after', value: (t: TxnView) => t.balanceAfter ?? '' },
  { key: 'category', header: 'Category', value: (t: TxnView) => t.categoryLabel },
  { key: 'counterparty_account_id', header: 'Counterparty account', value: (t: TxnView) => t.counterparty?.accountId ?? '' },
  { key: 'counterparty', header: 'Counterparty', value: (t: TxnView) => t.counterparty?.name ?? '' },
  { key: 'counterparty_type', header: 'Counterparty type', value: (t: TxnView) => t.counterparty?.accountType ?? '' },
  { key: 'message', header: 'Message', value: (t: TxnView) => t.message ?? '' },
  { key: 'memo', header: 'Memo', value: (t: TxnView) => t.memo ?? '' },
];

defineExport<typeof accountTxnsExportTable, TxnView, KeysetCursor>({
  id: 'account-transactions',
  title: 'Account transactions',
  table: accountTxnsExportTable,
  authorize: async (viewer, state) => {
    const id = exportAccountId(state);
    return id !== null && (await canViewAccount(id, viewer));
  },
  rows: async (state, cursor, { limit }) => {
    const id = exportAccountId(state);
    const account = id === null ? null : await findAccount(id);
    if (!account) return { rows: [], next: null };
    const context = await getAccountContext(account);
    return exportAccountBatch(account, context.firm?.firm_id ?? null, historyState(state), cursor, limit);
  },
  columns: TXN_COLUMNS,
});
