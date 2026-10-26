import 'server-only';
import { meGate } from '@/lib/services/me';
import { exportMyTransactionsBatch } from '@/lib/services/meTransactions';
import type { FeedRow } from '@/lib/services/meFlows';
import { meTransactionsTable, type MeTransactionsTable } from '@/lib/tables/me';
import { defineExport } from '../registry';

// /me/transactions: the player's own history across the accounts they can
// read, with the page's filters. Authorized by the same gate as the page; rows
// are scoped to the viewer's own relationships inside the service.

defineExport<MeTransactionsTable, FeedRow, string>({
  id: 'me-transactions',
  title: 'My transactions',
  table: meTransactionsTable,
  authorize: (viewer) => meGate(viewer).ok,
  rows: (state, cursor, { limit, viewer }) => {
    const gate = meGate(viewer);
    if (!gate.ok) return Promise.resolve({ rows: [], next: null });
    return exportMyTransactionsBatch(viewer, gate.uuid, state, cursor, limit);
  },
  columns: [
    { key: 'txn_id', header: 'Transaction', value: (r) => r.txnId },
    { key: 'settled_at', header: 'When (UTC)', value: (r) => r.at.toISOString() },
    { key: 'account', header: 'Account', value: (r) => r.account.name },
    { key: 'account_id', header: 'Account ID', value: (r) => r.account.accountId },
    { key: 'description', header: 'Description', value: (r) => r.description },
    { key: 'category', header: 'Category', value: (r) => r.categoryLabel },
    { key: 'counterparty', header: 'Counterparty', value: (r) => r.counterparty?.name ?? null },
    { key: 'counterparty_id', header: 'Counterparty ID', value: (r) => r.counterparty?.accountId ?? null },
    { key: 'amount', header: 'Amount', value: (r) => r.amount },
  ],
});
