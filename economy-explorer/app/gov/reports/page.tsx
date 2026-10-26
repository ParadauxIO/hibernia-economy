import Link from 'next/link';
import type { Route } from 'next';
import { DescriptionList, MetricCard, MetricRow, Money, Num, PageHeader, Section } from '@democracycraft/design-system';
import { DataTable, type Column } from '@democracycraft/design-system/table';
import { buildMetadata } from '@/lib/metadata';
import { getMonthlyReport, reportMonths } from '@/lib/services/government';
import type { FlowLine } from '@/lib/services/governmentModel';
import { movementColumns } from '../columns';
import { Freshness, ScopeNote, govPage, isGate, type SearchParams } from '../shared';
import { UnitSelect } from '../UnitSelect';
import { PrintButton } from './PrintButton';

export const dynamic = 'force-dynamic';

export async function generateMetadata() {
  return buildMetadata({ title: 'Government reports', description: 'Monthly government reports on {server}.', path: '/gov/reports' });
}

const lineColumns = (label: string): Column<FlowLine>[] => [
  { key: 'item', header: 'Category', cell: (l) => l.label },
  { key: 'amount', header: label, numeric: true, cell: (l) => <Money value={l.amount} /> },
];

const monthName = (m: string) => new Date(`${m}T00:00:00Z`).toLocaleDateString('en-US', { month: 'long', year: 'numeric', timeZone: 'UTC' });

export default async function GovReportsPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const sp = await searchParams;
  const page = await govPage(sp, '/gov/reports');
  if (isGate(page)) return page.gate;
  const months = reportMonths(12);
  const requested = typeof sp.month === 'string' ? `${sp.month.slice(0, 7)}-01` : null;
  const month = requested && months.includes(requested) ? requested : (months[1] ?? months[0]!);
  const r = await getMonthlyReport(page.scope, page.selection.ids, month);
  const b = r.flows.breakdown;
  const unitParam = page.selection.key !== 'all' ? `&unit=${encodeURIComponent(page.selection.key)}` : '';
  const options = [
    { value: 'all', label: 'All units', depth: 0 },
    ...page.scope.units.map((u) => ({ value: u.slug, label: u.name, depth: u.depth })),
    ...(page.scope.unassigned.length ? [{ value: 'unassigned', label: 'Unassigned', depth: 0 }] : []),
  ];

  return <div className="gov-report">
    <PageHeader title={`${monthName(month)} report`} subtitle={`${page.selection.label} · internal to the government workspace`} />
    <div className="gov-report-head">
      <div className="gov-controls">
        <UnitSelect value={page.selection.key} options={options} />
        <nav aria-label="Report month" className="gov-inline-actions">
          {months.slice(0, 6).map((m) => <Link key={m} href={`/gov/reports?month=${m.slice(0, 7)}${unitParam}` as Route} prefetch={false}
            className="btn btn-quiet" aria-current={m === month ? 'page' : undefined}>{monthName(m)}</Link>)}
        </nav>
      </div>
      <div className="gov-inline-actions">
        <PrintButton />
        <a className="btn btn-quiet" href={`/gov/reports/csv?month=${month.slice(0, 7)}${unitParam}`} download>Download CSV</a>
      </div>
    </div>
    <ScopeNote page={page} />
    <p className="muted small"><Freshness coverage={r.coverage} /></p>

    <MetricRow label="Balances">
      <MetricCard label="Opening balance" value={r.opening === null ? 'Preparing history' : <Money value={r.opening} />} meta={`Start of ${r.range.from}`} />
      <MetricCard label="Closing balance" value={r.closing === null ? 'Preparing history' : <Money value={r.closing} />} meta={`End of ${r.range.to}`} />
      <MetricCard label="Net flow" value={<Money value={r.flows.net} sign="exceptZero" tone="auto" />} />
    </MetricRow>

    <div className="gov-grid-2">
      <Section title="Revenue by category" description={<>Total <Money value={b.totalRevenue} /></>}>
        <DataTable id="report-revenue" label="Revenue by category" columns={lineColumns('Received')} rows={b.revenue} rowKey={(l) => l.key}
          empty={<p className="dt-empty-text">No revenue.</p>} />
      </Section>
      <Section title="Spending by category" description={<>Total <Money value={b.totalSpending} /></>}>
        <DataTable id="report-spending" label="Spending by category" columns={lineColumns('Paid out')} rows={b.spending} rowKey={(l) => l.key}
          empty={<p className="dt-empty-text">No spending.</p>} />
      </Section>
    </div>

    <Section title="Government transfers" divider>
      <DescriptionList columns={2} items={[
        { label: 'From government accounts', value: <Money value={b.governmentIn} /> },
        { label: 'To government accounts', value: <Money value={b.governmentOut} /> },
      ]} />
    </Section>

    <Section title="Top movements" divider>
      <DataTable id="report-movements" label="Top movements" columns={movementColumns} rows={r.movements} rowKey={(m) => `${m.txn_id}-${m.account_id}`}
        empty={<p className="dt-empty-text">No transactions this month.</p>} />
    </Section>

    <Section title="Fines" divider>
      <MetricRow label="Fines summary">
        <MetricCard label="Issued" value={<Num value={r.fines.count} />} />
        <MetricCard label="Collected" value={<Money value={r.fines.total} />} meta="Not revoked" />
        <MetricCard label="Revoked" value={<Num value={r.fines.revoked} />} />
        <MetricCard label="Issuing officials" value={<Num value={r.fines.issuers} />} />
      </MetricRow>
    </Section>
  </div>;
}
