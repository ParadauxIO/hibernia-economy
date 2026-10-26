import { Suspense } from 'react';
import Link from 'next/link';
import { InfoTip, MetricCard, MetricRow, Money, Num, PageHeader, Section, SkeletonMetricRow } from '@democracycraft/design-system';
import { formatNum } from '@democracycraft/design-system/format';
import { RangePicker } from '@democracycraft/design-system/next';
import { buildMetadata } from '@/lib/metadata';
import { parseRange, previousRange, rangeDays, toDay, type DayRange } from '@/lib/range';
import { parseMoney, toNumber } from '@/lib/money';
import { change, overviewActivity, overviewHeatmap, overviewSources, personalSupply } from '@/lib/services/economy';
import { BarChart, VolumeTimeline } from '@/components/charts/AreaTimeline';
import { Heatmap } from '@/components/charts/Heatmap';
import { AutoRefresh } from '@/components/AutoRefresh';
import { attempt, CoverageNote, NotReady, notReady, SectionError, SectionSkeleton } from './economy/_components/coverage';

export const dynamic = 'force-dynamic';
export async function generateMetadata() { return buildMetadata({ description: 'Activity and balances in the {server} economy.', path: '/' }); }

// The overview reads only the rollups (economy_day, hour_heatmap, type_day) and
// one sum over account_balances_mat, so a cold pod never runs a ledger scan.
// The shell streams at once; each section suspends on its own data.
//
// Rollups move once per worker tick (5 minutes), so the page refreshes on the
// same cadence rather than every minute: a faster refresh would re-render
// identical figures.
const REFRESH_MS = 5 * 60_000;
const PRESETS = ['7d', '30d', '90d', '1y'] as const;
const WEEKDAYS = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'];

export default async function OverviewPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const parsed = parseRange(await searchParams, { defaultPreset: '30d' });
  const range = parsed.range;
  const previous = previousRange(range);
  const period = `${rangeDays(range)} days`;
  return <>
    <PageHeader title="Economy"
      subtitle="Money moving through the economy over the selected period, and what players hold right now."
      actions={<AutoRefresh windowMs={REFRESH_MS} lagMs={20_000} label="Live" />} />
    <RangePicker value={parsed.value} presets={PRESETS} allowCompare={false} max={toDay(new Date())} />
    <Suspense fallback={<SkeletonMetricRow count={4} />}>
      <Metrics range={range} previous={previous} period={period} />
    </Suspense>
    <Suspense fallback={<SectionSkeleton title="Activity" chartHeight={300} />}>
      <Activity range={range} label={parsed.label} />
    </Suspense>
    <Suspense fallback={<SectionSkeleton title="When people trade" chartHeight={220} />}>
      <WhenActive range={range} label={parsed.label} />
    </Suspense>
    <Suspense fallback={<SectionSkeleton title="Where the money moves" chartHeight={280} />}>
      <Sources range={range} label={parsed.label} />
    </Suspense>
  </>;
}

async function Metrics({ range, previous, period }: { range: DayRange; previous: DayRange; period: string }) {
  const [supply, activity] = await Promise.all([attempt('personal supply', personalSupply), attempt('activity', () => overviewActivity(range, previous))]);
  // No covered day yet (fresh deploy): show dashes, not zeros that look like a dead economy.
  const a = activity.ok && !notReady(activity.value.coverage) ? activity.value : null;
  const prev = a?.previous ?? null;
  const deltaLabel = prev ? `vs previous ${period}` : a ? 'previous period not ready yet' : activity.ok ? 'history is being prepared' : undefined;
  const cents = (s: string) => toNumber(parseMoney(s));
  return <MetricRow label="Economy summary" className="overview-metrics">
    <MetricCard label="Personal money supply" value={supply.ok ? <Money value={supply.value} compact /> : '—'}
      meta="Held in player accounts now"
      info="The sum of every open personal account’s balance. Business and government money is not included." />
    <MetricCard label="Daily active players" value={<Num value={a?.totals.avgActivePlayers ?? null} />}
      delta={a && prev ? change(a.totals.avgActivePlayers ?? 0, prev.avgActivePlayers) : a ? null : undefined} deltaLabel={deltaLabel}
      spark={a?.days.map((d) => d.activePlayers)}
      meta={a ? `${formatNum(a.totals.newPlayers)} new players` : undefined}
      info="Average per day of the players whose personal account moved money that day." />
    <MetricCard label="Transactions" value={<Num value={a?.totals.txnCount ?? null} />}
      delta={a && prev ? change(a.totals.txnCount, prev.txnCount) : a ? null : undefined} deltaLabel={deltaLabel}
      spark={a?.days.map((d) => d.txnCount)} />
    <MetricCard label="Volume" value={a ? <Money value={a.totals.volume} compact /> : '—'}
      delta={a && prev ? change(cents(a.totals.volume), cents(prev.volume)) : a ? null : undefined} deltaLabel={deltaLabel}
      spark={a?.days.map((d) => cents(d.volume))} />
  </MetricRow>;
}

