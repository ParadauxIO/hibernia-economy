import { expect, it } from 'vitest';
import { accountsTable } from '@/lib/tables/accounts';
import { firmsTable } from '@/lib/tables/firms';
import { parseTableState } from '@/lib/tables/core';
import { publicDirectoryTable } from '@/lib/tables/publicDirectory';

it.each([accountsTable, firmsTable])('removes balance controls and ignores crafted balance queries for $id', table => {
  const publicTable = publicDirectoryTable(table);
  expect(publicTable.filters).not.toHaveProperty('balance');
  expect(publicTable.sorts).not.toHaveProperty('balance');
  expect(publicTable.quick.some(q => q.f.some(f => f.startsWith('balance:')))).toBe(false);
  for (const sort of ['balance', '-balance']) {
    const state = parseTableState({ sort, f: 'balance:gt:10000' }, publicTable);
    expect(state.sort).toEqual({ field: 'name', dir: 'asc' });
    expect(state.filters).toEqual([]);
  }
  expect(table.filters).toHaveProperty('balance');
  expect(table.sorts).toHaveProperty('balance');
});
