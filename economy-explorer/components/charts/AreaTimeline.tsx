'use client';
import { useId, useState, type ReactElement } from 'react';
import { TableWrap } from '@democracycraft/design-system';
import { useHydrated } from '@democracycraft/design-system/use-hydrated';
import { useMediaQuery } from '@/components/useMediaQuery';
import {
  Area, AreaChart, Bar, BarChart,
  CartesianGrid, Cell, Line, LineChart, Pie, PieChart,
  ResponsiveContainer, Tooltip, XAxis, YAxis,
} from 'recharts';
import { TAX_KINDS, TAX_LABELS, type TaxKind } from '@/lib/taxation';
import { fmtAmt, fmtAmtFull, fmtDate, fmtN } from '@/lib/format';

const GRID_STROKE = 'var(--border-soft)';
const AXIS_TICK = { fontSize: 11, fill: 'var(--fg-muted)' };

/**
 * Mount-gated ResponsiveContainer. recharts measures the DOM to size itself,
 * so it renders nothing on the server and the chart on the client — a
 * hydration mismatch (React #418). Rendering a same-height placeholder until
 * mounted keeps SSR and the first client render identical, then swaps in the
 * chart with no layout shift.
 */
function RC({ height, children, daily = false }: { height: number; children: ReactElement; daily?: boolean }) {
  const mounted = useHydrated();
  return (
    <>
      {daily && <p className="chart-subtitle">Daily buckets in UTC.</p>}
      {mounted ? (
        <ResponsiveContainer width="100%" height={height}>
          {children}
        </ResponsiveContainer>
      ) : <div style={{ width: '100%', height }} aria-hidden />}
    </>
  );
}

interface VolumeTimelineProps {
  countLabel?: 'Transactions' | 'Sales';
  data: { date: string; txn_count: number; total_volume: string }[];
}

export function VolumeTimeline({ data, countLabel = 'Transactions' }: VolumeTimelineProps) {
  // Phones default to volume only (two axes don't fit); an explicit choice wins.
  const chartId = useId().replaceAll(':', '');
  const narrow = useMediaQuery('(max-width: 720px)');
  const [chosen, setMetric] = useState<string | null>(null);
  const metric = chosen ?? (narrow ? 'volume' : 'both');
  if (data.length === 0) {
    return <EmptyChart>No recorded activity in this period. Try another time window.</EmptyChart>;
  }
  const series = data.map((d) => ({ date: d.date, count: d.txn_count, volume: parseFloat(d.total_volume) }));
  return (
    <>
    <label className="chart-series-control">Show <select aria-label="Chart series" value={metric} onChange={e => setMetric(e.target.value)}><option value="both">Volume and {countLabel.toLowerCase()}</option><option value="volume">Volume</option><option value="count">{countLabel}</option></select></label>
    <div className="chart-legend">{metric !== 'count' && <span><span aria-hidden style={{ color: 'var(--c-1)' }}>●</span> Volume · right axis</span>}{metric !== 'volume' && <span><span aria-hidden style={{ color: 'var(--c-3)' }}>●</span> {countLabel} · left axis</span>}</div>
    <p className="chart-subtitle">Daily buckets in UTC. The first and current day may be partial.</p>
    <RC height={230}>
      <AreaChart data={series} margin={{ top: 4, right: 8, bottom: 0, left: 0 }}>
        <defs>
          <linearGradient id={`${chartId}-volume`} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="var(--c-1)" stopOpacity={0.35} />
            <stop offset="100%" stopColor="var(--c-1)" stopOpacity={0} />
          </linearGradient>
          <linearGradient id={`${chartId}-count`} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="var(--c-3)" stopOpacity={0.18} />
            <stop offset="100%" stopColor="var(--c-3)" stopOpacity={0} />
          </linearGradient>
        </defs>
        <CartesianGrid strokeDasharray="2 4" stroke={GRID_STROKE} vertical={false} />
        <XAxis dataKey="date" tickFormatter={fmtDate} tick={AXIS_TICK} tickLine={false} axisLine={false} minTickGap={28} />
        <YAxis hide={metric === 'count'} yAxisId="vol" orientation="right" tickFormatter={(v) => fmtAmt(String(v))} tick={AXIS_TICK} tickLine={false} axisLine={false} width={56} />
        <YAxis
          hide={metric === 'volume'}
          yAxisId="cnt"
          orientation="left"
          domain={[0, 'dataMax']}
          allowDecimals={false}
          tickFormatter={(v) => fmtN(v)}
          tick={AXIS_TICK}
          tickLine={false}
          axisLine={false}
          width={44}
        />
        <Tooltip
          contentStyle={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 8, boxShadow: '0 6px 18px rgba(0,0,0,0.18)' }}
          labelStyle={{ color: 'var(--fg)', fontWeight: 600, marginBottom: 2 }}
          itemStyle={{ color: 'var(--fg-soft)' }}
          labelFormatter={(d) => fmtDate(String(d))}
          formatter={(v: number, name: string) => name === 'volume' ? [fmtAmtFull(v), 'Volume'] : [fmtN(v), countLabel]}
        />
        <Area hide={metric === 'count'} yAxisId="vol" type="monotone" dataKey="volume" name="volume" stroke="var(--c-1)" strokeWidth={2} fill={`url(#${chartId}-volume)`} dot={false} isAnimationActive={false} />
        <Area hide={metric === 'volume'} yAxisId="cnt" type="monotone" dataKey="count" name="count" stroke="var(--c-3)" strokeWidth={1.5} fill={`url(#${chartId}-count)`} dot={false} isAnimationActive={false} />
      </AreaChart>
    </RC>
    <details className="chart-data"><summary>View chart data</summary><TableWrap label={`${countLabel} and volume by day`}><table className="data-table" aria-label={`${countLabel} and volume by day`}><thead><tr><th scope="col">Date (UTC)</th><th scope="col" className="amount">{countLabel}</th><th scope="col" className="amount">Volume</th></tr></thead><tbody>{data.map(row => <tr key={row.date}><th scope="row">{fmtDate(row.date)}</th><td className="amount">{fmtN(row.txn_count)}</td><td className="amount">{fmtAmtFull(row.total_volume)}</td></tr>)}</tbody></table></TableWrap></details>
    </>
  );
}

