import 'server-only';
import { hasPermission } from '@/lib/auth/policy';
import type { KeysetCursor } from '@/lib/pagination/cursor';
import { exportTransactionsBatch, getTransactionsTable, type TxnListItem } from '@/lib/services/transactions';
import type { TransactionsTable } from '@/lib/tables/transactions';
import { defineExport } from '../registry';

// The admin ledger (/transactions): every filtered transaction, gated by
// transactions.read exactly like the page.

const sides = (list: TxnListItem['from']) => list.map((s) => `${s.label} #${s.account_id}`).join('; ');

defineExport<TransactionsTable, TxnListItem, KeysetCursor>({
  id: 'transactions',
  title: 'Transactions',
  table: getTransactionsTable,
  authorize: (viewer) => hasPermission(viewer, 'transactions.read'),
  rows: (state, cursor, { limit }) => exportTransactionsBatch(state, cursor, limit),
  columns: [
    { key: 'txn_id', header: 'Transaction', value: (t) => t.txn_id },
    { key: 'settled_at', header: 'Settled (UTC)', value: (t) => t.settlement_time.toISOString() },
    { key: 'amount', header: 'Amount', value: (t) => t.volume },
    { key: 'from', header: 'From', value: (t) => sides(t.from) },
    { key: 'to', header: 'To', value: (t) => sides(t.to) },
    { key: 'postings', header: 'Postings', value: (t) => t.posting_count },
    { key: 'plugin', header: 'Plugin', value: (t) => t.plugin_system },
    { key: 'message', header: 'Message', value: (t) => t.message },
    { key: 'initiator_name', header: 'Initiator', value: (t) => t.initiator_name },
    { key: 'initiator_uuid', header: 'Initiator UUID', value: (t) => t.initiator_uuid },
    { key: 'authorizer_uuid', header: 'Authorizer UUID', value: (t) => t.authorizer_uuid },
  ],
});
