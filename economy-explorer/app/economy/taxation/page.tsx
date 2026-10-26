import { Suspense } from 'react';
import Link from 'next/link';
import { MetricCard, MetricRow, Money, Num, PageHeader, Pct, Section, SkeletonMetricRow, TableWrap, When } from '@democracycraft/design-system';
import { RangePicker } from '@democracycraft/design-system/next';
import { buildMetadata } from '@/lib/metadata';
import { parseMoney, toNumber } from '@/lib/money';
import { parseRange, previousRange, rangeDays, toDay, type DayRange } from '@/lib/range';
import { change } from '@/lib/services/economy';
import { getTaxationDashboard } from '@/lib/services/taxation';
import { getTaxationRates } from '@/lib/services/taxationRates';
import { TaxRevenueTimeline } from '@/components/charts/AreaTimeline';
import { attempt, CoverageNote, NotReady, notReady, SectionError, SectionSkeleton } from '../_components/coverage';

export const dynamic = 'force-dynamic';
export async function generateMetadata() {
  return buildMetadata({ title: 'Taxation', description: 'Tax collections, fines and government revenue on {server}.', path: '/economy/taxation' });
}

const PRESETS = ['7d', '30d', '90d', '1y'] as const;

export default async function TaxationPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const parsed = parseRange(await searchParams, { defaultPreset: '30d' });
  const range = parsed.range;
  return <>
    <PageHeader title="Taxation" subtitle="Tax and fine revenue received by government accounts. Totals are public; individual payments, payers and fine reasons stay private." />
    <RangePicker value={parsed.value} presets={PRESETS} allowCompare={false} max={toDay(new Date())} />
    <Suspense fallback={<SkeletonMetricRow count={4} />}><Totals range={range} /></Suspense>
    <Suspense fallback={<SectionSkeleton title="Revenue by day" chartHeight={300} />}><Daily range={range} label={parsed.label} /></Suspense>
    <div className="econ-grid">
      <Suspense fallback={<SectionSkeleton title="Revenue by type" rows={5} />}><ByType range={range} /></Suspense>
      <Suspense fallback={<SectionSkeleton title="Where it lands" rows={5} />}><ByAccount range={range} /></Suspense>
    </div>
    <Suspense fallback={<SectionSkeleton title="Rates in effect" rows={6} />}><Rates /></Suspense>
  </>;
}

async function Totals({ range }: { range: DayRange }) {
  const previous = previousRange(range);
  const [current, before] = await Promise.all([
    attempt('taxation', () => getTaxationDashboard(range)),
    attempt('taxation previous', () => getTaxationDashboard(previous)),
  ]);
  const t = current.ok && !notReady(current.value.coverage) ? current.value.totals : null;
  const p = before.ok && before.value.coverage.covered ? before.value.totals : null;
  const n = (s: string) => toNumber(parseMoney(s));
  const deltaLabel = p ? `vs previous ${rangeDays(range)} days` : t ? 'previous period not ready yet' : undefined;
  return <MetricRow label="Tax revenue summary">
    <MetricCard label="Collected" value={t ? <Money value={t.collected} compact /> : '—'} meta="Taxes and fines"
      delta={t && p ? change(n(t.collected), n(p.collected)) : t ? null : undefined} deltaLabel={deltaLabel} />
    <MetricCard label="Fine refunds" value={t ? <Money value={t.refunded} compact /> : '—'} meta="Revoked fines paid back"
      delta={t && p ? change(n(t.refunded), n(p.refunded)) : t ? null : undefined} deltaLabel={deltaLabel} deltaTone="inverse" />
    <MetricCard label="Net revenue" value={t ? <Money value={t.net} compact /> : '—'} meta="Collected minus refunds"
      delta={t && p ? change(n(t.net), n(p.net)) : t ? null : undefined} deltaLabel={deltaLabel} />
    <MetricCard label="Receiving accounts" value={<Num value={t ? (current.ok ? current.value.accounts.length : null) : null} />} meta="Government accounts with revenue" />
  </MetricRow>;
}

async function Daily({ range, label }: { range: DayRange; label: string }) {
  const result = await attempt('taxation', () => getTaxationDashboard(range));
  if (!result.ok) return <SectionError title="Revenue by day" />;
  const { daily, coverage } = result.value;
  return <Section title="Revenue by day" description={`Net revenue per day and type, ${label.toLowerCase()}. A refund counts on the day it settles, so a day can be negative.`}
    actions={<CoverageNote coverage={coverage} />}>
    {notReady(coverage) ? <NotReady height={300} /> : <TaxRevenueTimeline data={daily} />}
  </Section>;
}

