import Link from 'next/link';
import { redirect } from 'next/navigation';
import { PageHeader } from '@democracycraft/design-system';
import { TableEmpty, TablePager, type LinkComponent } from '@democracycraft/design-system/table';
import { FilterBar } from '@/components/FilterBar';
import { ShopOwner } from '@/components/market/ShopOwner';
import { ShopTable } from '@/components/market/ShopTable';
import { searchItemsAction } from '@/lib/actions/entity';
import { requireLinkedViewer } from '@/lib/auth/linked';
import { CURRENCY_SYMBOL } from '@/lib/currency';
import { buildMetadata } from '@/lib/metadata';
import { serverIdentity } from '@/lib/serverIdentity';
import { getShopDirectory, itemLabel } from '@/lib/services/market';
import { shopsTable } from '@/lib/tables/shops';
import { cleared, filterText, parseTableState, tableHref, withPage, withQuery, withSort, withoutFilter, type TableState } from '@/lib/tables/core';
import { resolveTheme } from '@/lib/theme';

export const dynamic = 'force-dynamic';

export async function generateMetadata() {
  return buildMetadata({ title: 'Find shops', description: 'Compare shop prices and stock on {server}, with map links.', path: '/chestshop/shops' });
}

/** Spec §5.5 market shops: live shop signs, cheapest first, with map links. Prices and stock are as last observed. */
export default async function ShopsPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const sp = await searchParams;
  await requireLinkedViewer('/chestshop/shops', sp);
  const parsed = parseTableState(sp, shopsTable);
  const sellSide = parsed.filters.some((f) => f.field === 'direction' && f.values.length === 1 && f.values[0] === 'sell');
  // Best price first: cheapest when buying, highest buy-back when selling. The
  // selling order goes in the URL so the summary line and shared links agree.
  if (sellSide && sp.sort === undefined) redirect(tableHref('/chestshop/shops', withSort(parsed, 'price', 'desc', shopsTable), shopsTable));
  const state = parsed;
  const [{ rows, total }, theme] = await Promise.all([getShopDirectory(state), resolveTheme()]);
  const totalPages = Math.max(1, Math.ceil(total / state.limit));
  const href = (s: TableState<typeof shopsTable>) => tableHref('/chestshop/shops', s, shopsTable);
  const items = state.filters.filter((f) => f.field === 'item').flatMap((f) => f.values);
  const label = (key: string) => {
    const row = rows.find((r) => r.item_key.toUpperCase() === key.toUpperCase());
    return itemLabel(key, row ? { item_name: row.item_name, material: row.material, item_custom: row.item_custom } : null);
  };
  const entityLabels = Object.fromEntries(items.map((k) => [`item:${k}`, label(k)]));
  const describe = { currency: CURRENCY_SYMBOL, entityLabels };
  const activeFilters = [
    ...(state.q ? [{ text: `“${state.q}”`, removeHref: href(withQuery(state, '')) }] : []),
    ...state.filters.map((f) => ({ text: filterText(f, shopsTable, describe), removeHref: href(withoutFilter(state, f.field)) })),
  ];
  const selling = sellSide;
  return <>
    <PageHeader title="Find shops" subtitle={selling
      ? 'Shops that buy items from you, best price first. Prices and stock are as last observed in game.'
      : 'Shops that sell items, cheapest first. Prices and stock are as last observed in game; check before you travel.'} />
    <FilterBar table={shopsTable} total={total} entityLabels={entityLabels} searchEntity={searchItemsAction} currency={CURRENCY_SYMBOL} />
    {rows.length === 0
      ? <TableEmpty noun="shops" filters={activeFilters} clearHref={href(cleared(state))} link={Link as LinkComponent} />
      : <ShopTable id="shops-table" label="Shops" shops={rows} mapBase={serverIdentity(theme).map} direction={selling ? "sell" : "buy"}
        extra={[{ key: 'owner', header: 'Shop', cell: (s) => <ShopOwner shop={s} /> }]} />}
    <TablePager page={Math.min(state.page, totalPages)} totalPages={totalPages} hrefFor={(p) => href(withPage(state, p))} link={Link as LinkComponent} />
  </>;
}
