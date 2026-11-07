import { defineTable, type TableState } from './core';

/**
 * The item catalogue (`/chestshop`): every item traded in the window, from the
 * item rollup. Figures are for the window (30 days by default); prices are the
 * quantity-weighted average unit price of trades.
 */
export const itemsTable = defineTable({
  id: 'items',
  search: ['itemKey'],
  searchPlaceholder: 'Search items, e.g. diamond or oak log',
  filters: {
    trades: { label: 'Trades', kind: 'number', integer: true, hint: 'In the last 30 days' },
    volume: { label: 'Volume', kind: 'money', hint: 'Money traded in the last 30 days' },
    price: { label: 'Average price', kind: 'money', hint: 'Per item, last 30 days' },
  },
  sorts: { name: 'Item', trades: 'Trades', volume: 'Volume', price: 'Average price' },
  defaultSort: '-trades',
  quick: [
    { label: 'Over 100 trades', f: ['trades:gt:100'] },
    { label: 'Over 10k volume', f: ['volume:gt:10000'] },
    { label: 'Under 1 each', f: ['price:lt:1'] },
  ],
  noun: { one: 'item', many: 'items' },
  pageSize: 50,
  maxPageSize: 200,
});

export type ItemsTable = typeof itemsTable;
export type ItemsTableState = TableState<ItemsTable>;
