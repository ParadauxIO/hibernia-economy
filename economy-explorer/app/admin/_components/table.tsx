import type { AppHref } from '@/lib/routes';
import Link from 'next/link';
import { TableEmpty, type LinkComponent } from '@democracycraft/design-system/table';
import { cleared, filterText, tableHref, withQuery, withoutFilter, type AnyTable, type DescribeOptions, type TableState } from '@/lib/tables/core';

export const RouterLink: LinkComponent<AppHref> = Link;

/** The empty state for an admin chip table: which filters are active, and a way to drop each. */
export function AdminTableEmpty<T extends AnyTable>({ pathname, state, table, describe }: {
  pathname: AppHref;
  state: TableState<T>;
  table: T;
  describe?: DescribeOptions;
}) {
  const href = (s: TableState<T>) => tableHref(pathname, s as unknown as TableState, table);
  const filters = [
    ...(state.q ? [{ text: `“${state.q}”`, removeHref: href(withQuery(state, '')) }] : []),
    ...state.filters.map((f) => ({ text: filterText(f, table, describe), removeHref: href(withoutFilter(state, f.field)) })),
  ];
  return <TableEmpty noun={table.noun.many} filters={filters} clearHref={href(cleared(state))} link={RouterLink} />;
}
