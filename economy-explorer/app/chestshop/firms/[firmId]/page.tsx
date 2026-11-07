import '@/components/entity/entity.css';
import Link from 'next/link';
import { notFound } from 'next/navigation';
import { AccessGate, EntityBadge, MetricCard, MetricRow, Money, Num, PageHeader, Section } from '@democracycraft/design-system';
import { EntityLink } from '@democracycraft/design-system/next';
import { DataTable, type Column } from '@democracycraft/design-system/table';
import { Breadcrumbs } from '@/components/Breadcrumbs';
import { LoginButton } from '@/components/entity/LoginButton';
import { auditView } from '@/lib/audit';
import { canViewFirmFinancials, hasFirmFinancialAccess, hasPermission } from '@/lib/auth/access';
import { getViewer } from '@/lib/auth/viewer';
import { firmHref, itemHref, previewHref } from '@/lib/entityLinks';
import { itemIconUrl } from '@/lib/itemIcon';
import { buildMetadata } from '@/lib/metadata';
import { getMarketFirm, listFirmMarketItems, type MarketItemRow } from '@/lib/sql/market';
import { parseDaysWindow } from '@/lib/util/daysWindow';
import { flattenSearchParams } from '@/lib/util/searchParams';

export const dynamic = 'force-dynamic';

export async function generateMetadata({ params }: { params: Promise<{ firmId: string }> }) {
  const { firmId } = await params;
  return buildMetadata({ title: `Market firm #${firmId}`, description: 'Shop sales for this {server} firm.', path: `/chestshop/firms/${firmId}` });
}

/**
 * One firm's ChestShop figures. `?days=N` is the window the in-game
 * `/firm sales export <firm> <days>` link asks for; absent means all time.
 * Figures are for the firm's finance staff and staff with financial access.
 */
export default async function MarketFirmPage({ params, searchParams }: {
  params: Promise<{ firmId: string }>;
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const { firmId: raw } = await params;
  if (!/^[1-9]\d{0,9}$/.test(raw)) notFound();
  const firmId = Number(raw);
  const days = parseDaysWindow(flattenSearchParams(await searchParams).days);
  const windowLabel = days == null ? 'All time' : `Last ${days.toLocaleString('en-US')} ${days === 1 ? 'day' : 'days'}`;

  const viewer = await getViewer();
  const [canSeeFinancials, firm] = await Promise.all([canViewFirmFinancials(firmId, viewer), getMarketFirm(firmId, days)]);
  if (!firm) notFound();
  const items = canSeeFinancials ? await listFirmMarketItems(firmId, 50, 0, days) : [];
  if (canSeeFinancials && hasPermission(viewer, 'firm.financials.read.all')) {
    const own = viewer.minecraftUuid ? await hasFirmFinancialAccess(firmId, viewer.minecraftUuid) : false;
    if (!own) await auditView(viewer, { path: `/chestshop/firms/${firmId}`, targetType: 'firm', targetId: String(firmId) });
  }
  const name = firm.display_name ?? `Firm #${firm.firm_id}`;
  const columns: Column<MarketItemRow>[] = [
    { key: 'item', header: 'Item', cell: (i) => <EntityLink kind="item" href={itemHref(i.item_key)} name={i.item_name ?? i.item_key} icon={itemIconUrl(i.material, i.item_custom)} preview={previewHref('item', i.item_key)} /> },
    { key: 'trades', header: 'Trades', numeric: true, cell: (i) => i.trade_count.toLocaleString('en-US') },
    { key: 'qty', header: 'Items', numeric: true, secondary: true, cell: (i) => i.total_quantity.toLocaleString('en-US') },
    { key: 'volume', header: 'Volume', numeric: true, cell: (i) => <Money value={i.total_volume} /> },
  ];

  return <>
    <Breadcrumbs section="/chestshop/firms" items={[{ label: name }]} />
    <PageHeader eyebrow={<><EntityBadge type="BUSINESS" label="Firm" /><span className="num">#{firm.firm_id}</span></>} title={name}
      subtitle={<>Shop trading for <EntityLink kind="firm" href={firmHref(name)} name={name} preview={previewHref('firm', firm.firm_id)} />. {windowLabel}.</>}
      actions={days != null ? <Link className="btn btn-quiet" href={`/chestshop/firms/${firm.firm_id}`}>Show all time</Link> : undefined} />
    {!canSeeFinancials
      ? <AccessGate kind={viewer.anon ? 'login' : 'request'} title="This firm’s shop figures are private"
        description={<p>Sales, items and volume are visible to the firm’s finance staff. The firm’s balances are public on its page.</p>}
        grants={['The firm’s proprietor', 'An employee whose role has the Financial or Admin permission', 'Staff with permission to read every firm’s financials']}
        action={viewer.anon ? <LoginButton returnTo={`/chestshop/firms/${firm.firm_id}`} label="Log in" /> : <Link className="btn" href={firmHref(name)}>Firm page</Link>} />
      : <>
        <MetricRow label="Shop figures">
          <MetricCard label="Sales" value={<Num value={firm.sale_count} />} meta={windowLabel} />
          <MetricCard label="Items moved" value={<Num value={firm.total_quantity} />} meta={windowLabel} />
          <MetricCard label="Volume" value={<Money value={firm.total_volume} />} meta={windowLabel} />
          <MetricCard label="Distinct items" value={<Num value={items.length} />} meta="Up to 50 shown" />
        </MetricRow>
        <Section title="Items sold" description={`${windowLabel}, by number of trades`}>
          <DataTable id="market-firm-items" label="Items sold" columns={columns} rows={items} rowKey={(i) => i.item_key} density="compact"
            empty={<p className="dt-empty-text">{days == null ? 'No items.' : 'No sales in this window.'}</p>} />
        </Section>
      </>}
  </>;
}
