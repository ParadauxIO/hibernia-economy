import { defineTable, type TableState } from './core';

/** The public player directory (`/players`, spec §7.2). Who is here, not how rich: no balances. */
export const playersTable = defineTable({
  id: 'players',
  search: ['name'],
  searchPlaceholder: 'Search players by name',
  filters: {
    firstSeen: { label: 'First seen', kind: 'date' },
    lastSeen: { label: 'Last seen', kind: 'date' },
    hasFirm: { label: 'Has a firm', kind: 'boolean', hint: 'Proprietor or employee of an active firm' },
  },
  sorts: { name: 'Name', firstSeen: 'First seen', lastSeen: 'Last seen' },
  defaultSort: '-lastSeen',
  quick: [
    { label: 'Seen this week', f: ['lastSeen:gte:-7d'] },
    { label: 'New this month', f: ['firstSeen:gte:-30d'] },
    { label: 'In a firm', f: ['hasFirm:eq:true'] },
  ],
  noun: { one: 'player', many: 'players' },
  pageSize: 50,
  maxPageSize: 200,
});

export type PlayersTable = typeof playersTable;
export type PlayersTableState = TableState<PlayersTable>;
