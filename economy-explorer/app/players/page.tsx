import Link from 'next/link';
import { PageHeader, PlayerHead, When } from '@democracycraft/design-system';
import { DataTable, TableEmpty, TablePager, columnOptions, type Column, type LinkComponent } from '@democracycraft/design-system/table';
import { DateWithYear } from '@/components/entity/bits';
import { FilterBar } from '@/components/FilterBar';
import { CURRENCY_SYMBOL } from '@/lib/currency';
import { playerHref, playerName } from '@/lib/entityLinks';
import { buildMetadata } from '@/lib/metadata';
import { getPlayersDirectory } from '@/lib/services/players';
import type { DirectoryPlayerRow } from '@/lib/sql/players';
import { playersTable } from '@/lib/tables/players';
import { cleared, filterText, parseTableState, tableHref, withPage, withQuery, withSort, withoutFilter, type TableState } from '@/lib/tables/core';

export const dynamic = 'force-dynamic';

export async function generateMetadata() {
  return buildMetadata({ title: 'Players', description: 'Everyone who plays on {server}: when they joined, when they were last seen, and their firms.', path: '/players' });
}

const TABLE_ID = 'players-table';
const RouterLink: LinkComponent = Link;

const columns: Column<DirectoryPlayerRow>[] = [
  {
    key: 'name', header: 'Player', sort: 'name',
    cell: (p) => <span className="entity-link entity-player"><PlayerHead uuid={p.uuid} name={p.name} size={18} /><span className="entity-name">{playerName(p.name, p.uuid)}</span></span>,
  },
  { key: 'firstSeen', header: 'First seen', sort: 'firstSeen', sortFirst: 'desc', secondary: true, cell: (p) => <DateWithYear value={p.first_seen} /> },
  { key: 'lastSeen', header: 'Last seen', sort: 'lastSeen', sortFirst: 'desc', cell: (p) => <When value={p.last_seen} /> },
  { key: 'firms', header: 'Firms', numeric: true, secondary: true, optional: true, cell: (p) => p.firm_count > 0 ? p.firm_count.toLocaleString('en-US') : <span className="muted">—</span> },
];

/** Spec §7.2: a directory of people, by when they were around. No wealth ranking. */
export default async function PlayersPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const state = parseTableState(await searchParams, playersTable);
  const { rows, total } = await getPlayersDirectory(state);
  const totalPages = Math.max(1, Math.ceil(total / state.limit));
  const href = (s: TableState<typeof playersTable>) => tableHref('/players', s, playersTable);
  const activeFilters = [
    ...(state.q ? [{ text: `“${state.q}”`, removeHref: href(withQuery(state, '')) }] : []),
    ...state.filters.map((f) => ({ text: filterText(f, playersTable), removeHref: href(withoutFilter(state, f.field)) })),
  ];

  return <>
    <PageHeader title="Players" subtitle="Everyone with an account here. Search by name, or filter by when they joined and were last seen." />
    <FilterBar table={playersTable} total={total} columns={columnOptions(columns)} tableId={TABLE_ID} currency={CURRENCY_SYMBOL} />
    <DataTable id={TABLE_ID} label="Players" columns={columns} rows={rows} rowKey={(p) => p.uuid}
      rowHref={(p) => playerHref(p.name, p.uuid)} sort={state.sort} sortHref={(field, dir) => href(withSort(state, field, dir, playersTable))} link={RouterLink}
      empty={<TableEmpty noun="players" filters={activeFilters} clearHref={href(cleared(state))} link={RouterLink} />} />
    <TablePager page={Math.min(state.page, totalPages)} totalPages={totalPages} hrefFor={(p) => href(withPage(state, p))} link={RouterLink} />
  </>;
}
