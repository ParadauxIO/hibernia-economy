import type { AppHref } from '@/lib/routes';
import Link from 'next/link';
import { Money, Num, PageHeader, When } from '@democracycraft/design-system';
import { DataTable, TableEmpty, columnOptions, type Column, type LinkComponent } from '@democracycraft/design-system/table';
import { ExportMenuForViewer } from '@/components/ExportMenuForViewer';
import { FeedPager } from '@/components/FeedPager';
import { FilterBar } from '@/components/FilterBar';
import { PrivacyGate } from '@/components/PrivacyGate';
import { searchSalesEntity } from '@/lib/actions/search';
import { auditView } from '@/lib/audit';
import { hasPermission } from '@/lib/auth';
import { getViewer } from '@/lib/auth/viewer';
import { CURRENCY_SYMBOL } from '@/lib/currency';
import { exportHrefs } from '@/lib/export/href';
import { shortenUuid } from '@/lib/format';
import { buildMetadata } from '@/lib/metadata';
import { getSalesPage } from '@/lib/services/sales';
import type { MarketSaleRow } from '@/lib/sql/market';
import { cleared, filterText, parseTableState, serializeTableState, stateCursor, tableHref, withFilter, withQuery, withSort, withoutFilter, type TableState } from '@/lib/tables/core';
import { JUMP_PARAM, feedLinks, parseFeedNav } from '@/lib/tables/feed';
import { salesTable } from '@/lib/tables/sales';

export const dynamic = 'force-dynamic';

export async function generateMetadata() {
  return buildMetadata({ title: 'Sales', description: 'Trade-by-trade ChestShop sales on {server}.', path: '/chestshop/sales' });
}

const TABLE_ID = 'sales-table';
const RouterLink: LinkComponent<AppHref> = Link;

function fmtDay(d: Date): string {
  return d.toISOString().slice(0, 10);
}

export default async function SalesFeedPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const viewer = await getViewer();
  if (viewer.anon) {
    return <PrivacyGate kind="login" title="ChestShop sales feed" hint="Sign in with moderator or admin access to view trade-by-trade detail with customer attribution." />;
  }
  if (!hasPermission(viewer, 'market.sales.read')) {
    return <PrivacyGate kind="private" title="Moderators and admins only" hint="Trade-by-trade sales with customer attribution need the market.sales.read permission." />;
  }

  const raw = await searchParams;
  const state = parseTableState(raw, salesTable);
  const nav = parseFeedNav(raw);
  const customer = state.filters.find((f) => f.field === 'customer');

  // Privileged read: trade-by-trade detail with customer attribution. When
  // filtered to one customer, that player is the target.
  await auditView(viewer, {
    path: '/chestshop/sales',
    targetType: customer?.values.length === 1 ? 'player' : 'chestshop',
    targetId: customer?.values.length === 1 ? customer.values[0] ?? null : serializeTableState(state, salesTable).slice(0, 64) || null,
  });

  const page = await getSalesPage(state, nav, stateCursor(state));
  const href = (s: TableState<typeof salesTable>) => tableHref('/chestshop/sales', s, salesTable);
  const describe = { currency: CURRENCY_SYMBOL, entityLabels: page.entityLabels };
  const activeFilters = [
    ...(state.q ? [{ text: `“${state.q}”`, removeHref: href(withQuery(state, '')) }] : []),
    ...state.filters.map((f) => ({ text: filterText(f, salesTable, describe), removeHref: href(withoutFilter(state, f.field)) })),
  ];
  const onlyCustomer = (uuid: string) => href(withFilter(state, { field: 'customer', op: 'in', values: [uuid] }));

  const columns: Column<MarketSaleRow>[] = [
    {
      key: 'item',
      header: 'Item',
      cell: (s) => <Link href={`/chestshop/items/${encodeURIComponent(s.item_key)}`} prefetch={false}>{s.item_name ?? s.item_key}</Link>,
    },
    { key: 'qty', header: 'Qty', numeric: true, cell: (s) => <Num value={s.quantity} /> },
    {
      key: 'direction',
      header: 'Trade',
      cell: (s) => (s.direction === 'BUY' ? 'Bought' : 'Sold'),
    },
    { key: 'total', header: 'Total', numeric: true, cell: (s) => <Money value={s.total_price} /> },
    { key: 'unit', header: 'Each', numeric: true, secondary: true, optional: true, cell: (s) => <Money value={s.unit_price} /> },
    {
      key: 'customerName',
      header: 'Customer',
      cell: (s) => s.customer_uuid
        ? <Link href={onlyCustomer(s.customer_uuid)} prefetch={false} title={s.customer_uuid}>{s.customer_name ?? shortenUuid(s.customer_uuid)}</Link>
        : <span className="muted">—</span>,
    },
    { key: 'seller', header: 'Shop', cell: (s) => s.owner_name ?? (s.admin_shop ? 'Admin shop' : <span className="muted">—</span>) },
    {
      key: 'where',
      header: 'Location',
      secondary: true,
      optional: true,
      cell: (s) => (s.world ? <span className="dt-sub">{s.world}{s.sign_x !== null ? ` ${s.sign_x}, ${s.sign_y}, ${s.sign_z}` : ''}</span> : <span className="muted">—</span>),
    },
    { key: 'stock', header: 'Stock after', numeric: true, secondary: true, optional: true, cell: (s) => <Num value={s.shop_stock} /> },
    { key: 'occurred', header: 'When', sort: 'occurred', sortFirst: 'desc', align: 'end', cell: (s) => <When value={s.occurred_at} time /> },
  ];

  const { newerHref, olderHref } = feedLinks('/chestshop/sales', state, salesTable, page.position);
  const countText = `${page.count.count.toLocaleString('en-US')}${page.count.capped ? '+' : ''}`;
  const hidden = new URLSearchParams(serializeTableState({ ...state, cursor: null }, salesTable));

  return (
    <>
      <PageHeader title="Sales" subtitle="Every ChestShop trade with the customer who made it. Staff only." />

      <FilterBar
        table={salesTable}
        total={page.count.count}
        totalIsFloor={page.count.capped}
        entityLabels={page.entityLabels}
        searchEntity={searchSalesEntity}
        columns={columnOptions(columns)}
        tableId={TABLE_ID}
        currency={CURRENCY_SYMBOL}
        exportSlot={<ExportMenuForViewer hrefs={exportHrefs('sales', state, salesTable)} allDescription={`${countText} sales, up to 100,000`} />}
      />

      {page.window.bounded && page.window.from && (
        <p className="muted small">
          Price and item-name searches cover {page.window.days} days at most: {fmtDay(page.window.from)} to {page.window.to ? fmtDay(new Date(page.window.to.getTime() - 1)) : 'now'}.
          {' '}Add a Date filter to search another period.
        </p>
      )}

      <DataTable
        id={TABLE_ID}
        label="Sales"
        columns={columns}
        rows={page.rows}
        rowKey={(s) => s.sale_id}
        sort={state.sort}
        sortHref={(field, dir) => href(withSort(state, field, dir, salesTable))}
        density="compact"
        link={RouterLink}
        empty={<TableEmpty noun="sales" filters={activeFilters} clearHref={href(cleared(state))} link={RouterLink} />}
      />

      <FeedPager
        newerHref={newerHref}
        olderHref={olderHref}
        summary={page.rows.length ? `Showing ${page.rows.length} of ${countText}` : undefined}
        jump={{ action: '/chestshop/sales', param: JUMP_PARAM, value: nav.jump?.day, hidden: [...hidden.entries()], max: fmtDay(new Date()) }}
      />
    </>
  );
}
