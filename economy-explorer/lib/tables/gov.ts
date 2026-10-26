import { defineTable, type TableState } from './core';

// Tables of the /gov workspace and /admin/gov-units (spec §10, §5.5). Fines
// live in ./fines.ts. The account lists hold a few dozen rows (every
// GOVERNMENT account), so they are filtered in the service; the ledger and
// audit feeds are compiled to SQL in lib/sql/government.ts.

/** In-scope government accounts (/gov/accounts). */
export const govAccountsTable = defineTable({
  id: 'gov-accounts',
  search: ['displayName', 'accountId'],
  searchPlaceholder: 'Search accounts or #id',
  filters: {
    balance: { label: 'Balance', kind: 'money' },
    net: { label: '30-day net', kind: 'money', hint: 'Money in minus money out over the last 30 days' },
    assigned: { label: 'In a unit', kind: 'boolean' },
  },
  sorts: { name: 'Account', balance: 'Balance', net: '30-day net', unit: 'Unit' },
  defaultSort: '-balance',
  quick: [
    { label: 'Negative balance', f: ['balance:lt:0'] },
    { label: 'Losing money', f: ['net:lt:0'] },
    { label: 'Not in a unit', f: ['assigned:eq:false'] },
  ],
  noun: { one: 'account', many: 'accounts' },
  pageSize: 100,
  maxPageSize: 200,
});
export type GovAccountsState = TableState<typeof govAccountsTable>;

export const DIRECTIONS = [
  { value: 'in', label: 'Money in' },
  { value: 'out', label: 'Money out' },
] as const;

const LEDGER_FILTERS = {
  direction: { label: 'Direction', kind: 'enum', options: DIRECTIONS },
  amount: { label: 'Amount', kind: 'money', hint: 'Size of the movement, either direction' },
  account: { label: 'Account', kind: 'entity', entity: 'account', hint: 'A government account in your scope' },
  initiator: { label: 'Initiated by', kind: 'entity', entity: 'player' },
  authorized: { label: 'Has an authorizer', kind: 'boolean', hint: 'Approved by a second person' },
} as const;

/** Postings on in-scope accounts with who initiated and approved them (/gov/audit). */
export const govLedgerTable = defineTable({
  id: 'gov-ledger',
  search: [],
  searchPlaceholder: 'Filter transactions',
  filters: LEDGER_FILTERS,
  sorts: { time: 'Time' },
  defaultSort: '-time',
  quick: [
    { label: 'Money out', f: ['direction:in:out'] },
    { label: 'Over 100k', f: ['amount:gt:100000'] },
    { label: 'Authorized', f: ['authorized:eq:true'] },
  ],
  pagination: 'cursor',
  noun: { one: 'transaction', many: 'transactions' },
  pageSize: 50,
  maxPageSize: 200,
});
export type GovLedgerState = TableState<typeof govLedgerTable>;

/**
 * The `gov-transactions` export (lib/export/datasets/gov.ts): the ledger
 * table's filters plus `settled`, which carries the page's range into the
 * export link (the page takes it from the RangePicker instead of a chip).
 */
export const govLedgerExportTable = defineTable({
  id: 'gov-transactions',
  filters: { ...LEDGER_FILTERS, settled: { label: 'Settled', kind: 'date' } },
  sorts: { time: 'Time' },
  defaultSort: '-time',
  pagination: 'cursor',
  noun: { one: 'transaction', many: 'transactions' },
  pageSize: 50,
  maxPageSize: 200,
});
export type GovLedgerExportState = TableState<typeof govLedgerExportTable>;

/** Explorer views and exports of in-scope data (/gov/audit). */
export const govViewsTable = defineTable({
  id: 'gov-views',
  search: ['actorName', 'path'],
  searchPlaceholder: 'Search viewer or page',
  filters: {
    actor: { label: 'Viewer', kind: 'entity', entity: 'player' },
    account: { label: 'Account viewed', kind: 'entity', entity: 'account' },
  },
  sorts: { at: 'When' },
  defaultSort: '-at',
  pagination: 'cursor',
  noun: { one: 'view', many: 'views' },
  pageSize: 50,
  maxPageSize: 200,
});
export type GovViewsState = TableState<typeof govViewsTable>;

/** Every active GOVERNMENT account and its unit (/admin/gov-units). */
export const govUnitAccountsTable = defineTable({
  id: 'gov-unit-accounts',
  search: ['displayName', 'accountId'],
  searchPlaceholder: 'Search government accounts or #id',
  filters: {
    assigned: { label: 'In a unit', kind: 'boolean' },
    balance: { label: 'Balance', kind: 'money' },
  },
  sorts: { name: 'Account', balance: 'Balance', unit: 'Unit' },
  defaultSort: 'name',
  quick: [{ label: 'Not in a unit', f: ['assigned:eq:false'] }],
  noun: { one: 'account', many: 'accounts' },
  pageSize: 200,
  maxPageSize: 200,
});
export type GovUnitAccountsState = TableState<typeof govUnitAccountsTable>;
