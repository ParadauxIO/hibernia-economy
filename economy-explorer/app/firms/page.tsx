import Link from 'next/link';
import { Money, PageHeader } from '@democracycraft/design-system';
import { DataTable, TableEmpty, TablePager, columnOptions, type Column, type LinkComponent } from '@democracycraft/design-system/table';
import { DateWithYear } from '@/components/entity/bits';
import { FilterBar } from '@/components/FilterBar';
import { CURRENCY_SYMBOL } from '@/lib/currency';
import { firmHref, playerName } from '@/lib/entityLinks';
import { buildMetadata } from '@/lib/metadata';
import { getFirmsDirectory } from '@/lib/services/firm';
import type { FirmDirectoryRow } from '@/lib/sql/firm';
import { firmsTable } from '@/lib/tables/firms';
import { cleared, filterText, parseTableState, tableHref, withPage, withQuery, withSort, withoutFilter, type TableState } from '@/lib/tables/core';

export const dynamic = 'force-dynamic';

export async function generateMetadata() {
  return buildMetadata({ title: 'Firms', description: 'Registered businesses on {server}, with their accounts, staff and headquarters.', path: '/firms' });
}

const TABLE_ID = 'firms-table';
const RouterLink: LinkComponent = Link;

const columns: Column<FirmDirectoryRow>[] = [
  {
    key: 'name', header: 'Firm', sort: 'name',
    cell: (f) => <>
      <span>{f.display_name}</span>
      {f.proprietor_uuid && <span className="dt-sub">{` · ${playerName(f.proprietor_name, f.proprietor_uuid)}`}</span>}
      {f.archived ? <span className="dt-sub"> · disbanded</span> : null}
    </>,
  },
  { key: 'hq', header: 'HQ', secondary: true, optional: true, cell: (f) => f.hq_region ?? <span className="muted">—</span> },
  { key: 'staff', header: 'Staff', sort: 'staff', numeric: true, secondary: true, cell: (f) => f.staff_count.toLocaleString('en-US') },
  { key: 'accounts', header: 'Accounts', numeric: true, secondary: true, optional: true, cell: (f) => f.account_count.toLocaleString('en-US') },
  { key: 'balance', header: 'Balance', sort: 'balance', numeric: true, cell: (f) => <Money value={f.total_balance} /> },
  { key: 'created', header: 'Founded', sort: 'created', sortFirst: 'desc', secondary: true, optional: true, cell: (f) => <DateWithYear value={f.created_at} /> },
];

/** Spec §5.5: the firm directory. A firm's balance is the total of its own accounts. */
export default async function FirmsPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const state = parseTableState(await searchParams, firmsTable);
  const { rows, total } = await getFirmsDirectory(state);
  const totalPages = Math.max(1, Math.ceil(total / state.limit));
  const href = (s: TableState<typeof firmsTable>) => tableHref('/firms', s, firmsTable);
  const describe = { currency: CURRENCY_SYMBOL };
  const activeFilters = [
    ...(state.q ? [{ text: `“${state.q}”`, removeHref: href(withQuery(state, '')) }] : []),
    ...state.filters.map((f) => ({ text: filterText(f, firmsTable, describe), removeHref: href(withoutFilter(state, f.field)) })),
  ];

  return <>
    <PageHeader title="Firms" subtitle="Businesses registered on the server. A firm’s balance is its own money, held across its accounts." />
    <FilterBar table={firmsTable} total={total} columns={columnOptions(columns)} tableId={TABLE_ID} currency={CURRENCY_SYMBOL} />
    <DataTable id={TABLE_ID} label="Firms" columns={columns} rows={rows} rowKey={(f) => f.firm_id} rowHref={(f) => firmHref(f.display_name)}
      sort={state.sort} sortHref={(field, dir) => href(withSort(state, field, dir, firmsTable))} link={RouterLink}
      empty={<TableEmpty noun="firms" filters={activeFilters} clearHref={href(cleared(state))} link={RouterLink} />} />
    <TablePager page={Math.min(state.page, totalPages)} totalPages={totalPages} hrefFor={(p) => href(withPage(state, p))} link={RouterLink} />
  </>;
}
