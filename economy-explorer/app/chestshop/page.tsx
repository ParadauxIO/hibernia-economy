import type { AppHref } from '@/lib/routes';
import Link from 'next/link';
import { Money, PageHeader } from '@democracycraft/design-system';
import { DataTable, TableEmpty, TablePager, columnOptions, type Column, type LinkComponent } from '@democracycraft/design-system/table';
import { CoverageNote } from '@/components/entity/bits';
import { FilterBar } from '@/components/FilterBar';
import { ItemIcon } from '@/components/market/ItemIcon';
import { CURRENCY_SYMBOL } from '@/lib/currency';
import { itemHref } from '@/lib/entityLinks';
import { itemIconUrl } from '@/lib/itemIcon';
import { buildMetadata } from '@/lib/metadata';
import { getCatalogue, itemLabel, type CatalogueRow } from '@/lib/services/market';
import { priceToCents } from '@/lib/shopPrice';
import { itemsTable } from '@/lib/tables/items';
import { cleared, filterText, parseTableState, tableHref, withPage, withQuery, withSort, withoutFilter, type TableState } from '@/lib/tables/core';

export const dynamic = 'force-dynamic';

export async function generateMetadata() {
  return buildMetadata({ title: 'Items', description: 'Every item traded at shops on {server} in the last 30 days, with prices and volume.', path: '/chestshop' });
}

const TABLE_ID = 'items-table';
const RouterLink: LinkComponent<AppHref> = Link;

const columns: Column<CatalogueRow>[] = [
  {
    key: 'name', header: 'Item', sort: 'name',
    cell: (i) => <span className="entity-link entity-item"><ItemIcon icon={itemIconUrl(i.identity?.material ?? i.item_key, i.identity?.item_custom ?? 0)} name={itemLabel(i.item_key, i.identity)} size={20} />
      <span className="entity-name">{itemLabel(i.item_key, i.identity)}</span>{i.identity?.item_custom ? <span className="dt-sub"> · custom</span> : null}</span>,
  },
  { key: 'price', header: 'Average price', sort: 'price', numeric: true, cell: (i) => i.avg_price === null ? '—' : <Money value={priceToCents(i.avg_price) ?? '0.00'} /> },
  { key: 'trades', header: 'Trades', sort: 'trades', numeric: true, secondary: true, cell: (i) => i.trades.toLocaleString('en-US') },
  { key: 'qty', header: 'Items traded', numeric: true, secondary: true, optional: true, cell: (i) => i.qty.toLocaleString('en-US') },
  { key: 'volume', header: 'Volume', sort: 'volume', numeric: true, cell: (i) => <Money value={i.volume} /> },
];

/** The item catalogue: every item traded in the last 30 days, from the item rollup. */
export default async function ItemsPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const state = parseTableState(await searchParams, itemsTable);
  const { rows, total, coverage } = await getCatalogue(state);
  const totalPages = Math.max(1, Math.ceil(total / state.limit));
  const href = (s: TableState<typeof itemsTable>) => tableHref('/chestshop', s, itemsTable);
  const describe = { currency: CURRENCY_SYMBOL };
  const activeFilters = [
    ...(state.q ? [{ text: `“${state.q}”`, removeHref: href(withQuery(state, '')) }] : []),
    ...state.filters.map((f) => ({ text: filterText(f, itemsTable, describe), removeHref: href(withoutFilter(state, f.field)) })),
  ];
  return <>
    <PageHeader title="Items" subtitle="Everything traded at shops in the last 30 days. Prices are averages of real trades, not live quotes." />
    <FilterBar table={itemsTable} total={total} columns={columnOptions(columns)} tableId={TABLE_ID} currency={CURRENCY_SYMBOL} />
    <CoverageNote coverage={coverage} />
    <DataTable id={TABLE_ID} label="Items" columns={columns} rows={rows} rowKey={(i) => i.item_key} rowHref={(i) => itemHref(i.item_key)}
      sort={state.sort} sortHref={(field, dir) => href(withSort(state, field, dir, itemsTable))} link={RouterLink}
      empty={<TableEmpty noun="items" filters={activeFilters} clearHref={href(cleared(state))} link={RouterLink} />} />
    <TablePager page={Math.min(state.page, totalPages)} totalPages={totalPages} hrefFor={(p) => href(withPage(state, p))} link={RouterLink} />
  </>;
}
