import { WindowTabs } from '@democracycraft/design-system';
import { buildMetadata } from '@/lib/metadata';
import { flattenSearchParams } from '@/lib/util/searchParams';
import { getMarketWindow, getMarketTotals } from '@/lib/services/market';
import { Suspense } from 'react';
import { z } from 'zod';
import { fmtAmtFull, fmtN } from '@/lib/format';
import { divRound, formatMoney, sumBy } from '@/lib/money';
import { VolumeTimeline } from '@/components/charts/AreaTimeline';
import { CsvButton } from '@/components/CsvButton';
import { JsonButton } from '@/components/JsonButton';

const SP_SCHEMA = z.object({ days: z.coerce.number().int().min(1).max(30).default(30) });

export const dynamic = 'force-dynamic';

// Only short windows for now — there isn't yet 30 days of data, so 90/365 are
// misleading.

export async function generateMetadata() {
  return buildMetadata({ title: "Market", description: "ChestShop market overview for {server} — prices, volume and top-traded items.", path: "/market/activity" });
}

async function AllTime({ totals, field }: {
  totals: ReturnType<typeof getMarketTotals>;
  field: 'sales' | 'volume' | 'items';
}) {
  const data = await totals;
  return <>{field === 'volume' ? fmtAmtFull(data.volume) : fmtN(data[field])} all-time</>;
}

export default async function MarketPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const sp = SP_SCHEMA.parse(flattenSearchParams(await searchParams));
  const totals = getMarketTotals();
  const [volume, windowItems] = await getMarketWindow(sp.days);

  const windowSales = volume.reduce((s, v) => s + v.sales, 0);
  const windowVolume = sumBy(volume, (v) => v.total_volume);

  return (
    <>
      <div className="page-heading">
        <h1>Market activity</h1>
        <span className="sub">ChestShop activity · last {sp.days} days</span>
        <WindowTabs basePath="/market/activity" days={sp.days} />
      </div>

      <div className="kpi-grid">
        <div className="kpi">
          <div className="kpi-label">Sales / {sp.days}d</div>
          <div className="kpi-value">{fmtN(windowSales)}</div>
          <div className="kpi-meta"><Suspense fallback="— all-time"><AllTime totals={totals} field="sales" /></Suspense></div>
        </div>
        <div className="kpi">
          <div className="kpi-label">Volume / {sp.days}d</div>
          <div className="kpi-value">{formatMoney(windowVolume)}</div>
          <div className="kpi-meta"><Suspense fallback="— all-time"><AllTime totals={totals} field="volume" /></Suspense></div>
        </div>
        <div className="kpi">
          <div className="kpi-label">Items traded</div>
          <div className="kpi-value">{fmtN(windowItems)}</div>
          <div className="kpi-meta"><Suspense fallback="— all-time"><AllTime totals={totals} field="items" /></Suspense></div>
        </div>
        <div className="kpi">
          <div className="kpi-label">Avg sale</div>
          <div className="kpi-value">{formatMoney(windowSales > 0 ? divRound(windowVolume, windowSales) : 0n)}</div>
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
        <VolumeTimeline countLabel="Sales" data={volume.map((v) => ({ date: v.date, txn_count: v.sales, total_volume: v.total_volume }))} />
      </div>

    </>
  );
}
