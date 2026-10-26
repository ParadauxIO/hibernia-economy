import { MetricCard, MetricRow, Money, PageHeader, Section } from '@democracycraft/design-system';
import { DataTable, type Column } from '@democracycraft/design-system/table';
import { BarChart } from '@/components/charts/AreaTimeline';
import { buildMetadata } from '@/lib/metadata';
import { getSpending } from '@/lib/services/government';
import type { FlowLine } from '@/lib/services/governmentModel';
import type { CounterpartyRow } from '@/lib/sql/government';
import { linesByUnit, type UnitRow } from '../byUnit';
import { officialColumns } from '../columns';
import { Freshness, GovControls, PartyRef, RawNote, ScopeNote, govPage, isGate, type SearchParams } from '../shared';

export const dynamic = 'force-dynamic';

export async function generateMetadata() {
  return buildMetadata({ title: 'Government spending', description: 'Government spending in your scope on {server}.', path: '/gov/spending' });
}

const categoryColumns: Column<FlowLine>[] = [
  { key: 'category', header: 'Category', cell: (l) => l.label },
  { key: 'amount', header: 'Paid out', numeric: true, cell: (l) => <Money value={l.amount} /> },
];

const recipientColumns: Column<CounterpartyRow>[] = [
  { key: 'recipient', header: 'Recipient', cell: (c) => <PartyRef row={{ account_id: c.counterparty_id, display_name: c.display_name, account_type: c.account_type, owner_uuid: c.owner_uuid, owner_name: c.owner_name, firm_name: c.firm_name }} /> },
  { key: 'n', header: 'Transactions, both ways', numeric: true, secondary: true, cell: (c) => c.txn_count.toLocaleString('en-US') },
  { key: 'in', header: 'Received from them', numeric: true, secondary: true, optional: true, cell: (c) => <Money value={c.inflow} /> },
  { key: 'out', header: 'Paid to them', numeric: true, cell: (c) => <Money value={c.outflow} /> },
];

const unitColumns: Column<UnitRow>[] = [
  { key: 'unit', header: 'Unit', cell: (r) => r.name },
  { key: 'total', header: 'Spending', numeric: true, cell: (r) => <Money value={r.total} /> },
];

export default async function GovSpendingPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const page = await govPage(await searchParams, '/gov/spending');
  if (isGate(page)) return page.gate;
  const s = await getSpending(page.scope, page.selection.ids, page.range.range);
  const b = s.flows.breakdown;
  const byUnit = linesByUnit(page.scope, b.spending, () => 'all');
  const external = b.recipients.filter((r) => r.type !== 'GOVERNMENT');

  return <>
    <PageHeader title="Spending" subtitle={`${page.selection.label} · ${page.range.label}`} />
    <GovControls page={page} />
    <ScopeNote page={page} />
    <MetricRow label="Spending summary">
      <MetricCard label="Spending" value={<Money value={b.totalSpending} />} info="Money paid to players, firms and the system." />
      {external.map((r) => <MetricCard key={r.type} label={`To ${r.label.toLowerCase()}`} value={<Money value={r.amount} />} />)}
      <MetricCard label="To other government accounts" value={<Money value={b.governmentOut} />} info="Transfers to government accounts, including other departments. Internal, so not counted as spending." />
    </MetricRow>

    <div className="gov-grid-2">
      <Section title="By category" description={<>Outflows to outside government. <Freshness coverage={s.flows.coverage} /></>}>
        <DataTable id="gov-spending-categories" label="Spending by category" columns={categoryColumns} rows={b.spending} rowKey={(l) => l.key}
          empty={<p className="dt-empty-text">{s.flows.coverage.covered ? 'No spending in this period.' : 'Preparing history.'}</p>} />
      </Section>
      <Section title="By recipient type" description="Government transfers are internal and shown separately.">
        <BarChart label="Spending by recipient type" x="label" horizontal height={200}
          data={b.recipients.map((r) => ({ label: r.type === 'GOVERNMENT' ? `${r.label} (internal)` : r.label, amount: r.amount }))}
          series={[{ key: 'amount', label: 'Paid out', color: 'var(--neg)' }]} />
      </Section>
    </div>

    {byUnit.length > 1 && <Section title="By unit" divider>
      <DataTable id="gov-spending-units" label="Spending by unit" columns={unitColumns} rows={byUnit} rowKey={(u) => u.key} />
    </Section>}

    <Section title="Largest recipients" divider description={<>Accounts outside the selection, whole months {s.recipients.months.from.slice(0, 7)} to {s.recipients.months.to.slice(0, 7)}. <Freshness coverage={s.recipients.coverage} /></>}>
      <DataTable id="gov-recipients" label="Largest recipients" columns={recipientColumns} rows={s.recipients.rows} rowKey={(c) => c.counterparty_id}
        empty={<p className="dt-empty-text">{s.recipients.coverage.covered ? 'Nobody was paid in this period.' : 'Preparing history.'}</p>} />
    </Section>

    <Section title="Spending by official" divider description={<>Who initiated and who approved each payment out, from the ledger. Automated payouts show the plugin’s identity. <RawNote range={s.officials.range} /></>}>
      <DataTable id="gov-officials" label="Spending by official" columns={officialColumns('Paid out')} rows={s.officials.rows}
        rowKey={(o) => `${o.initiator_uuid ?? ''}-${o.authorizer_uuid ?? ''}`} empty={<p className="dt-empty-text">No payments out in this period.</p>} />
    </Section>
  </>;
}
