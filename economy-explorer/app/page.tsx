import Link from 'next/link';
import { buildMetadata } from '@/lib/metadata';
import { getOverviewData } from '@/lib/services/economy';
import { fmtAmt, fmtN } from '@/lib/format';
import { VolumeTimeline } from '@/components/charts/AreaTimeline';
import { Heatmap } from '@/components/charts/Heatmap';
import { SectionTabs } from '@/components/SectionTabs';
import { WindowTabs } from '@/components/WindowTabs';
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
  return <>
    <div className="page-heading"><h1>Economy</h1><WindowTabs basePath="/" days={days} /><AutoRefresh windowMs={60_000} /><SectionTabs /></div>
    <p className="page-intro">A snapshot of player wealth and economic activity. Activity covers the last {days} days; balances show current holdings.</p>
    <div className="kpi-grid">
      <div className="kpi"><div className="kpi-label">Personal supply</div><div className="kpi-value">{fmtAmt(stats.personalSupply)}</div><div className="kpi-meta">Current player-held balances</div></div>
      <div className="kpi"><div className="kpi-label">Active players / {days}d</div><div className="kpi-value">{fmtN(days === 7 ? players.active7d : players.active30d)}</div><div className="kpi-meta">{fmtN(newPlayers)} first seen in this period</div></div>
      <div className="kpi"><div className="kpi-label">Transactions / {days}d</div><div className="kpi-value">{fmtN(current.transactions)}</div><div className="kpi-meta">{change(current.transactions, previous.transactions)}</div></div>
      <div className="kpi"><div className="kpi-label">Volume / {days}d</div><div className="kpi-value">{fmtAmt(current.volume)}</div><div className="kpi-meta">{change(current.volume, previous.volume)}</div></div>
    </div>
    <div className="chart-card"><h2 className="chart-title">Transaction activity</h2><VolumeTimeline data={volume} /></div>
    <div className="charts-grid">
      <div className="chart-card"><h2 className="chart-title">When the economy moves</h2><p className="chart-subtitle">Hour of day × weekday · UTC · last {days} days · scroll for all hours</p><Heatmap cells={heat} /></div>
      <div className="card"><h2 className="card-title">Explore the economy</h2>
        <p><Link href="/economy/wealth">Wealth distribution →</Link><br />Balances, inequality and top accounts.</p>
        <p><Link href="/money-flow">Money flow →</Link><br />Where money comes from and where it goes.</p>
        <p><Link href="/market">Find an item or shop →</Link><br />Prices and places to trade.</p>
        <p className="muted">{fmtN(stats.totalAccounts)} open accounts · {fmtN(players.registered)} registered players</p>
      </div>
    </div>
  </>;
}
