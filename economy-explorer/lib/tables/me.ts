import { CATEGORIES, CATEGORY_LABELS } from '@/lib/categories';
import { defineTable, type TableState } from './core';

/** Category chip options, in taxonomy order (lib/categories.ts). */
export const CATEGORY_OPTIONS = CATEGORIES.map((c) => ({ value: c, label: CATEGORY_LABELS[c] }));

/**
 * The player's own transaction history (`/me/transactions`, spec §8.2).
 *
 * Every filter is backed by the (account_id, txn_id, amount) postings index:
 * account, date (turned into a txn_id range), direction and amount are index
 * conditions. Category, counterparty and memo text are checked row by row, so
 * the service searches them one 31-day window at a time (spec §11: no request
 * scans more than 31 days of raw ledger) and "Older" continues the search.
 */
export const meTransactionsTable = defineTable({
  id: 'me-transactions',
  search: ['memo', 'message'],
  searchPlaceholder: 'Search memos and messages',
  filters: {
    account: { label: 'Account', kind: 'entity', entity: 'account', hint: 'Defaults to your personal accounts' },
    category: { label: 'Category', kind: 'enum', options: CATEGORY_OPTIONS },
    direction: { label: 'Direction', kind: 'enum', options: [{ value: 'in', label: 'Money in' }, { value: 'out', label: 'Money out' }] },
    counterparty: { label: 'Counterparty', kind: 'entity', entity: 'account', hint: 'The other account in the transaction' },
    amount: { label: 'Amount', kind: 'money', hint: 'Size of the movement, in or out' },
    memo: { label: 'Memo', kind: 'text', hint: 'Text in the memo or message' },
    date: { label: 'Date', kind: 'date' },
  },
  sorts: { date: 'Date' },
  defaultSort: '-date',
  quick: [
    { label: 'Money in', f: ['direction:in:in'] },
    { label: 'Money out', f: ['direction:in:out'] },
    { label: 'Taxes', f: ['category:in:taxes'] },
    { label: 'Over 1k', f: ['amount:gt:1000'] },
    { label: 'Last 30 days', f: ['date:gte:-30d'] },
  ],
  pagination: 'cursor',
  pageSize: 50,
  maxPageSize: 100,
  noun: { one: 'transaction', many: 'transactions' },
});

export type MeTransactionsTable = typeof meTransactionsTable;
export type MeTransactionsState = TableState<MeTransactionsTable>;