const TYPE_COLORS: Record<string, string> = {
  PERSONAL: 'var(--c-1)',
  BUSINESS: 'var(--c-3)',
  GOVERNMENT: 'var(--c-4)',
  SYSTEM: 'var(--c-2)',
};

interface DonutProps {
  data: { account_type: string; account_count: number; total_balance: string }[];
}
export function WealthDonut({ data }: DonutProps) {
  const pie = data
    .filter((d) => parseFloat(d.total_balance) > 0)
    .map((d) => ({
      name: d.account_type,
      value: parseFloat(d.total_balance),
      accountCount: d.account_count,
      fill: TYPE_COLORS[d.account_type] ?? 'var(--fg-muted)',
    }));
  if (pie.length === 0) return <EmptyChart>No data.</EmptyChart>;
  return (
    <>
      <RC height={230}>
        <PieChart>
          <Pie data={pie} dataKey="value" nameKey="name" cx="50%" cy="50%" innerRadius={58} outerRadius={88} paddingAngle={3} stroke="var(--bg-card)" strokeWidth={2}>
            {pie.map((d) => <Cell key={d.name} fill={d.fill} aria-label={`${d.name}: ${fmtAmtFull(d.value)}, ${fmtN(d.accountCount)} accounts`} />)}
          </Pie>
          <Tooltip
            contentStyle={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 8, boxShadow: '0 6px 18px rgba(0,0,0,0.18)' }}
          labelStyle={{ color: 'var(--fg)', fontWeight: 600, marginBottom: 2 }}
          itemStyle={{ color: 'var(--fg-soft)' }}
            formatter={(value: number, _name, payload) => [
              `${fmtAmtFull(value)} · ${fmtN((payload as { payload?: { accountCount?: number } }).payload?.accountCount ?? 0)} accts`,
              'Balance',
            ]}
          />
        </PieChart>
      </RC>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 10, marginTop: 8, justifyContent: 'center' }}>
        {pie.map((d) => (
          <span key={d.name} style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 12, color: 'var(--fg-soft)' }}>
            <span style={{ width: 8, height: 8, borderRadius: '50%', background: d.fill }} />
            {d.name}
          </span>
        ))}
      </div>
    </>
  );
}

const PALETTE = ['var(--c-1)', 'var(--c-2)', 'var(--c-3)', 'var(--c-4)', 'var(--c-5)', 'var(--c-6)', 'var(--c-7)'];

