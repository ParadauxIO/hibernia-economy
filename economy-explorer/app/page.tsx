import { buildMetadata } from '@/lib/metadata';
import { getOverviewData } from '@/lib/services/economy';
import { fmtAmt, fmtN } from '@/lib/format';
import { VolumeTimeline } from '@/components/charts/AreaTimeline';
import { Heatmap } from '@/components/charts/Heatmap';
import { SectionTabs } from '@/components/SectionTabs';
import { ChartCard, PageHeading, StatCard, WindowTabs } from '@democracycraft/design-system';
import { AutoRefresh } from '@/components/AutoRefresh';
export const dynamic = 'force-dynamic';
export async function generateMetadata() { return buildMetadata({ description: 'Activity and balances in the {server} economy.', path: '/' }); }
function change(now: number, before: number) {
  if (before === 0) return now === 0 ? 'No activity in either period' : 'No prior activity to compare';
  const delta = (now - before) / before * 100;
  return `${delta >= 0 ? '+' : ''}${delta.toFixed(1)}% vs previous period`;
}
export default async function DashboardPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const raw = await searchParams;
  const days = raw.days === '7' ? 7 : 30;
  const { stats, volume, players, newPlayers, comparison, heat } = await getOverviewData(days);
  const { current, previous } = comparison;
  const busiest = heat.reduce<(typeof heat)[number] | undefined>((peak, cell) => !peak || cell.txn_count > peak.txn_count ? cell : peak, undefined);
  const weekdays = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'];
  return <>
    <PageHeading title="Economy"><SectionTabs /></PageHeading>
    <div className="analytics-bar"><div><span>Activity: last {days} days · balances: current</span></div><div className="analytics-controls"><WindowTabs basePath="/" days={days} /><AutoRefresh windowMs={60_000} /></div></div>
    <div className="kpi-grid overview-metrics">
      <StatCard label="Personal supply" value={fmtAmt(stats.personalSupply)} meta="Current player-held balances" />
      <StatCard label={`Active players / ${days}d`} value={fmtN(days === 7 ? players.active7d : players.active30d)} meta={`${fmtN(newPlayers)} first seen in this period`} />
      <StatCard label={`Transactions / ${days}d`} value={fmtN(current.transactions)} meta={change(current.transactions, previous.transactions)} />
      <StatCard label={`Volume / ${days}d`} value={fmtAmt(current.volume)} meta={change(current.volume, previous.volume)} />
    </div>
    <p className="muted small">Volume and transaction counts estimate adjacent matching Vault bridge legs as one event. Unmatched rewards and spending remain; raw transaction history is unchanged.</p>
    <ChartCard><div className="section-heading"><h2>Transaction activity</h2><span className="muted small">Last {days} days</span></div><VolumeTimeline data={volume} /></ChartCard>
    <ChartCard>
      <div className="section-heading"><h2>Activity by weekday and hour</h2><span className="muted small">Raw ledger transactions · UTC · last {days} days</span></div>
      <Heatmap cells={heat} />
      {busiest && busiest.txn_count > 0 && <p className="muted small">Busiest slot: {weekdays[busiest.dow0]} {String(busiest.hr).padStart(2, '0')}:00 UTC · {fmtN(busiest.txn_count)} transactions</p>}
    </ChartCard>
    <p className="muted small">{fmtN(stats.totalAccounts)} open accounts · {fmtN(players.registered)} registered players</p>
  </>;
}
