import { defineTable, type TableState } from './core';

/**
 * The shop finder (`/chestshop/shops`): live shop signs as last observed.
 * Direction picks which side of the sign is compared: Buy (you pay the shop)
 * or Sell (the shop pays you). Prices are per item (signs quote a batch).
 */
export const shopsTable = defineTable({
  id: 'shops',
  search: ['itemName', 'material'],
  searchPlaceholder: 'Search items or materials',
  filters: {
    item: { label: 'Item', kind: 'entity', entity: 'item' },
    direction: { label: 'I want to', kind: 'enum', options: [{ value: 'buy', label: 'Buy' }, { value: 'sell', label: 'Sell' }], hint: 'Buy from shops, or sell to them' },
    price: { label: 'Price per item', kind: 'money' },
    inStock: { label: 'In stock', kind: 'boolean', hint: 'At least one batch observed' },
    owner: { label: 'Shop type', kind: 'enum', options: [{ value: 'player', label: 'Player' }, { value: 'firm', label: 'Firm' }, { value: 'admin', label: 'Admin' }] },
    world: { label: 'World', kind: 'text' },
  },
  sorts: { price: 'Price per item', stock: 'Stock', seen: 'Last seen' },
  defaultSort: 'price',
  quick: [
    { label: 'In stock', f: ['inStock:eq:true'] },
    { label: 'Sell to shops', f: ['direction:in:sell'] },
    { label: 'Firm shops', f: ['owner:in:firm'] },
  ],
  noun: { one: 'shop', many: 'shops' },
  pageSize: 25,
  maxPageSize: 100,
  legacy: { filterParams: { item: 'item', direction: 'direction' } },
});

export type ShopsTable = typeof shopsTable;
export type ShopsTableState = TableState<ShopsTable>;