interface DistProps {
  data: { bucket: string; bucket_label: string; account_count: number }[];
}
export function BalanceDistribution({ data }: DistProps) {
  if (data.length === 0) return <EmptyChart>No data.</EmptyChart>;
  return (
    <RC height={250}>
      <BarChart data={data} margin={{ top: 4, right: 8, bottom: 8, left: 0 }}>
        <CartesianGrid strokeDasharray="2 4" stroke={GRID_STROKE} vertical={false} />
        {/* interval=0 forces a label on EVERY bucket; angled so the finer bands
            don't collide. */}
        <XAxis
          dataKey="bucket_label"
          tick={{ ...AXIS_TICK, fontSize: 10 }}
          tickLine={false}
          axisLine={false}
          interval={0}
          angle={-35}
          textAnchor="end"
          height={58}
        />
        <YAxis tick={AXIS_TICK} tickLine={false} axisLine={false} width={36} />
        <Tooltip
          contentStyle={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 8, boxShadow: '0 6px 18px rgba(0,0,0,0.18)' }}
          labelStyle={{ color: 'var(--fg)', fontWeight: 600, marginBottom: 2 }}
          itemStyle={{ color: 'var(--fg-soft)' }}
          formatter={(v: number) => [`${fmtN(v)} accts`, 'Accounts']}
        />
        <Bar dataKey="account_count" name="Accounts" radius={[4, 4, 0, 0]}>
          {data.map((_, i) => <Cell key={i} fill={PALETTE[i % PALETTE.length]} fillOpacity={0.85} />)}
        </Bar>
      </BarChart>
    </RC>
  );
}

interface TopAccountsProps {
  data: { account_id: number; label: string; balance: number }[];
}
export function TopAccountsBar({ data }: TopAccountsProps) {
  if (data.length === 0) return <EmptyChart>No data.</EmptyChart>;
  return (
    <RC height={Math.max(160, 22 * data.length + 20)}>
      <BarChart data={data} layout="vertical" margin={{ top: 4, right: 8, bottom: 0, left: 4 }}>
        <CartesianGrid strokeDasharray="2 4" stroke={GRID_STROKE} horizontal={false} />
        <XAxis type="number" tickFormatter={(v) => fmtAmt(String(v))} tick={AXIS_TICK} tickLine={false} axisLine={false} />
        <YAxis type="category" dataKey="label" width={140} tick={{ ...AXIS_TICK, fill: 'var(--fg-soft)' }} tickLine={false} axisLine={false} />
        <Tooltip
          contentStyle={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 8, boxShadow: '0 6px 18px rgba(0,0,0,0.18)' }}
          labelStyle={{ color: 'var(--fg)', fontWeight: 600, marginBottom: 2 }}
          itemStyle={{ color: 'var(--fg-soft)' }}
          formatter={(v: number) => [fmtAmtFull(v), 'Balance']}
        />
        <Bar dataKey="balance" name="Balance" radius={[0, 4, 4, 0]} fill="var(--c-1)" />
      </BarChart>
    </RC>
  );
}

interface InOutProps {
  data: { day: string; cr: number; dr: number }[];
}
export function InOutChart({ data }: InOutProps) {
  if (data.length === 0) return <EmptyChart>No data.</EmptyChart>;
  return (
    <RC daily height={210}>
      <BarChart data={data} margin={{ top: 4, right: 8, bottom: 0, left: 0 }}>
        <CartesianGrid strokeDasharray="2 4" stroke={GRID_STROKE} vertical={false} />
        <XAxis
          dataKey="day"
          tickFormatter={(d) => fmtDate(d)}
          tick={AXIS_TICK}
          tickLine={false}
          axisLine={false}
          minTickGap={20}
        />
        <YAxis tickFormatter={(v) => fmtAmt(String(v))} tick={AXIS_TICK} tickLine={false} axisLine={false} width={56} />
        <Tooltip
          contentStyle={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 8, boxShadow: '0 6px 18px rgba(0,0,0,0.18)' }}
          labelStyle={{ color: 'var(--fg)', fontWeight: 600, marginBottom: 2 }}
          itemStyle={{ color: 'var(--fg-soft)' }}
          formatter={(v: number, name) => [fmtAmtFull(v), name === 'cr' ? 'In' : 'Out']}
        />
        <Bar dataKey="cr" stackId="a" fill="var(--c-3)" radius={[3, 3, 0, 0]} />
        <Bar dataKey="dr" stackId="a" fill="var(--c-5)" radius={[3, 3, 0, 0]} />
      </BarChart>
    </RC>
  );
}

