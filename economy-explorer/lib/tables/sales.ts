import { defineTable, type TableState } from './core';

// The ChestShop sales feed (`/chestshop/sales`, `market.sales.read`) and its export.
//
// chestshop_sale indexes every entity as (column, occurred_at), so each entity
// filter drives the keyset (occurred_at, sale_id) straight off its own index:
//   item     → idx_cs_item        customer → idx_cs_customer
//   owner    → idx_cs_owner       firm     → idx_cs_firm
//   occurred → idx_cs_time
// Direction is roughly half of all sales, so it is a cheap residual check.
// Price and item-name text are per-row checks with no index on wide rows
// (item_data is MEDIUMTEXT), so they scan at most SALES_SCAN_WINDOW_DAYS.

export const SALES_SCAN_WINDOW_DAYS = 7;

export const salesTable = defineTable({
  id: 'sales',
  search: ['itemName'],
  searchPlaceholder: 'Search item names',
  filters: {
    occurred: { label: 'Date', kind: 'date' },
    item: { label: 'Item', kind: 'entity', entity: 'item' },
    customer: { label: 'Customer', kind: 'entity', entity: 'player', hint: 'The player who used the shop' },
    owner: { label: 'Shop owner', kind: 'entity', entity: 'player', hint: 'Owner of a player shop' },
    firm: { label: 'Firm', kind: 'entity', entity: 'firm', hint: 'Sales at a firm’s shops' },
    direction: {
      label: 'Direction',
      kind: 'enum',
      options: [
        { value: 'buy', label: 'Customer bought' },
        { value: 'sell', label: 'Customer sold' },
      ],
    },
    price: { label: 'Total price', kind: 'money', hint: `Searches up to ${SALES_SCAN_WINDOW_DAYS} days` },
  },
  sorts: { occurred: 'Date' },
  defaultSort: '-occurred',
  quick: [
    { label: 'Today', f: ['occurred:gte:-1d'] },
    { label: 'Customer bought', f: ['direction:in:buy'] },
    { label: 'Over 10k this week', f: ['price:gt:10000', 'occurred:gte:-7d'] },
  ],
  pagination: 'cursor',
  pageSize: 50,
  maxPageSize: 200,
  noun: { one: 'sale', many: 'sales' },
  // Old links: /chestshop/sales?customer=<uuid>.
  legacy: { filterParams: { customer: 'customer' } },
});

export type SalesTable = typeof salesTable;
export type SalesTableState = TableState<SalesTable>;
