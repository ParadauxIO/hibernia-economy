import Link from 'next/link';
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
    <div className="overview-lead"><span className="eyebrow">A living economy</span><h2>See the bigger picture.</h2><p>Follow the activity, understand the balances, and discover where to explore next.</p></div>
    <div className="analytics-bar"><div><strong>Last {days} days of activity</strong><span>Compared with the previous {days} days · balances are current holdings</span></div><div className="analytics-controls"><WindowTabs basePath="/" days={days} /><AutoRefresh windowMs={60_000} /></div></div>
    <div className="kpi-grid">
      <StatCard label="Personal supply" value={fmtAmt(stats.personalSupply)} meta="Current player-held balances" />
      <StatCard label={`Active players / ${days}d`} value={fmtN(days === 7 ? players.active7d : players.active30d)} meta={`${fmtN(newPlayers)} first seen in this period`} />
      <StatCard label={`Transactions / ${days}d`} value={fmtN(current.transactions)} meta={change(current.transactions, previous.transactions)} />
      <StatCard label={`Volume / ${days}d`} value={fmtAmt(current.volume)} meta={change(current.volume, previous.volume)} />
    </div>
    <ChartCard><div className="section-heading"><div><h2>Transaction activity</h2><p>How much moved, and how often.</p></div><span className="chart-tag">{days}-day view</span></div><VolumeTimeline data={volume} /></ChartCard>
    <ChartCard><div className="section-heading"><div><h2>When the economy moves</h2><p>Activity by hour and weekday · UTC · last {days} days</p></div><span className="muted small">Scroll the chart for all hours</span></div><div className="activity-pattern"><Heatmap cells={heat} /><aside className="activity-insight"><span className="eyebrow">A rhythm to the week</span>{busiest && busiest.txn_count > 0 ? <><h3>{weekdays[busiest.dow0]} at {String(busiest.hr).padStart(2, '0')}:00 UTC</h3><p>The busiest weekday-and-hour slot in this period, with <strong>{fmtN(busiest.txn_count)} transactions</strong> across the selected days.</p></> : <><h3>Patterns take time.</h3><p>Activity will appear here as transactions are recorded.</p></>}<p className="small muted">Brighter cells mean more transactions. Each cell covers one hour.</p></aside></div></ChartCard>
    <div className="section-heading"><div><span className="eyebrow">Keep exploring</span><h2>Follow your curiosity.</h2></div><span className="muted small">{fmtN(stats.totalAccounts)} open accounts · {fmtN(players.registered)} registered players</span></div>
    <div className="explore-grid">
      <Link href="/economy/wealth" className="explore-card"><span className="eyebrow">01 / Wealth</span><h3>Who holds the wealth?</h3><p>Explore balances, distribution, and the biggest accounts.</p><span className="item-card-action">Explore wealth <span aria-hidden>→</span></span></Link>
      <Link href={`/money-flow?days=${days}`} className="explore-card"><span className="eyebrow">02 / Movement</span><h3>Where does money go?</h3><p>Follow money as it moves between different account types.</p><span className="item-card-action">Follow the flow <span aria-hidden>→</span></span></Link>
      <Link href="/market" className="explore-card"><span className="eyebrow">03 / Market</span><h3>Find your next trade.</h3><p>Discover items, compare observed offers, and find a shop.</p><span className="item-card-action">Visit the market <span aria-hidden>→</span></span></Link>
    </div>
  </>;
}
