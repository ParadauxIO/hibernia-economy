import { buildMetadata } from '@/lib/metadata';
import { flattenSearchParams } from '@/lib/util/searchParams';
import { z } from 'zod';
import { getDailyVolume, getActivePlayersSummary, getNewPlayersDaily } from '@/lib/sql/health';
import { getEconomyActivityCalendar } from '@/lib/sql/ledger';
import { memo } from '@/lib/cache';
import { fmtAmtFull, fmtN } from '@/lib/format';
import { VolumeTimeline } from '@/components/charts/AreaTimeline';
import { SectionTabs } from '@/components/SectionTabs';
import { WindowTabs } from '@/components/WindowTabs';

const SP_SCHEMA = z.object({ days: z.coerce.number().int().min(7).max(30).default(30) });

export const dynamic = 'force-dynamic';

export async function generateMetadata() {
  return buildMetadata({ title: "Economy Health", description: "Key health indicators for the {server} economy.", path: "/economy/health" });
}

// The health aggregates (daily volume LEFT JOIN ledger_postings, active/new-
// player scans) are tenant-global and slow-moving but were previously uncached —
// every hit re-ran them. Cache per pod for 60s (single-flight + SWR, lib/cache),
// keyed by the day window.
const getHealthData = (days: number) =>
  memo(`economy-health:${days}`, 60_000, () =>
    Promise.all([
      getDailyVolume(days),
      getActivePlayersSummary(),
      getNewPlayersDaily(days),
      getEconomyActivityCalendar(days),
    ]),
  );

export default async function EconomyHealthPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const sp = SP_SCHEMA.parse(flattenSearchParams(await searchParams));

  const [vol, players, newPlayers, activity] = await getHealthData(sp.days);

  const totalVolume = vol.reduce((s, v) => s + parseFloat(v.total_volume), 0);
  const totalTxns = vol.reduce((s, v) => s + v.txn_count, 0);
  const totalNew = newPlayers.reduce((s, p) => s + p.count, 0);

  return (
    <>
      <div className="page-heading">
        <h1>Health</h1>
        <span className="sub">economy pulse · last {sp.days} days</span>
        <WindowTabs basePath="/economy/health" days={sp.days} />
        <SectionTabs />
      </div>

      <div className="kpi-grid">
        <div className="kpi">
          <div className="kpi-label">Active players / 1d</div>
          <div className="kpi-value">{fmtN(players.active1d)}</div>
          <div className="kpi-meta">{fmtN(players.active7d)} in 7d · {fmtN(players.active30d)} in 30d</div>
        </div>
        <div className="kpi">
          <div className="kpi-label">Registered</div>
          <div className="kpi-value">{fmtN(players.registered)}</div>
          <div className="kpi-meta">all-time known players</div>
        </div>
        <div className="kpi">
          <div className="kpi-label">New players</div>
          <div className="kpi-value">{fmtN(totalNew)}</div>
          <div className="kpi-meta">in this window</div>
        </div>
        <div className="kpi">
          <div className="kpi-label">Activity</div>
          <div className="kpi-value">{fmtN(activity.length)}</div>
          <div className="kpi-meta">days with txns</div>
        </div>
      </div>

      <div className="chart-card">
        <div className="chart-header">
          <div>
            <div className="chart-title">Daily volume</div>
            <div className="chart-subtitle">last {sp.days} days</div>
          </div>
          <span className="chart-tag">$ + #</span>
        </div>
        <VolumeTimeline data={vol.map((v) => ({ date: v.date, txn_count: v.txn_count, total_volume: v.total_volume }))} />
      </div>

      <div className="kpi-grid">
        <div className="kpi">
          <div className="kpi-label">Total volume</div>
          <div className="kpi-value">{fmtAmtFull(totalVolume)}</div>
          <div className="kpi-meta">credit side only</div>
        </div>
        <div className="kpi">
          <div className="kpi-label">Total transactions</div>
          <div className="kpi-value">{fmtN(totalTxns)}</div>
          <div className="kpi-meta">distinct txns</div>
        </div>
        <div className="kpi">
          <div className="kpi-label">Avg daily volume</div>
          <div className="kpi-value">{fmtAmtFull(vol.length > 0 ? totalVolume / vol.length : 0)}</div>
        </div>
        <div className="kpi">
          <div className="kpi-label">Avg daily txns</div>
          <div className="kpi-value">{fmtN(vol.length > 0 ? Math.round(totalTxns / vol.length) : 0)}</div>
        </div>
      </div>
    </>
  );
}

