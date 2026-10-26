import { Suspense } from 'react';
import { EntityBadge, InfoTip, MetricCard, MetricRow, Money, PageHeader, Section, SkeletonMetricRow, TableWrap } from '@democracycraft/design-system';
import { RangePicker } from '@democracycraft/design-system/next';
import { buildMetadata } from '@/lib/metadata';
import { parseMoney } from '@/lib/money';
import { parseRange, rangeLabel, toDay, type DayRange } from '@/lib/range';
import { ACCOUNT_TYPES, clampRange, MAX_FLOW_DAYS, moneyFlowMatrix, supplyChange, type AccountTypeKey, type FlowMatrix } from '@/lib/services/economy';
import { TimeSeriesChart } from '@/components/charts/AreaTimeline';
import { attempt, CoverageNote, NotReady, notReady, SectionError, SectionSkeleton } from '../economy/_components/coverage';

export const dynamic = 'force-dynamic';
export async function generateMetadata() {
  return buildMetadata({ title: 'Money flow', description: 'How money moves between account types across the {server} economy.', path: '/money-flow' });
}

// Built on the rollups only: the type-to-type matrix sums account_day rows by
// counterparty type, and the net changes come from type_day. Ranges are capped
// at MAX_FLOW_DAYS so the matrix never reads more than a quarter of account rows.
const PRESETS = ['7d', '30d', '90d'] as const;
const TYPE_LABEL: Record<AccountTypeKey, string> = { PERSONAL: 'Personal', BUSINESS: 'Business', GOVERNMENT: 'Government', SYSTEM: 'System' };
const TYPE_COLOR: Record<AccountTypeKey, string> = { PERSONAL: 'var(--cat-1)', BUSINESS: 'var(--cat-3)', GOVERNMENT: 'var(--cat-4)', SYSTEM: 'var(--cat-8)' };

const TYPE_INFO: Record<AccountTypeKey, string> = {
  PERSONAL: 'Player accounts.',
  BUSINESS: 'Firm accounts. A firm’s money belongs to the firm, not to its proprietor.',
  GOVERNMENT: 'Government accounts, including the accounts that create new money. Creating money lowers their balance, so a large outflow here is money entering the economy.',
  SYSTEM: 'Plugin and bridge accounts. Some plugins pass a payment through a system account, so one transfer appears as money in and money out here.',
};

export default async function MoneyFlowPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const parsed = parseRange(await searchParams, { defaultPreset: '30d' });
  const range = clampRange(parsed.range, MAX_FLOW_DAYS);
  const label = range === parsed.range ? parsed.label : `${rangeLabel('custom', range)} (the last ${MAX_FLOW_DAYS} days of your selection)`;
  return <>
    <PageHeader title="Money flow" subtitle="Who pays whom, by account type, and which types gained or lost money over the period." />
    <RangePicker value={parsed.value} presets={PRESETS} allowCompare={false} max={toDay(new Date())} />
    <Suspense fallback={<SkeletonMetricRow count={4} />}><NetChange range={range} /></Suspense>
    <Suspense fallback={<SectionSkeleton title="Between account types" rows={5} />}><Matrix range={range} label={label} /></Suspense>
    <Suspense fallback={<SectionSkeleton title="Net change by day" chartHeight={300} />}><NetByDay range={range} label={label} /></Suspense>
    <Suspense fallback={<SectionSkeleton title="By source" rows={8} />}><BySource range={range} label={label} /></Suspense>
  </>;
}

async function NetChange({ range }: { range: DayRange }) {
  const result = await attempt('supply change', () => supplyChange(range));
  const byType = result.ok && !notReady(result.value.coverage) ? result.value.byType : null;
  return <MetricRow label="Net change by account type">
    {ACCOUNT_TYPES.map((type) => {
      const row = byType?.find((r) => r.type === type);
      return <MetricCard key={type} label={TYPE_LABEL[type]} info={TYPE_INFO[type]}
        value={row ? <Money value={row.net} compact sign="exceptZero" tone="auto" /> : '—'}
        meta={row ? <>in <Money value={row.inflow} compact /> · out <Money value={row.outflow} compact /></> : undefined} />;
    })}
  </MetricRow>;
}

function share(amount: string, max: bigint): number {
  if (max <= 0n) return 0;
  return Number((parseMoney(amount) * 1000n) / max) / 10;
}

