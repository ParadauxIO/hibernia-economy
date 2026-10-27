import '@/components/entity/entity.css';
import Link from 'next/link';
import { redirect } from 'next/navigation';
import { MetricCard, MetricRow, Money, PageHeader, Section, When } from '@democracycraft/design-system';
import { RangePicker } from '@democracycraft/design-system/next';
import { DataTable, type Column } from '@democracycraft/design-system/table';
import { Breadcrumbs } from '@/components/Breadcrumbs';
import { BarChart, TimeSeriesChart } from '@/components/charts/AreaTimeline';
import { Candlestick } from '@/components/charts/Candlestick';
import { CopyButton } from '@/components/entity/CopyButton';
import { CoverageNote } from '@/components/entity/bits';
import { ItemIcon } from '@/components/market/ItemIcon';
import { ShopOwner } from '@/components/market/ShopOwner';
import { ShopTable } from '@/components/market/ShopTable';
import { hasPermission } from '@/lib/auth/access';
import { getViewer } from '@/lib/auth/viewer';
import { itemHref } from '@/lib/entityLinks';
import { itemIconUrl } from '@/lib/itemIcon';
import { compareRows, dailyCandles, spreadRows, summarise, tradeRows } from '@/lib/market/itemSeries';
import { buildMetadata } from '@/lib/metadata';
import { parseRange, rangeToParams } from '@/lib/range';
import { serverIdentity } from '@/lib/serverIdentity';
import { getItemOffers, getItemPage, getMarketOverview, getRecentItemSales, itemLabel } from '@/lib/services/market';
import { priceToCents } from '@/lib/shopPrice';
import type { MarketSaleRow, MarketShopRow } from '@/lib/sql/market';
import { resolveTheme } from '@/lib/theme';

export const dynamic = 'force-dynamic';

type Params = Promise<{ itemKey: string }>;
type SearchParams = Promise<Record<string, string | string[] | undefined>>;

