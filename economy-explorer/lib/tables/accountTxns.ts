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

export const accountTxnsTable = defineTable({
  id: 'account-txns',
  search: ['message', 'memo'],
  searchPlaceholder: 'Search messages and memos',
  filters: {
    category: { label: 'Category', kind: 'enum', options: TXN_CATEGORIES },
    direction: { label: 'Direction', kind: 'enum', options: [{ value: 'in', label: 'Money in' }, { value: 'out', label: 'Money out' }] },
    counterparty: { label: 'Counterparty', kind: 'entity', entity: 'account', hint: 'The other side of the transaction' },
    amount: { label: 'Amount', kind: 'money', hint: 'Size of the payment, in or out' },
    date: { label: 'Date', kind: 'date' },
    memo: { label: 'Message', kind: 'text', hint: 'Searches one date range at a time' },
  },
  sorts: { date: 'Date' },
  defaultSort: '-date',
  quick: [
    { label: 'Money in', f: ['direction:in:in'] },
    { label: 'Money out', f: ['direction:in:out'] },
    { label: 'Over 1k', f: ['amount:gt:1000'] },
    { label: 'Past 7 days', f: ['date:gte:-7d'] },
  ],
  pagination: 'cursor',
  pageSize: 25,
  maxPageSize: 100,
  noun: { one: 'transaction', many: 'transactions' },
});

export type AccountTxnsTable = typeof accountTxnsTable;
export type AccountTxnsState = TableState<AccountTxnsTable>;

/** Free-text search and the Message filter scan at most this many days when no Date filter is set. */
export const TEXT_SEARCH_DEFAULT_DAYS = 30;

/** Transactions examined per filtered page (lib/sql/accountHistory.ts). Bounds every filtered page's work. */
export const SCAN_WINDOW = 5_000;
