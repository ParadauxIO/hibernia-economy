import { Suspense } from 'react';
import Link from 'next/link';
import type { Route } from 'next';
import { EntityBadge, InfoTip, MetricCard, MetricRow, Money, Num, PageHeader, Pct, Section, SkeletonMetricRow, TableWrap } from '@democracycraft/design-system';
import { RangePicker } from '@democracycraft/design-system/next';
import { buildMetadata } from '@/lib/metadata';
import { accountLabel } from '@/lib/format';
import { divRound, parseMoney } from '@/lib/money';
import { parseRange, toDay, type DayRange } from '@/lib/range';
import { wealthHistory, wealthNow } from '@/lib/services/economy';
import { BalanceDistribution, TimeSeriesChart } from '@/components/charts/AreaTimeline';
import { attempt, CoverageNote, SectionError, SectionSkeleton } from '../_components/coverage';

export const dynamic = 'force-dynamic';
export async function generateMetadata() { return buildMetadata({ title: 'Wealth', description: 'Wealth distribution and balances in the {server} economy.', path: '/economy/wealth' }); }

const PRESETS = ['30d', '90d', '1y', 'all'] as const;
const TYPES = ['PERSONAL', 'BUSINESS', 'GOVERNMENT', 'SYSTEM'] as const;
const TYPE_LABEL: Record<string, string> = { PERSONAL: 'Personal', BUSINESS: 'Business', GOVERNMENT: 'Government', SYSTEM: 'System' };
const TYPE_COLOR: Record<string, string> = { PERSONAL: 'var(--cat-1)', BUSINESS: 'var(--cat-3)', GOVERNMENT: 'var(--cat-4)', SYSTEM: 'var(--cat-8)' };

const MINTING = 'Government includes the accounts that create new money. Paying out money that never came in leaves a minting account with a negative balance, by design, so the government total can be negative. The ledger is shown as it is: nothing is hidden or netted off.';

export default async function WealthPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const parsed = parseRange(await searchParams, { defaultPreset: '90d' });
  return <>
    <PageHeader title="Wealth" subtitle="How money is spread across accounts. Balances are as they stand now; the history comes from daily snapshots." />
    <Suspense fallback={<SkeletonMetricRow count={4} />}><Concentration /></Suspense>
    <Suspense fallback={<SectionSkeleton title="Money by account type" rows={4} />}><ByType /></Suspense>
    <Section title="Over time" description={parsed.label}
      actions={<RangePicker value={parsed.value} presets={PRESETS} allowCompare={false} max={toDay(new Date())} />}>
      <Suspense fallback={<div className="econ-grid"><SectionSkeleton title="Money held by type" chartHeight={300} /><SectionSkeleton title="Concentration" chartHeight={300} /></div>}>
        <History range={parsed.range} />
      </Suspense>
    </Section>
    <div className="econ-grid">
      <Suspense fallback={<SectionSkeleton title="Personal balances" chartHeight={250} />}><Distribution /></Suspense>
      <Suspense fallback={<SectionSkeleton title="Largest accounts" rows={10} />}><TopAccounts /></Suspense>
    </div>
  </>;
}

async function Concentration() {
  const result = await attempt('wealth now', wealthNow);
  const w = result.ok ? result.value : null;
  return <MetricRow label="Wealth concentration">
    <MetricCard label="Gini coefficient" value={<Num value={w?.gini ?? null} decimals={3} />}
      info="Inequality of personal balances: 0 means everyone holds the same, 1 means one account holds everything. Over every open personal account with a positive balance." />
    <MetricCard label="Top 1% hold" value={<Pct value={w?.top1 ?? null} />} info="Share of all personal money held by the richest 1% of personal accounts." />
    <MetricCard label="Top 10% hold" value={<Pct value={w?.top10 ?? null} />} info="Share of all personal money held by the richest 10% of personal accounts." />
    <MetricCard label="Personal accounts with money" value={<Num value={w?.holders ?? null} />} meta="Open, positive balance" />
  </MetricRow>;
}