const KEY_RE = /^[A-Za-z0-9_:.#-]{1,64}$/;

/** `?compare=A,B` (up to three other items), deduplicated and validated. */
function parseCompare(raw: string | string[] | undefined, itemKey: string): string[] {
  const value = Array.isArray(raw) ? raw.join(',') : raw ?? '';
  return [...new Set(value.split(',').map((k) => k.trim()).filter((k) => KEY_RE.test(k) && k !== itemKey))].slice(0, 3);
}

export async function generateMetadata({ params }: { params: Params }) {
  const { itemKey } = await params;
  const key = decodeURIComponent(itemKey);
  const name = itemLabel(key);
  return buildMetadata({ title: name, description: `${name} on {server}: price history, buy and sell prices, and where to trade it.`, path: itemHref(key) });
}

/** Spec §7.4: candles and volume from the item rollup, comparison, spread, and live offers with map links. */
export default async function ItemPage({ params, searchParams }: { params: Params; searchParams: SearchParams }) {
  const [{ itemKey: raw }, sp] = await Promise.all([params, searchParams]);
  const itemKey = decodeURIComponent(raw);
  const range = parseRange(sp, { defaultPreset: '30d' });
  const compare = parseCompare(sp.compare, itemKey);
  const self = itemHref(itemKey);
  const query = (keys: string[]) => {
    const p = new URLSearchParams(rangeToParams(range));
    if (keys.length) p.set('compare', keys.join(','));
    return `${self}?${p}` as const;
  };
  // "Add an item" form: fold ?add=KEY into ?compare= and redirect to the canonical URL.
  const add = typeof sp.add === 'string' ? sp.add.trim() : '';
  if (add) redirect(`${query(KEY_RE.test(add) && add !== itemKey ? [...new Set([...compare, add])].slice(0, 3) : compare)}#compare`);

  const viewer = await getViewer();
  const staff = hasPermission(viewer, 'market.figures.read');
  const [page, offers, overview, theme, sales] = await Promise.all([
    getItemPage(itemKey, compare, range.range),
    getItemOffers(itemKey),
    getMarketOverview(),
    resolveTheme(),
    staff ? getRecentItemSales(itemKey, 25) : Promise.resolve([] as MarketSaleRow[]),
  ]);
  const name = itemLabel(itemKey, page.identity ?? (offers.buy[0] ? { item_name: offers.buy[0].item_name, material: offers.buy[0].material, item_custom: offers.buy[0].item_custom } : null));
  const material = page.identity?.material ?? offers.buy[0]?.material ?? offers.sell[0]?.material ?? itemKey;
  const icon = itemIconUrl(material, page.identity?.item_custom ?? 0);
  const s = summarise(page.days, itemKey);
  const change = s.firstAvg && s.lastAvg ? (s.lastAvg - s.firstAvg) / s.firstAvg : null;
  const candles = dailyCandles(page.days, itemKey, 'BUY');
  const spread = spreadRows(page.days, itemKey);
  const trades = tradeRows(page.days, itemKey);
  const keys = [itemKey, ...page.compare.map((c) => c.itemKey)];
  const names = new Map([[itemKey, name], ...page.compare.map((c) => [c.itemKey, itemLabel(c.itemKey, c.identity)] as [string, string])]);
  const suggestions = overview.mostTraded.filter((m) => !keys.includes(m.item_key)).slice(0, 6);
  const mapBase = serverIdentity(theme).map;
  const owner: Column<MarketShopRow>[] = [{ key: 'owner', header: 'Shop', cell: (r) => <ShopOwner shop={r} /> }];

  return <>
    <Breadcrumbs section="/market" items={[{ label: name }]} />
    <PageHeader
      avatar={<ItemIcon icon={icon} name={name} size={44} />}
      eyebrow={<><span>Item</span><span className="num">{itemKey}</span></>}
      title={name}
      subtitle={s.trades > 0
        ? <>Traded {s.trades.toLocaleString('en-US')} times, {range.label.toLowerCase()}. Prices are averages of real trades, not live quotes.</>
        : <>No trades {range.label.toLowerCase()}. Prices below are what shops are asking now.</>}
      actions={<div className="entity-actions"><CopyButton value={self} label="Copy link" /></div>}
    />
    <RangePicker value={range.value} max={range.range.to} />

    <MetricRow label="Item summary">
      <MetricCard label="Average price" value={s.avgPrice ? <Money value={s.avgPrice} /> : '—'} delta={change} deltaLabel={`over ${range.label.toLowerCase()}`} deltaTone="neutral"
        info="Volume divided by items traded, both directions." />
      <MetricCard label="Shops charge" value={s.lastBuy ? <Money value={priceToCents(s.lastBuy) ?? '0.00'} /> : '—'} meta="Latest closing price, per item" />
      <MetricCard label="Shops pay" value={s.lastSell ? <Money value={priceToCents(s.lastSell) ?? '0.00'} /> : '—'} meta="Latest buy-back price, per item" />
      <MetricCard label="Volume" value={<Money value={s.volume} compact />} meta={`${s.qty.toLocaleString('en-US')} items`} />
    </MetricRow>
    <CoverageNote coverage={page.coverage} />

    <Section title="Price" description={`Daily candles for purchases from shops, ${range.label.toLowerCase()}`}>
      <Candlestick candles={candles} />
    </Section>

    <div className="entity-pair">
      <Section title="Trades" description="Per day: customers buying from shops, and selling to them" divider>
        <BarChart data={trades} x="date" xFormat="day" stacked height={200} label="Trades per day"
          series={[{ key: 'bought', label: 'Bought from shops', format: 'count', color: 'var(--cat-1)' }, { key: 'sold', label: 'Sold to shops', format: 'count', color: 'var(--cat-3)' }]}
          empty="No trades in this range." />
      </Section>
      <Section title="Buy and sell prices" description="Average per item per day, and the spread between them" divider>
        <TimeSeriesChart data={spread} x="date" kind="line" outliers={false} height={200} label="Buy and sell prices by day"
          series={[{ key: 'buy', label: 'Shops charge', format: 'money', color: 'var(--cat-1)' }, { key: 'sell', label: 'Shops pay', format: 'money', color: 'var(--cat-3)' }, { key: 'spread', label: 'Spread', format: 'money', color: 'var(--cat-5)' }]}
          empty="Not enough trades in both directions." />
      </Section>
    </div>

    <Section id="compare" title="Compare" description="Average price per day, up to three other items" divider>
      {page.compare.length > 0 && <TimeSeriesChart data={compareRows(page.days, keys)} x="date" kind="line" outliers={false} height={220} label="Average price by day"
        series={keys.map((k, i) => ({ key: k, label: names.get(k) ?? k, format: 'money' as const, color: `var(--cat-${i + 1})` }))} />}
      <ul className="role-list">
        {page.compare.map((c) => <li key={c.itemKey}><span>{names.get(c.itemKey)}</span>
          <Link href={`${query(compare.filter((k) => k !== c.itemKey))}#compare`}>Remove<span className="sr-only"> {names.get(c.itemKey)}</span></Link></li>)}
        {compare.length < 3 && suggestions.map((m) => <li key={m.item_key}>
          <Link href={`${query([...compare, m.item_key])}#compare`}>+ {itemLabel(m.item_key, m.identity)}</Link></li>)}
      </ul>
      {compare.length < 3 && <form action={self} method="get" className="market-search-controls no-print">
        {Object.entries(rangeToParams(range)).map(([k, v]) => <input key={k} type="hidden" name={k} value={v} />)}
        {compare.length > 0 && <input type="hidden" name="compare" value={compare.join(',')} />}
        <label htmlFor="compare-add" className="sr-only">Item key to compare</label>
        <input id="compare-add" className="input" name="add" placeholder="Item key, e.g. IRON_INGOT" />
        <button type="submit" className="btn">Compare</button>
      </form>}
    </Section>

    <Section title="Where to buy" description={`${offers.buyCount.toLocaleString('en-US')} shops sell ${name}, cheapest first`} divider
      actions={<Link className="btn btn-quiet" href={`/chestshop/shops?f=item:in:${encodeURIComponent(itemKey)}`}>All shops</Link>}>
      <ShopTable id="item-buy" label={`Shops selling ${name}`} shops={offers.buy} mapBase={mapBase} showItem={false} extra={owner} />
    </Section>
    <Section title="Where to sell" description={`${offers.sellCount.toLocaleString('en-US')} shops buy ${name}, best price first`} divider
      actions={<Link className="btn btn-quiet" href={`/chestshop/shops?f=item:in:${encodeURIComponent(itemKey)}&f=direction:in:sell`}>All shops</Link>}>
      <ShopTable id="item-sell" label={`Shops buying ${name}`} shops={offers.sell} mapBase={mapBase} showItem={false} direction="sell" extra={owner} />
    </Section>

    {staff && <Section id="recent-sales" title="Recent sales" description="Staff only: individual trades" divider
      actions={<Link className="btn btn-quiet" href={`/chestshop/sales?q=${encodeURIComponent(itemKey)}`}>Sales feed</Link>}>
      <Sales rows={sales} />
    </Section>}
  </>;
}

function Sales({ rows }: { rows: MarketSaleRow[] }) {
  const columns: Column<MarketSaleRow>[] = [
    { key: 'when', header: 'When', cell: (r) => <When value={r.occurred_at} time /> },
    { key: 'dir', header: 'Trade', cell: (r) => r.direction === 'BUY' ? 'Bought from shop' : 'Sold to shop' },
    { key: 'qty', header: 'Items', numeric: true, cell: (r) => r.quantity.toLocaleString('en-US') },
    { key: 'unit', header: 'Per item', numeric: true, secondary: true, cell: (r) => <Money value={priceToCents(r.unit_price) ?? '0.00'} /> },
    { key: 'total', header: 'Total', numeric: true, cell: (r) => <Money value={r.total_price} /> },
    { key: 'shop', header: 'Shop', secondary: true, cell: (r) => <ShopOwner shop={r} /> },
  ];
  return <DataTable id="item-sales" label="Recent sales" columns={columns} rows={rows} rowKey={(r) => r.sale_id} density="compact"
    empty={<p className="dt-empty-text">No sales recorded.</p>} />;
}