function FlowTable({ matrix }: { matrix: FlowMatrix }) {
  const lookup = new Map(matrix.flows.map((f) => [`${f.from}>${f.to}`, f.amount]));
  const max = matrix.flows.reduce((m, f) => (parseMoney(f.amount) > m ? parseMoney(f.amount) : m), 0n);
  return <TableWrap label="Money paid between account types">
    <table className="data-table econ-table econ-wide flow-matrix" aria-label="Money paid between account types">
      <caption className="sr-only">Rows are the paying account type, columns the receiving type.</caption>
      <thead><tr><th scope="col">From \ to</th>{ACCOUNT_TYPES.map((t) => <th key={t} scope="col" className="amount"><EntityBadge type={t} /></th>)}</tr></thead>
      <tbody>{ACCOUNT_TYPES.map((from) => <tr key={from}>
        <th scope="row"><EntityBadge type={from} /></th>
        {ACCOUNT_TYPES.map((to) => {
          const amount = lookup.get(`${from}>${to}`);
          return <td key={to} className="amount">{amount
            ? <span className="flow-cell"><Money value={amount} compact /><span className="flow-bar" data-self={from === to} style={{ inlineSize: `${share(amount, max)}%` }} aria-hidden="true" /></span>
            : <span className="muted">—</span>}</td>;
        })}
      </tr>)}</tbody>
    </table>
  </TableWrap>;
}

async function Matrix({ range, label }: { range: DayRange; label: string }) {
  const result = await attempt('flow matrix', () => moneyFlowMatrix(range));
  if (!result.ok) return <SectionError title="Between account types" />;
  const matrix = result.value;
  return <Section title="Between account types" description={<>Money paid from one type of account to another, {label.toLowerCase()}. Total <Money value={matrix.total} compact />.<InfoTip label="How flows are counted">
    Each payment is counted once, from the payer’s side, against the type of account that received it. Transfers between someone’s own accounts are left out. The ledger is shown as it is: a payment routed through a system bridge account appears as two flows, into System and out of System.
  </InfoTip></>} actions={<CoverageNote coverage={matrix.coverage} />}>
    <FlowTable matrix={matrix} />
  </Section>;
}

async function NetByDay({ range, label }: { range: DayRange; label: string }) {
  const result = await attempt('supply change', () => supplyChange(range));
  if (!result.ok) return <SectionError title="Net change by day" />;
  const { daily, coverage } = result.value;
  return <Section title="Net change by day" description={`Money gained (above zero) or lost by each account type per day, ${label.toLowerCase()}. The four lines always sum to zero.`}
    actions={<CoverageNote coverage={coverage} />}>
    {notReady(coverage) ? <NotReady height={300} /> : <TimeSeriesChart data={daily} kind="line" outliers={false} label="Net change by account type per day"
      empty="No activity in this period yet."
      series={ACCOUNT_TYPES.map((t) => ({ key: t, label: TYPE_LABEL[t], format: 'money' as const, color: TYPE_COLOR[t] }))} />}
  </Section>;
}

const sourceLabel = (key: string) => (key === '' ? 'Not recorded' : key);

async function BySource({ range, label }: { range: DayRange; label: string }) {
  const result = await attempt('supply change', () => supplyChange(range));
  if (!result.ok) return <SectionError title="By source" />;
  const rows = result.value.bySource.slice(0, 15);
  return <Section title="By source" description={`Net change per account type for the plugins that moved the most money, ${label.toLowerCase()}.`}>
    <TableWrap label="Net change by source and account type">
      <table className="data-table econ-table econ-wide" aria-label="Net change by source and account type">
        <thead><tr><th scope="col">Source</th>{ACCOUNT_TYPES.map((t) => <th key={t} scope="col" className="amount">{TYPE_LABEL[t]}</th>)}<th scope="col" className="amount" data-secondary="true">Paid in</th></tr></thead>
        <tbody>
          {rows.length === 0 && <tr><td colSpan={ACCOUNT_TYPES.length + 2} className="muted">No activity in this period yet.</td></tr>}
          {rows.map((r) => <tr key={r.pluginKey}>
            <th scope="row">{sourceLabel(r.pluginKey)}</th>
            {ACCOUNT_TYPES.map((t) => <td key={t} className="amount"><Money value={r[t]} compact sign="exceptZero" tone="auto" /></td>)}
            <td className="amount" data-secondary="true"><Money value={r.gross} compact /></td>
          </tr>)}
        </tbody>
      </table>
    </TableWrap>
  </Section>;
}
