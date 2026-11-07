import '@/components/entity/entity.css';
import Link from 'next/link';
import { MetricCard, MetricRow, Money, Num, PageHeader, Pct, Section } from '@democracycraft/design-system';
import { EntityLink } from '@democracycraft/design-system/next';
import { DataTable, type Column } from '@democracycraft/design-system/table';
import { CoverageNote } from '@/components/entity/bits';
import { MarketSearch } from '@/components/market/MarketSearch';
import { ShopTable } from '@/components/market/ShopTable';
import { ShopOwner } from '@/components/market/ShopOwner';
import { itemHref, previewHref } from '@/lib/entityLinks';
import { itemIconUrl } from '@/lib/itemIcon';
import { buildMetadata } from '@/lib/metadata';
import { serverIdentity } from '@/lib/serverIdentity';
import { priceToCents } from '@/lib/shopPrice';
import { change } from '@/lib/services/account';
import { getMarketOverview, itemLabel, type MarketOverview, type Mover } from '@/lib/services/market';
import { resolveTheme } from '@/lib/theme';

export const dynamic = 'force-dynamic';

export async function generateMetadata() {
  return buildMetadata({ title: 'Market', description: 'What is selling on {server}: price movers, most traded items and the newest shops.', path: '/market' });
}

/** Spec §7.4: the market's front page. Everything here comes from the item rollup, refreshed every few minutes. */
export default async function MarketPage() {
  const [overview, theme] = await Promise.all([getMarketOverview(), resolveTheme()]);
  const { week, prevWeek } = overview;
  return <>
    <PageHeader title="Market" subtitle="Prices and trades at player, firm and admin shops over the last 7 days." />
    <MarketSearch />
    <MetricRow label="Market this week">
      <MetricCard label="Trades, 7 days" value={<Num value={week.trades} />} delta={change(week.trades, prevWeek?.trades)} deltaLabel="vs previous 7 days" deltaTone="neutral"
        spark={overview.days.length > 1 ? overview.days.map((d) => d.trades) : undefined} />
      <MetricCard label="Volume, 7 days" value={<Money value={week.volume} compact />} delta={change(week.volume, prevWeek?.volume)} deltaLabel="vs previous 7 days" deltaTone="neutral" />
      <MetricCard label="Items traded" value={<Num value={week.items} />} delta={change(week.items, prevWeek?.items)} deltaLabel="vs previous 7 days" deltaTone="neutral" />
    </MetricRow>
    <CoverageNote coverage={overview.coverage} />

    <div className="entity-pair">
      <Section title="Rising" description="Average price, this week against last week">
        <Movers id="gainers" rows={overview.gainers} empty="No item rose in price this week." />
      </Section>
      <Section title="Falling" description="Average price, this week against last week">
        <Movers id="losers" rows={overview.losers} empty="No item fell in price this week." />
      </Section>
    </div>

    <Section title="Most traded" description="Last 7 days, by number of trades" divider actions={<Link href="/chestshop" className="btn btn-quiet">All items</Link>}>
      <MostTraded rows={overview.mostTraded} />
    </Section>

    <Section title="Newest shops" description="Signs seen for the first time most recently" divider actions={<Link href="/chestshop/shops" className="btn btn-quiet">Find shops</Link>}>
      <ShopTable id="newest-shops" label="Newest shops" shops={overview.newest} mapBase={serverIdentity(theme).map}
        extra={[{ key: 'owner', header: 'Shop', secondary: true, cell: (s) => <ShopOwner shop={s} /> }]} />
    </Section>
  </>;
}

function ItemCell({ itemKey, identity }: { itemKey: string; identity: Mover['identity'] }) {
  return <EntityLink kind="item" href={itemHref(itemKey)} name={itemLabel(itemKey, identity)} icon={itemIconUrl(identity?.material ?? itemKey, identity?.item_custom ?? 0)} preview={previewHref('item', itemKey)} />;
}

function Movers({ id, rows, empty }: { id: string; rows: Mover[]; empty: string }) {
  const columns: Column<Mover>[] = [
    { key: 'item', header: 'Item', cell: (m) => <ItemCell itemKey={m.itemKey} identity={m.identity} /> },
    { key: 'price', header: 'Average now', numeric: true, cell: (m) => <Money value={priceToCents(m.after) ?? "0.00"} /> },
    { key: 'change', header: 'Change', numeric: true, cell: (m) => <Pct value={m.change} sign="always" tone="auto" /> },
  ];
  return <DataTable id={id} label={id === 'gainers' ? 'Rising prices' : 'Falling prices'} columns={columns} rows={rows} rowKey={(m) => m.itemKey} density="compact"
    empty={<p className="dt-empty-text">{empty}</p>} />;
}

function MostTraded({ rows }: { rows: MarketOverview['mostTraded'] }) {
  const columns: Column<MarketOverview['mostTraded'][number]>[] = [
    { key: 'item', header: 'Item', cell: (r) => <ItemCell itemKey={r.item_key} identity={r.identity} /> },
    { key: 'trades', header: 'Trades', numeric: true, cell: (r) => r.trades.toLocaleString('en-US') },
    { key: 'qty', header: 'Items', numeric: true, secondary: true, cell: (r) => r.qty.toLocaleString('en-US') },
    { key: 'price', header: 'Average price', numeric: true, secondary: true, cell: (r) => r.avg_price === null ? "—" : <Money value={priceToCents(r.avg_price) ?? "0.00"} /> },
    { key: 'volume', header: 'Volume', numeric: true, cell: (r) => <Money value={r.volume} compact /> },
  ];
  return <DataTable id="most-traded" label="Most traded items" columns={columns} rows={rows} rowKey={(r) => r.item_key} density="compact"
    empty={<p className="dt-empty-text">No trades in the last 7 days.</p>} />;
}
