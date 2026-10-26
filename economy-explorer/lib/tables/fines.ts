import { defineTable, type TableState } from './core';

// Fines in /gov (spec §10.3). Reasons are free text written by players, so
// there are no categories: the reason is searched, everything else is a chip.
// Every filter is bounded by the page's range on idx_fines_issued_at.

export const FINE_STATUSES = [
  { value: 'active', label: 'Active' },
  { value: 'revoked', label: 'Revoked' },
] as const;

export const finesTable = defineTable({
  id: 'gov-fines',
  search: ['reason'],
  searchPlaceholder: 'Search fine reasons',
  filters: {
    status: { label: 'Status', kind: 'enum', options: FINE_STATUSES },
    amount: { label: 'Amount', kind: 'money' },
    issued: { label: 'Issued', kind: 'date', hint: 'Within the selected range' },
    issuer: { label: 'Issued by', kind: 'entity', entity: 'player', hint: 'The official who issued the fine' },
    player: { label: 'Fined player', kind: 'entity', entity: 'player' },
    account: { label: 'Receiving account', kind: 'entity', entity: 'account', hint: 'A government account in your scope' },
  },
  sorts: { issued: 'Issued', amount: 'Amount' },
  defaultSort: '-issued',
  quick: [
    { label: 'Active', f: ['status:in:active'] },
    { label: 'This week', f: ['issued:gte:-7d'] },
    { label: 'Revoked', f: ['status:in:revoked'] },
    { label: 'Over 10k', f: ['amount:gt:10000'] },
  ],
  noun: { one: 'fine', many: 'fines' },
  pageSize: 50,
  maxPageSize: 200,
  legacy: { filterParams: { status: 'status' } },
});

export type FinesTable = typeof finesTable;
export type FinesTableState = TableState<FinesTable>;
