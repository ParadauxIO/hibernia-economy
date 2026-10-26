import Link from 'next/link';
import { MetricCard, MetricRow, Money, Num, PageHeader, Section, Segmented, Tag, When } from '@democracycraft/design-system';
import { DataTable, TablePager, type Column, type LinkComponent } from '@democracycraft/design-system/table';
import { BarChart } from '@/components/charts/AreaTimeline';
import { buildMetadata } from '@/lib/metadata';
import { getPayroll } from '@/lib/services/government';
import type { PayrollRun } from '@/lib/services/governmentModel';
import type { SalaryRecipientRow } from '@/lib/sql/government';
import { Freshness, GovControls, PartyRef, RawNote, STATUS_TONE, ScopeNote, govPage, isGate, type SearchParams } from '../shared';

export const dynamic = 'force-dynamic';

export async function generateMetadata() {
  return buildMetadata({ title: 'Government payroll', description: 'Government payroll in your scope on {server}.', path: '/gov/payroll' });
}

const RouterLink: LinkComponent = Link;
const PAGE = 50;
const STATUS_LABEL: Record<PayrollRun['status'], string> = { ok: 'On time', late: 'Late', missed: 'After missed runs', duplicate: 'Possible duplicate', resumed: 'Resumed after a pause' };

const runColumns: Column<PayrollRun>[] = [
  { key: 'start', header: 'Run', cell: (r) => <When value={r.start} time relative={false} /> },
  { key: 'status', header: 'Status', cell: (r) => <Tag tone={STATUS_TONE[r.status]}>{r.status === 'missed' ? `${r.missedBefore} missed before` : STATUS_LABEL[r.status]}</Tag> },
  { key: 'gap', header: 'Since previous', numeric: true, secondary: true, cell: (r) => r.gapMinutes === null ? '—' : `${r.gapMinutes} min` },
  { key: 'payments', header: 'Payments', numeric: true, secondary: true, cell: (r) => r.payments.toLocaleString('en-US') },
  { key: 'total', header: 'Paid', numeric: true, cell: (r) => <Money value={r.total} /> },
];

const recipientColumns: Column<SalaryRecipientRow>[] = [
  { key: 'who', header: 'Recipient', cell: (r) => <PartyRef row={r} /> },
  { key: 'n', header: 'Payments', numeric: true, secondary: true, cell: (r) => r.payments.toLocaleString('en-US') },
  { key: 'last', header: 'Last paid', secondary: true, cell: (r) => <When value={r.last_paid} /> },
  { key: 'total', header: 'Received', numeric: true, cell: (r) => <Money value={r.total} /> },
];

export default async function GovPayrollPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const sp = await searchParams;
  const page = await govPage(sp, '/gov/payroll');
  if (isGate(page)) return page.gate;
  const p = await getPayroll(page.scope, page.selection.ids, page.range.range);
  const a = p.analysis;
  const view = sp.runs === 'all' ? 'all' : 'issues';
  const runs = [...a.runs].reverse().filter((r) => view === 'all' || r.status !== 'ok');
  const totalPages = Math.max(1, Math.ceil(runs.length / PAGE));
  const current = Math.min(Math.max(1, Number(sp.rpage) || 1), totalPages);
  const base = `/gov/payroll?${new URLSearchParams(page.extra).toString()}`;
  const runsHref = (v: string, n = 1) => `${base}&runs=${v}${n > 1 ? `&rpage=${n}` : ''}`;

  return <>
    <PageHeader title="Payroll" subtitle={`${page.selection.label} · ${page.range.label}`} />
    <GovControls page={page} />
    <ScopeNote page={page} />
    {p.wagePlugins.length === 0 && p.coverage.covered
      ? <p className="muted">These accounts paid no wages in this period.</p>
      : <>
        <MetricRow label="Payroll summary">
          <MetricCard label="Wages paid" value={<Money value={p.total} />} meta={page.range.label} />
          <MetricCard label="Payments" value={<Num value={p.payments} />} />
          <MetricCard label="Runs" value={<Num value={a.runs.length} />} meta={a.cadenceMinutes ? `about every ${a.cadenceMinutes} min` : 'cadence not established'} />
          <MetricCard label="Missed runs" value={<Num value={a.missed} />} info="A gap between runs longer than one and a half times the usual cadence counts the runs that should have happened in it. Gaps of more than twelve cadences are pauses and are not counted." />
          <MetricCard label="Possible duplicates" value={<Num value={a.duplicates} />} info="A run that started less than half a cadence after the previous one." meta={a.pauses ? `${a.pauses} pause${a.pauses === 1 ? '' : 's'} not counted as missed` : undefined} />
        </MetricRow>

        <Section title="Wages by day" description={<>Paid by the selected accounts. <Freshness coverage={p.coverage} /></>}>
          <BarChart label="Wages paid by day" x="day" xFormat="day" data={p.days.map((d) => ({ day: d.day, total: d.total }))}
            series={[{ key: 'total', label: 'Wages', color: 'var(--cat-3)' }]} empty={p.coverage.covered ? 'No wages in this period.' : 'Preparing history.'} />
        </Section>

        <Section title="Run history" divider description={<>Payouts grouped into runs. <RawNote range={p.rawRange} /></>}
          actions={<Segmented label="Runs shown" value={view} items={[{ value: 'issues', label: 'Problems', href: runsHref('issues') }, { value: 'all', label: 'All runs', href: runsHref('all') }]} />}>
          <DataTable id="gov-payroll-runs" label="Payroll runs" columns={runColumns} rows={runs.slice((current - 1) * PAGE, current * PAGE)} rowKey={(r) => r.start}
            empty={<p className="dt-empty-text">{view === 'issues' ? 'No missed, late or duplicated runs.' : 'No payroll runs in this window.'}</p>} />
          <TablePager page={current} totalPages={totalPages} hrefFor={(n) => runsHref(view, n)} link={RouterLink} />
        </Section>

        <Section title="Recipients" divider description={<>Who received wages, largest first. <RawNote range={p.rawRange} /></>}>
          <DataTable id="gov-payroll-recipients" label="Payroll recipients" columns={recipientColumns} rows={p.recipients} rowKey={(r) => r.account_id}
            empty={<p className="dt-empty-text">No recipients in this window.</p>} />
        </Section>
      </>}
  </>;
}