async function Activity({ range, label }: { range: DayRange; label: string }) {
  const result = await attempt('activity', () => overviewActivity(range, previousRange(range)));
  if (!result.ok) return <SectionError title="Activity" />;
  const { days, coverage } = result.value;
  return <Section title="Activity" description={<>{label}. A Vault bridge transfer counts once.<InfoTip label="About bridge-adjusted figures">
    Some plugins move money through a system “bridge” account, which the ledger records as two transactions. Here a matching pair counts as one transaction with one amount. The ledger itself is unchanged.
  </InfoTip></>} actions={<CoverageNote coverage={coverage} />}>
    {notReady(coverage) ? <NotReady height={300} /> : <VolumeTimeline data={days.map((d) => ({ date: d.day, txn_count: d.txnCount, total_volume: d.volume }))} />}
  </Section>;
}

async function WhenActive({ range, label }: { range: DayRange; label: string }) {
  const result = await attempt('heatmap', () => overviewHeatmap(range));
  if (!result.ok) return <SectionError title="When people trade" />;
  const { cells, coverage } = result.value;
  const busiest = cells.reduce<(typeof cells)[number] | undefined>((peak, c) => (!peak || c.txn_count > peak.txn_count ? c : peak), undefined);
  return <Section title="When people trade" description={`Transactions by weekday and hour (UTC), ${label.toLowerCase()}.`} actions={<CoverageNote coverage={coverage} />}>
    {notReady(coverage) ? <NotReady height={220} /> : <Heatmap cells={cells} />}
    {busiest && busiest.txn_count > 0 && <p className="econ-note">Busiest: {WEEKDAYS[busiest.dow0]}s at {String(busiest.hr).padStart(2, '0')}:00 UTC, {formatNum(busiest.txn_count)} transactions.</p>}
  </Section>;
}

const sourceLabel = (key: string) => key === '' ? 'Not recorded' : key;

async function Sources({ range, label }: { range: DayRange; label: string }) {
  const result = await attempt('sources', () => overviewSources(range));
  if (!result.ok) return <SectionError title="Where the money moves" />;
  const { sources, other, coverage } = result.value;
  const rows = [...sources.map((s) => ({ source: sourceLabel(s.pluginKey), volume: s.volume })),
    ...(parseMoney(other) > 0n ? [{ source: 'Everything else', volume: other }] : [])];
  return <Section title="Where the money moves" description={<>Money paid into accounts by each plugin, {label.toLowerCase()}. <Link href="/money-flow">See money flow by account type</Link></>}
    actions={<CoverageNote coverage={coverage} />}>
    {notReady(coverage) ? <NotReady height={200} /> : <BarChart data={rows} x="source" horizontal height={Math.max(160, rows.length * 32)} label="Money paid in by source"
      note="As recorded on the ledger: a bridge transfer appears once per leg."
      series={[{ key: 'volume', label: 'Paid in', format: 'money', color: 'var(--cat-1)' }]}
      empty="No activity in this period yet." />}
  </Section>;
}
