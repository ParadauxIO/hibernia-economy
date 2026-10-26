import Link from 'next/link';
import type { Route } from 'next';
import { MetricCard, MetricRow, Money, PageHeader, Section } from '@democracycraft/design-system';
import { DataTable } from '@democracycraft/design-system/table';
import { BarChart, TimeSeriesChart } from '@/components/charts/AreaTimeline';
import { buildMetadata } from '@/lib/metadata';
import { getOverview } from '@/lib/services/government';
import { movementColumns } from './columns';
import { Freshness, GovControls, RawNote, ScopeNote, govPage, isGate, type SearchParams } from './shared';

export const dynamic = 'force-dynamic';

export async function generateMetadata() {
  return buildMetadata({ title: 'Government', description: 'The government workspace on {server}.', path: '/gov' });
}

const monthLabel = (m: string) => new Date(`${m}T00:00:00Z`).toLocaleDateString('en-US', { month: 'short', year: 'numeric', timeZone: 'UTC' });

export default async function GovOverviewPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const page = await govPage(await searchParams, '/gov');
  if (isGate(page)) return page.gate;
  const { selection, range } = page;
  const o = await getOverview(page.scope, selection, range.range, page.carry);
  const b = o.flows.breakdown;

  return <>
    <PageHeader title="Overview" subtitle={`${selection.label} · ${range.label}`} />
    <GovControls page={page} />
    <ScopeNote page={page} />

    <MetricRow label="Treasury summary">
      <MetricCard label="Treasury total" value={<Money value={o.treasury} />} meta={`${o.accountCount} account${o.accountCount === 1 ? '' : 's'}`}
        info="The sum of the selected accounts’ balances, as the ledger has them. Accounts that minted money hold negative balances and are included." />
      <MetricCard label="Net flow" value={<Money value={o.net} sign="exceptZero" tone="auto" />} meta={range.label}
        info="Everything in minus everything out over the period, transfers between government accounts included. It equals the change in the treasury total." />
      <MetricCard label="Revenue" value={<Money value={b.totalRevenue} />} meta={<>Taxes <Money value={b.taxes} compact /> · fines <Money value={b.fines} compact /></>}
        info="Money received from players, firms and the system. Transfers from other government accounts are not revenue." />
      <MetricCard label="Spending" value={<Money value={b.totalSpending} />}
        info="Money paid to players, firms and the system. Transfers to other government accounts are not spending." />
    </MetricRow>

    <Section title="Revenue and spending by month" description={<>Excludes transfers between government accounts. <Freshness coverage={o.flows.coverage} /></>}>
      <BarChart label="Revenue and spending by month" x="month" data={o.months.map((m) => ({ month: monthLabel(m.month), revenue: m.revenue, spending: m.spending }))}
        series={[{ key: 'revenue', label: 'Revenue', color: 'var(--pos)' }, { key: 'spending', label: 'Spending', color: 'var(--neg)' }]}
        empty={o.flows.coverage.covered ? 'No revenue or spending in this period.' : 'Preparing history.'} />
    </Section>

    <Section title="Balance over time" description={<>Closing balance of the selected accounts each day (UTC). <Freshness coverage={o.balance.coverage} /></>} divider>
      <TimeSeriesChart label="Treasury balance by day" data={o.balance.data.map((p) => ({ date: p.day, balance: p.balance }))}
        series={[{ key: 'balance', label: 'Balance', color: 'var(--cat-1)' }]} kind="line"
        empty={o.balance.coverage.covered ? 'No balance history.' : 'Preparing history.'} />
    </Section>

    <Section title="Largest movements" description={<RawNote range={o.movements.range} />} divider>
      <DataTable id="gov-movements" label="Largest movements" columns={movementColumns} rows={o.movements.rows} rowKey={(m) => `${m.txn_id}-${m.account_id}`}
        empty={<p className="dt-empty-text">No transactions in this period.</p>} />
    </Section>

    {o.units.length > 0 && <Section title={selection.key === 'all' ? 'Units' : 'Sub-units'} description="Balance and net flow over the period, from the accounts you can read." divider>
      <ul className="gov-units">
        {o.units.map((u) => <li key={u.key}>
          <Link className="gov-unit-card" href={u.href as Route} prefetch={false}>
            <span className="gov-unit-name">{u.name}</span>
            <span className="gov-unit-balance"><Money value={u.balance} /></span>
            <span className="gov-unit-meta">Net <Money value={u.net} sign="exceptZero" tone="auto" /> · {u.partial ? `partial view: ${u.readable} of ${u.total} accounts` : `${u.readable} account${u.readable === 1 ? '' : 's'}`}</span>
          </Link>
        </li>)}
      </ul>
    </Section>}
  </>;
}