interface BalanceLineProps {
  data: { date: string; balance: number }[];
}
export function BalanceLine({ data }: BalanceLineProps) {
  if (data.length === 0) return <EmptyChart>No data.</EmptyChart>;
  return (
    <RC daily height={230}>
      <AreaChart data={data} margin={{ top: 4, right: 8, bottom: 0, left: 0 }}>
        <defs>
          <linearGradient id="gBalLine" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="var(--c-2)" stopOpacity={0.4} />
            <stop offset="100%" stopColor="var(--c-2)" stopOpacity={0} />
          </linearGradient>
        </defs>
        <CartesianGrid strokeDasharray="2 4" stroke={GRID_STROKE} vertical={false} />
        <XAxis
          dataKey="date"
          tickFormatter={(d) => fmtDate(d)}
          tick={AXIS_TICK}
          tickLine={false}
          axisLine={false}
          minTickGap={28}
        />
        <YAxis tickFormatter={(v) => fmtAmt(String(v))} tick={AXIS_TICK} tickLine={false} axisLine={false} width={60} />
        <Tooltip
          contentStyle={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 8, boxShadow: '0 6px 18px rgba(0,0,0,0.18)' }}
          labelStyle={{ color: 'var(--fg)', fontWeight: 600, marginBottom: 2 }}
          itemStyle={{ color: 'var(--fg-soft)' }}
          formatter={(v: number) => [fmtAmtFull(v), 'Balance']}
        />
        <Area type="monotone" dataKey="balance" stroke="var(--c-2)" strokeWidth={2} fill="url(#gBalLine)" dot={false} isAnimationActive={false} />
      </AreaChart>
    </RC>
  );
}

interface PriceLineProps {
  data: { day: string; avg_unit_price: string; sales: number }[];
}
export function PriceLine({ data }: PriceLineProps) {
  if (data.length === 0) return <EmptyChart>No data.</EmptyChart>;
  const series = data.map((d) => ({ day: d.day, price: parseFloat(d.avg_unit_price), sales: d.sales }));
  return (
    <RC daily height={230}>
      <LineChart data={series} margin={{ top: 4, right: 8, bottom: 0, left: 0 }}>
        <CartesianGrid strokeDasharray="2 4" stroke={GRID_STROKE} vertical={false} />
        <XAxis
          dataKey="day"
          tickFormatter={(d) => fmtDate(d)}
          tick={AXIS_TICK}
          tickLine={false}
          axisLine={false}
          minTickGap={28}
        />
        <YAxis tickFormatter={(v) => fmtAmt(String(v))} tick={AXIS_TICK} tickLine={false} axisLine={false} width={56} />
        <Tooltip
          contentStyle={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 8, boxShadow: '0 6px 18px rgba(0,0,0,0.18)' }}
          labelStyle={{ color: 'var(--fg)', fontWeight: 600, marginBottom: 2 }}
          itemStyle={{ color: 'var(--fg-soft)' }}
          formatter={(v: number, name) => name === 'price' ? [fmtAmtFull(v), 'Avg price'] : [fmtN(v), 'Sales']}
        />
        <Line type="monotone" dataKey="price" stroke="var(--c-1)" strokeWidth={2} dot={false} isAnimationActive={false} />
      </LineChart>
    </RC>
  );
}

function EmptyChart({ children }: { children: React.ReactNode }) {
  return <div className="state-empty" style={{ height: 230, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>{children}</div>;
}

/** Unstacked lines preserve negative fine-refund days without hiding other taxes. */
export function TaxRevenueTimeline({ data }: { data: ({ date: string } & Record<TaxKind, number>)[] }) {
  return <>
    <div className="chart-legend">{TAX_KINDS.map((kind, i) => <span key={kind} style={{ color: PALETTE[i] }}>● {TAX_LABELS[kind]}</span>)}</div>
    <RC daily height={260}>
      <LineChart data={data} margin={{ top: 8, right: 12, bottom: 0, left: 12 }}>
        <CartesianGrid strokeDasharray="2 4" stroke={GRID_STROKE} vertical={false} />
        <XAxis dataKey="date" tickFormatter={fmtDate} tick={AXIS_TICK} tickLine={false} axisLine={false} minTickGap={28} />
        <YAxis tickFormatter={v => fmtAmt(String(v))} tick={AXIS_TICK} tickLine={false} axisLine={false} width={60} />
        <Tooltip labelFormatter={d => fmtDate(String(d))} formatter={(v: number) => fmtAmtFull(v)}
          contentStyle={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 8 }} />
        {TAX_KINDS.map((kind, i) => <Line key={kind} dataKey={kind} name={TAX_LABELS[kind]} stroke={PALETTE[i]} strokeWidth={2} dot={false} isAnimationActive={false} />)}
      </LineChart>
    </RC>
  </>;
}