async function ByType({ range }: { range: DayRange }) {
  const result = await attempt('taxation', () => getTaxationDashboard(range));
  if (!result.ok) return <SectionError title="Revenue by type" />;
  const { byType, totals } = result.value;
  const net = parseMoney(totals.net);
  return <Section title="Revenue by type" description="Personal and corporate balance taxes, source income tax, ChestShop sales tax and recorded fines. Property taxes and other government transfers are not included.">
    <TableWrap label="Revenue by type">
      <table className="data-table econ-table" aria-label="Revenue by type">
        <thead><tr><th scope="col">Type</th><th scope="col" className="amount" data-secondary="true">Collected</th><th scope="col" className="amount" data-secondary="true">Refunded</th><th scope="col" className="amount">Net</th><th scope="col" className="amount">Share</th></tr></thead>
        <tbody>{byType.map((row) => <tr key={row.kind}>
          <th scope="row">{row.label}</th>
          <td className="amount" data-secondary="true"><Money value={row.collected} /></td>
          <td className="amount" data-secondary="true"><Money value={row.refunded} /></td>
          <td className="amount"><Money value={row.net} /></td>
          <td className="amount"><Pct value={net > 0n ? toNumber(parseMoney(row.net)) / toNumber(net) : null} /></td>
        </tr>)}</tbody>
      </table>
    </TableWrap>
  </Section>;
}

async function ByAccount({ range }: { range: DayRange }) {
  const result = await attempt('taxation', () => getTaxationDashboard(range));
  if (!result.ok) return <SectionError title="Where it lands" />;
  const { accounts } = result.value;
  return <Section title="Where it lands" description="Revenue per receiving government account, including archived ones.">
    <TableWrap label="Where it lands">
      <table className="data-table econ-table" aria-label="Where it lands">
        <thead><tr><th scope="col">Government account</th><th scope="col" className="amount" data-secondary="true">Collected</th><th scope="col" className="amount" data-secondary="true">Refunded</th><th scope="col" className="amount">Net</th></tr></thead>
        <tbody>
          {accounts.length === 0 && <tr><td colSpan={4} className="muted">No tax or fine revenue in this period.</td></tr>}
          {accounts.map((row) => <tr key={row.id}>
            <th scope="row">{row.name}</th>
            <td className="amount" data-secondary="true"><Money value={row.collected} /></td>
            <td className="amount" data-secondary="true"><Money value={row.refunded} /></td>
            <td className="amount"><Money value={row.net} /></td>
          </tr>)}
        </tbody>
      </table>
    </TableWrap>
  </Section>;
}

const percent = (value: string) => `${(Number(value) * 100).toLocaleString('en-US', { maximumFractionDigits: 4 })}%`;

async function Rates() {
  const result = await attempt('tax rates', getTaxationRates);
  if (!result.ok) return <SectionError title="Rates in effect" />;
  const rates = result.value;
  return <Section title="Rates in effect" description={<>The latest configuration published by Treasury, whatever period is selected above. Corporate and ChestShop rates are not published here; unavailable does not mean zero. <Link href="/docs/guides/tax">How taxes work</Link></>}>
    <div className="econ-grid">
      <Section level={3} title="Personal balance tax" description={rates.balanceTax
        ? <>{rates.balanceTax.enabled ? 'Enabled' : 'Disabled'} · published <When value={rates.balanceTax.updatedAt} />. A weekly rate on the whole balance at the highest floor reached, prorated at login.</>
        : 'Live rates are unavailable right now.'}>
        {rates.balanceTax && <TableWrap label="Personal balance tax">
          <table className="data-table econ-table tax-rates-table" aria-label="Personal balance tax">
            <thead><tr><th scope="col">Balance floor</th><th scope="col" className="amount">Weekly rate</th></tr></thead>
            <tbody>{rates.balanceTax.brackets.map((row) => <tr key={row.floor}><td><Money value={row.floor} /> and above</td><td className="amount num">{percent(row.weeklyRate)}</td></tr>)}</tbody>
          </table>
        </TableWrap>}
      </Section>
      <Section level={3} title="Source / income tax" description={rates.sourceIncomeTax
        ? <>{rates.sourceIncomeTax.enabled ? 'Enabled' : 'Disabled'} · published <When value={rates.sourceIncomeTax.updatedAt} />. Default rate {percent(rates.sourceIncomeTax.defaultRate)} for sources without an override.</>
        : 'Live rates are unavailable right now.'}>
        {rates.sourceIncomeTax && <TableWrap label="Source / income tax">
          <table className="data-table econ-table tax-rates-table" aria-label="Source / income tax">
            <thead><tr><th scope="col">Income source</th><th scope="col" className="amount">Rate override</th></tr></thead>
            <tbody>
              {rates.sourceIncomeTax.pluginRates.length === 0 && <tr><td colSpan={2} className="muted">No source overrides.</td></tr>}
              {rates.sourceIncomeTax.pluginRates.map((row) => <tr key={row.plugin}><td>{row.plugin}</td><td className="amount num">{percent(row.rate)}</td></tr>)}
            </tbody>
          </table>
        </TableWrap>}
      </Section>
    </div>
  </Section>;
}