async function ByType() {
  const result = await attempt('wealth now', wealthNow);
  if (!result.ok) return <SectionError title="Money by account type" />;
  const byType = TYPES.map((type) => result.value.byType.find((b) => b.account_type === type)).filter((b) => b !== undefined);
  return <Section title="Money by account type" description="Open accounts, current balances.">
    <TableWrap label="Money by account type">
      <table className="data-table econ-table" aria-label="Money by account type">
        <thead><tr><th scope="col">Type</th><th scope="col" className="amount">Accounts</th><th scope="col" className="amount">Total balance</th><th scope="col" className="amount" data-secondary="true">Average</th></tr></thead>
        <tbody>{byType.map((b) => <tr key={b.account_type}>
          <th scope="row"><EntityBadge type={b.account_type} />{b.account_type === 'GOVERNMENT' && <InfoTip label="About negative government balances">{MINTING}</InfoTip>}</th>
          <td className="amount"><Num value={b.account_count} /></td>
          <td className="amount"><Money value={b.total_balance} /></td>
          <td className="amount" data-secondary="true"><Money value={b.account_count > 0 ? divRound(parseMoney(b.total_balance), b.account_count) : 0n} /></td>
        </tr>)}</tbody>
      </table>
    </TableWrap>
  </Section>;
}

async function History({ range }: { range: DayRange }) {
  const result = await attempt('wealth history', () => wealthHistory(range));
  if (!result.ok) return <SectionError title="Over time" />;
  const { snapshots, coverage } = result.value;
  const supply = snapshots.map((s) => ({ date: s.day, PERSONAL: s.supply.personal, BUSINESS: s.supply.business, GOVERNMENT: s.supply.government, SYSTEM: s.supply.system }));
  const shares = snapshots.map((s) => ({ date: s.day, gini: Number(s.gini), top1: Number(s.top1Share), top10: Number(s.top10Share) }));
  const empty = 'No snapshots in this period yet. The explorer records one a day.';
  return <>
    <CoverageNote coverage={coverage} snapshots />
    <div className="econ-grid">
      <TimeSeriesChart data={supply} kind="line" outliers={false} label="Money held by account type"
        title="Money held by type" description={<>End-of-day balance totals. <InfoTip label="About the government line">{MINTING}</InfoTip></>}
        note="One snapshot per day, UTC." empty={empty}
        series={TYPES.map((t) => ({ key: t, label: TYPE_LABEL[t]!, format: 'money' as const, color: TYPE_COLOR[t] }))} />
      <TimeSeriesChart data={shares} kind="line" outliers={false} label="Concentration of personal money"
        title="Concentration" description="Gini coefficient and the share held by the richest accounts."
        note="One snapshot per day, UTC." empty={empty}
        series={[
          { key: 'top1', label: 'Top 1% share', format: 'percent', color: 'var(--cat-1)' },
          { key: 'top10', label: 'Top 10% share', format: 'percent', color: 'var(--cat-3)' },
          { key: 'gini', label: 'Gini', format: 'number', color: 'var(--cat-5)', axis: 'right' },
        ]} />
    </div>
  </>;
}

async function Distribution() {
  const result = await attempt('wealth now', wealthNow);
  if (!result.ok) return <SectionError title="Personal balances" />;
  return <Section title="Personal balances" description="Open personal accounts by balance band. Empty accounts are left out.">
    <BalanceDistribution data={result.value.distribution} />
  </Section>;
}

async function TopAccounts() {
  const result = await attempt('wealth now', wealthNow);
  if (!result.ok) return <SectionError title="Largest accounts" />;
  return <Section title="Largest accounts" description="By current balance, all account types.">
    <TableWrap label="Largest accounts">
      <table className="data-table econ-table" aria-label="Largest accounts">
        <thead><tr><th scope="col">Account</th><th scope="col" data-secondary="true">Type</th><th scope="col" className="amount">Balance</th></tr></thead>
        <tbody>{result.value.topAccounts.map((a) => <tr key={a.account_id}>
          <td><Link href={`/accounts/${a.account_id}` as Route} prefetch={false}>{accountLabel(a)}</Link> <span className="num muted small">#{a.account_id}</span></td>
          <td data-secondary="true"><EntityBadge type={a.account_type} /></td>
          <td className="amount"><Money value={a.balance} /></td>
        </tr>)}</tbody>
      </table>
    </TableWrap>
  </Section>;
}
