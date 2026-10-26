import '@/components/entity/entity.css';
import { MetricCard, MetricRow, Money, Num, PageHeader, Section } from '@democracycraft/design-system';
import { EntityLink, RangePicker } from '@democracycraft/design-system/next';
import { DataTable, type Column } from '@democracycraft/design-system/table';
import { VolumeTimeline } from '@/components/charts/AreaTimeline';
import { CoverageNote } from '@/components/entity/bits';
import { itemHref, previewHref } from '@/lib/entityLinks';
import { itemIconUrl } from '@/lib/itemIcon';
import { buildMetadata } from '@/lib/metadata';
import { divRound, sum } from '@/lib/money';
import { parseRange } from '@/lib/range';
import { getMarketActivity, itemLabel } from '@/lib/services/market';
import { priceToCents } from '@/lib/shopPrice';

export const dynamic = 'force-dynamic';

export async function generateMetadata() {
  return buildMetadata({ title: 'Market activity', description: 'ChestShop trades on {server} over time: volume, trades and the items that moved the most money.', path: '/market/activity' });
}

/** Market-wide trades over a range, from the item rollup. */
export default async function MarketActivityPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const range = parseRange(await searchParams, { defaultPreset: '30d' });
  const { days, coverage, top } = await getMarketActivity(range.range);
  const trades = days.reduce((n, d) => n + d.trades, 0);
  const volume = sum(days.map((d) => d.volume));
  type Top = (typeof top)[number];
  const columns: Column<Top>[] = [
    { key: 'item', header: 'Item', cell: (t) => <EntityLink kind="item" href={itemHref(t.item_key)} name={itemLabel(t.item_key, t.identity)} icon={itemIconUrl(t.identity?.material ?? t.item_key, t.identity?.item_custom ?? 0)} preview={previewHref('item', t.item_key)} /> },
    { key: 'trades', header: 'Trades', numeric: true, secondary: true, cell: (t) => t.trades.toLocaleString('en-US') },
    { key: 'price', header: 'Average price', numeric: true, secondary: true, cell: (t) => t.avg_price === null ? '—' : <Money value={priceToCents(t.avg_price) ?? '0.00'} /> },
    { key: 'volume', header: 'Volume', numeric: true, cell: (t) => <Money value={t.volume} /> },
  ];

  return <>
    <PageHeader title="Market activity" subtitle="Every ChestShop trade on the server, added up by day." />
    <RangePicker value={range.value} max={range.range.to} />
    <MetricRow label="Market summary">
      <MetricCard label="Trades" value={<Num value={trades} />} meta={range.label} />
      <MetricCard label="Volume" value={<Money value={volume} compact />} meta={range.label} />
      <MetricCard label="Average trade" value={<Money value={trades > 0 ? divRound(volume, trades) : 0n} />} meta="Volume per trade" />
    </MetricRow>
    <Section title="Trades and volume" description={range.label}>
      <CoverageNote coverage={coverage} />
      <VolumeTimeline countLabel="Sales" data={days.map((d) => ({ date: d.day, txn_count: d.trades, total_volume: d.volume }))} />
    </Section>
    <Section title="Where the money went" description={`Items by volume, ${range.label.toLowerCase()}`} divider>
      <DataTable id="activity-items" label="Items by volume" columns={columns} rows={top} rowKey={(t) => t.item_key} density="compact"
        empty={<p className="dt-empty-text">No trades in this range.</p>} />
    </Section>
  </>;
}
