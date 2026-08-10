import { buildMetadata } from '@/lib/metadata';
import { flattenSearchParams } from '@/lib/util/searchParams';
import { memo } from '@/lib/cache';
import { z } from 'zod';
import { topItemsWithSeries, volumeByDay, countSales, sumVolume, countDistinctItems } from '@/lib/sql/market';
import { fmtAmtFull, fmtN } from '@/lib/format';
import { SectionTabs } from '@/components/SectionTabs';
import { VolumeTimeline } from '@/components/charts/AreaTimeline';
import { ItemCards } from '@/components/market/ItemCards';
import { itemIconUrl } from '@/lib/itemIcon';
import { CsvButton } from '@/components/CsvButton';
import { JsonButton } from '@/components/JsonButton';

const SP_SCHEMA = z.object({ days: z.coerce.number().int().min(1).max(30).default(30) });

export const dynamic = 'force-dynamic';

// Only short windows for now — there isn't yet 30 days of data, so 90/365 are
// misleading.
const WINDOWS = [7, 30];

export async function generateMetadata() {
  return buildMetadata({ title: "Market", description: "ChestShop market overview for {server} — prices, volume and top-traded items.", path: "/market" });
}

// The market overview is tenant-global and slow-moving: the three all-time KPIs
// (countSales/sumVolume/countDistinctItems) are full scans of chestshop_sale on
// every load, and the windowed item/volume aggregates change only as new sales
// trickle in. Cache the whole bundle per `days` window for 60s — same approach
// as the dashboard (app/page.tsx). One tenant per pod, so the day arg is the only
// cache discriminator needed.
const getMarketData = (days: number) =>
  memo(`market-overview:${days}`, 60_000, () =>
    Promise.all([
      // 12 keeps the card grid rectangular at 2/3/4 columns (no orphan row).
      topItemsWithSeries(days, 12),
      volumeByDay(days),
      countSales(),
      sumVolume(),
      countDistinctItems(),
    ]),
  );

export default async function MarketPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const sp = SP_SCHEMA.parse(flattenSearchParams(await searchParams));
  const [items, volume, totalSales, totalVolume, distinctItems] = await getMarketData(sp.days);

  const windowSales = volume.reduce((s, v) => s + v.sales, 0);
  const windowVolume = volume.reduce((s, v) => s + parseFloat(v.total_volume), 0);

  return (
    <>
      <div className="page-heading">
        <h1>Market</h1>
        <span className="sub">ChestShop activity · last {sp.days} days</span>
        <span className="window-tabs">
          {WINDOWS.map((w) => (
            <a key={w} href={`/market?days=${w}`} className={w === sp.days ? 'active' : ''}>{w}d</a>
          ))}
        </span>
        <SectionTabs />
      </div>

      <div className="kpi-grid">
        <div className="kpi">
          <div className="kpi-label">Sales / {sp.days}d</div>
          <div className="kpi-value">{fmtN(windowSales)}</div>
          <div className="kpi-meta">{fmtN(totalSales)} all-time</div>
        </div>
        <div className="kpi">
          <div className="kpi-label">Volume / {sp.days}d</div>
          <div className="kpi-value">{fmtAmtFull(windowVolume)}</div>
          <div className="kpi-meta">{fmtAmtFull(totalVolume)} all-time</div>
        </div>
        <div className="kpi">
          <div className="kpi-label">Items traded</div>
          <div className="kpi-value">{fmtN(items.length)}</div>
          <div className="kpi-meta">{fmtN(distinctItems)} all-time</div>
        </div>
        <div className="kpi">
          <div className="kpi-label">Avg sale</div>
          <div className="kpi-value">{fmtAmtFull(windowSales > 0 ? windowVolume / windowSales : 0)}</div>
          <div className="kpi-meta">mean value per sale</div>
        </div>
      </div>

      <div className="chart-card">
        <div className="chart-header">
          <div>
            <div className="chart-title">Trade activity</div>
            <div className="chart-subtitle">sales volume + count · last {sp.days} days</div>
          </div>
          <span style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            <span className="chart-tag">$ + #</span>
            <span className="toolbar-export">
              <CsvButton
                filename={`market-volume-${sp.days}d.csv`}
                headers={['Date', 'Sales', 'Volume']}
                rows={volume.map((v) => [v.date, v.sales, v.total_volume])}
              />
              <JsonButton filename={`market-volume-${sp.days}d.json`} data={volume} />
            </span>
          </span>
        </div>
        <VolumeTimeline data={volume.map((v) => ({ date: v.date, txn_count: v.sales, total_volume: v.total_volume }))} />
      </div>

      <ItemCards
        items={items.map((i) => ({ ...i, icon: itemIconUrl(i.material, i.item_custom) }))}
        days={sp.days}
      />
    </>
  );
}

