import { defineTable, type TableState } from './core';

/**
 * The firms directory (`/firms`, spec §5.5). Balance is the total of the
 * firm's own accounts: the firm's money, shown as the firm's, never its
 * proprietor's (§3 rule 5).
 */
export const firmsTable = defineTable({
  id: 'firms',
  search: ['name', 'hq'],
  searchPlaceholder: 'Search firms or HQ regions',
  filters: {
    balance: { label: 'Balance', kind: 'money', hint: 'Total across the firm’s accounts' },
    staff: { label: 'Staff', kind: 'number', integer: true },
    hq: { label: 'HQ', kind: 'text' },
    created: { label: 'Founded', kind: 'date' },
    archived: { label: 'Disbanded', kind: 'boolean', hint: 'Disbanded firms are hidden unless you ask for them' },
  },
  sorts: { name: 'Name', balance: 'Balance', staff: 'Staff', created: 'Founded' },
  defaultSort: '-balance',
  quick: [
    { label: 'Over 10 staff', f: ['staff:gt:10'] },
    { label: 'Balance over 100k', f: ['balance:gt:100000'] },
    { label: 'Founded in the last 30 days', f: ['created:gte:-30d'] },
  ],
  noun: { one: 'firm', many: 'firms' },
  pageSize: 50,
  maxPageSize: 200,
  legacy: { sortParams: { name: 'name', balance: 'balance', employees: 'staff', created: 'created' } },
});

export type FirmsTable = typeof firmsTable;
export type FirmsTableState = TableState<FirmsTable>;
