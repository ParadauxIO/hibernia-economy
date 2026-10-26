import { MetricCard, MetricRow, Money, PageHeader, Section } from '@democracycraft/design-system';
import { DataTable, type Column } from '@democracycraft/design-system/table';
import { Donut } from '@/components/charts/AreaTimeline';
import { buildMetadata } from '@/lib/metadata';
import { parseMoney, toDbString } from '@/lib/money';
import { getRevenue } from '@/lib/services/government';
import type { FlowLine } from '@/lib/services/governmentModel';
import { linesByUnit, type UnitRow } from '../byUnit';
import { payerColumns } from '../columns';
import { Freshness, GovControls, ScopeNote, govPage, isGate, type SearchParams } from '../shared';

export const dynamic = 'force-dynamic';

export async function generateMetadata() {
  return buildMetadata({ title: 'Government revenue', description: 'Government revenue in your scope on {server}.', path: '/gov/revenue' });
}

const sourceColumns: Column<FlowLine>[] = [
  { key: 'source', header: 'Source', cell: (l) => l.label },
  { key: 'amount', header: 'Received', numeric: true, cell: (l) => <Money value={l.amount} /> },
];

const GROUPS = [{ key: 'taxes', label: 'Taxes' }, { key: 'fines', label: 'Fines' }, { key: 'other', label: 'Other' }];
const group = (l: FlowLine) => (l.category === 'taxes' ? 'taxes' : l.category === 'fines' ? 'fines' : 'other');

const unitColumns: Column<UnitRow>[] = [
  { key: 'unit', header: 'Unit', cell: (r) => r.name },
  ...GROUPS.map((g) => ({ key: g.key, header: g.label, numeric: true, secondary: true, cell: (r: UnitRow) => <Money value={r.values[g.key] ?? '0.00'} /> })),
  { key: 'total', header: 'Total', numeric: true, cell: (r) => <Money value={r.total} /> },
];

export default async function GovRevenuePage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const page = await govPage(await searchParams, '/gov/revenue');
  if (isGate(page)) return page.gate;
  const r = await getRevenue(page.scope, page.selection.ids, page.range.range);
  const b = r.flows.breakdown;
  const other = toDbString(parseMoney(b.totalRevenue) - parseMoney(b.taxes) - parseMoney(b.fines));
  const byUnit = linesByUnit(page.scope, b.revenue, group);
  const finePayers = r.finePayers.filter((p) => p.account_id > 0 || p.player_uuid);

  return <>
    <PageHeader title="Revenue" subtitle={`${page.selection.label} · ${page.range.label}`} />
    <GovControls page={page} />
    <ScopeNote page={page} />
    <MetricRow label="Revenue summary">
      <MetricCard label="Revenue" value={<Money value={b.totalRevenue} />} info="Money received from players, firms and the system. Transfers from other government accounts are listed separately below." />
      <MetricCard label="Taxes" value={<Money value={b.taxes} />} />
      <MetricCard label="Fines" value={<Money value={b.fines} />} info="Fines paid into these accounts. When a fine is revoked, its refund shows under spending." />
      <MetricCard label="Other" value={<Money value={other} />} meta={<>From government accounts <Money value={b.governmentIn} compact /></>} />
    </MetricRow>

    <div className="gov-grid-2">
      <Section title="By source" description={<>Taxes by kind, fines, then other inflows by category. <Freshness coverage={r.flows.coverage} /></>}>
        <DataTable id="gov-revenue-sources" label="Revenue by source" columns={sourceColumns} rows={b.revenue} rowKey={(l) => l.key}
          empty={<p className="dt-empty-text">{r.flows.coverage.covered ? 'No revenue in this period.' : 'Preparing history.'}</p>} />
      </Section>
      <Section title="Share">
        <Donut label="Revenue by source" items={b.revenue.map((l) => ({ key: l.key, label: l.label, value: l.amount }))} />
      </Section>
    </div>

    {byUnit.length > 1 && <Section title="By unit" description="Each account counts towards its own unit." divider>
      <DataTable id="gov-revenue-units" label="Revenue by unit" columns={unitColumns} rows={byUnit} rowKey={(u) => u.key} />
    </Section>}

    <Section title="Top tax payers" divider description={<>Players and firms who paid the most tax into these accounts{r.topRange.capped ? <>, last 31 days of the range ({r.topRange.from} to {r.topRange.to})</> : null}. Read from the ledger.</>}>
      <DataTable id="gov-tax-payers" label="Top tax payers" columns={payerColumns} rows={r.taxPayers} rowKey={(p) => p.account_id}
        empty={<p className="dt-empty-text">No tax was paid into these accounts in this period.</p>} />
    </Section>

    <Section title="Top fine payers" divider description="Fines not revoked, by the account that paid them, over the whole range.">
      <DataTable id="gov-fine-payers" label="Top fine payers" rows={finePayers} rowKey={(p) => `${p.account_id}-${p.player_uuid ?? ''}`}
        columns={payerColumns}
        empty={<p className="dt-empty-text">No fines were paid into these accounts in this period.</p>} />
    </Section>
  </>;
}
