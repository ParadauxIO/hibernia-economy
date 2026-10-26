import { CATEGORY_LABELS, type Category } from '@/lib/categories';
import { defineTable, type TableState } from './core';

// The account history table on /accounts/[id] (spec §7.1). An unbounded feed:
// cursor pagination, newest first, and only filters whose SQL stays bounded
// (lib/sql/ledger.ts: every page examines at most a fixed window of the
// account's postings, walked down the (account_id, txn_id) index).

/**
 * Categories the history can filter on. Internal is left out: it depends on the
 * owner's other accounts, which a single-account scan cannot see cheaply.
 */
export const TXN_CATEGORIES = (Object.keys(CATEGORY_LABELS) as Category[])
  .filter((c) => c !== 'internal')
  .map((c) => ({ value: c, label: CATEGORY_LABELS[c] }));

const TXN_FILTERS = {
  category: { label: 'Category', kind: 'enum', options: TXN_CATEGORIES },
  direction: { label: 'Direction', kind: 'enum', options: [{ value: 'in', label: 'Money in' }, { value: 'out', label: 'Money out' }] },
  counterparty: { label: 'Counterparty', kind: 'entity', entity: 'account', hint: 'The other side of the transaction' },
  amount: { label: 'Amount', kind: 'money', hint: 'Size of the payment, in or out' },
  date: { label: 'Date', kind: 'date' },
  memo: { label: 'Message', kind: 'text', hint: 'Searches one date range at a time' },
} as const;

const TXN_TABLE = {
  search: ['message', 'memo'],
  searchPlaceholder: 'Search messages and memos',
  sorts: { date: 'Date' },
  defaultSort: '-date',
  pagination: 'cursor',
  pageSize: 25,
  maxPageSize: 100,
  noun: { one: 'transaction', many: 'transactions' },
} as const;

export const accountTxnsTable = defineTable({
  ...TXN_TABLE,
  id: 'account-txns',
  filters: TXN_FILTERS,
  quick: [
    { label: 'Money in', f: ['direction:in:in'] },
    { label: 'Money out', f: ['direction:in:out'] },
    { label: 'Over 1k', f: ['amount:gt:1000'] },
    { label: 'Past 7 days', f: ['date:gte:-7d'] },
  ],
});

/**
 * The same history as an export dataset (lib/export/datasets): the page's
 * filters plus the account they apply to, so the export URL carries both.
 */
export const accountTxnsExportTable = defineTable({
  ...TXN_TABLE,
  id: 'account-txns-export',
  filters: { ...TXN_FILTERS, account: { label: 'Account', kind: 'entity', entity: 'account' } },
});

/** A firm's transactions across its accounts, for export: the history filters plus the firm. */
export const firmTxnsExportTable = defineTable({
  ...TXN_TABLE,
  id: 'firm-txns-export',
  filters: { ...TXN_FILTERS, firm: { label: 'Firm', kind: 'entity', entity: 'firm' } },
});

export type AccountTxnsTable = typeof accountTxnsTable;
export type AccountTxnsState = TableState<AccountTxnsTable>;
export type AccountTxnsExportState = TableState<typeof accountTxnsExportTable>;
export type FirmTxnsExportState = TableState<typeof firmTxnsExportTable>;

/** Free-text search and the Message filter scan at most this many days when no Date filter is set. */
export const TEXT_SEARCH_DEFAULT_DAYS = 30;

/** Transactions examined per filtered page (lib/sql/accountHistory.ts). Bounds every filtered page's work. */
export const SCAN_WINDOW = 5_000;
