import type { AnyTable } from './core';

/** Remove balance queries as well as controls, so counts/order cannot reveal wealth. */
export function publicDirectoryTable<T extends AnyTable>(table: T): T {
  return {
    ...table,
    filters: Object.fromEntries(Object.entries(table.filters).filter(([key]) => key !== 'balance')),
    sorts: Object.fromEntries(Object.entries(table.sorts).filter(([key]) => key !== 'balance')),
    defaultSort: { field: 'name', dir: 'asc' },
    quick: table.quick.filter(quick => !quick.f.some(filter => filter.startsWith('balance:'))),
    legacy: {
      filterParams: Object.fromEntries(Object.entries(table.legacy.filterParams).filter(([, field]) => field !== 'balance')),
      sortParams: Object.fromEntries(Object.entries(table.legacy.sortParams).filter(([, field]) => field !== 'balance')),
    },
  };
}
