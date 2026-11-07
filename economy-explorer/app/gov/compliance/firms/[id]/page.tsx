import { notFound } from 'next/navigation';
import { DescriptionList, MetricCard, MetricRow, Money, PageHeader, Section, Tag, When } from '@democracycraft/design-system';
import { BackLink, EntityLink, RangePicker } from '@democracycraft/design-system/next';
import { DataTable, type Column } from '@democracycraft/design-system/table';
import { BarChart } from '@/components/charts/AreaTimeline';
import { buildMetadata } from '@/lib/metadata';
import { parseRange } from '@/lib/range';
import { utcDay } from '@/lib/rollup/days';
import { complianceAccess, getFirmInvestigation, LARGE_AMOUNT, ROUND_UNIT, type FirmInvestigation } from '@/lib/services/governmentCompliance';
import type { BalanceAnomaly } from '@/lib/services/governmentModel';
import type { CounterpartyRow } from '@/lib/sql/government';
import { movementColumns } from '../../../columns';
import { GovGate } from '../../../GovGate';
import { AccountRef, Freshness, PartyRef, PlayerRef, RawNote, type SearchParams } from '../../../shared';
import { WatchButton } from '../../WatchButton';

export const dynamic = 'force-dynamic';

export async function generateMetadata() {
  return buildMetadata({ title: 'Firm investigation', description: 'Compliance firm investigation on {server}.', path: '/gov/compliance' });
}

type FirmAccount = FirmInvestigation['accounts'][number];

const accountColumns: Column<FirmAccount>[] = [
  { key: 'account', header: 'Account', cell: (a) => <AccountRef id={a.account_id} name={a.display_name} type={a.account_type} /> },
  { key: 'state', header: 'State', secondary: true, cell: (a) => a.removed_at ? <Tag tone="warn">Removed <When value={a.removed_at} /></Tag> : a.is_archived ? <Tag>Archived</Tag> : <Tag tone="pos">Active</Tag> },
  { key: 'balance', header: 'Balance', numeric: true, cell: (a) => <Money value={a.balance} /> },
  { key: 'watch', header: <span className="sr-only">Watchlist</span>, label: 'Watchlist', cell: (a) => <WatchButton accountId={a.account_id} watched={a.watched} label={a.display_name ?? `account #${a.account_id}`} /> },
];

const counterpartyColumns = (dir: 'in' | 'out'): Column<CounterpartyRow>[] => [
  { key: 'who', header: dir === 'in' ? 'From' : 'To', cell: (c) => <PartyRef row={{ account_id: c.counterparty_id, display_name: c.display_name, account_type: c.account_type, owner_uuid: c.owner_uuid, owner_name: c.owner_name, firm_name: c.firm_name }} /> },
  { key: 'n', header: 'Transactions, both ways', numeric: true, secondary: true, cell: (c) => c.txn_count.toLocaleString('en-US') },
  { key: 'amount', header: dir === 'in' ? 'Received' : 'Paid', numeric: true, cell: (c) => <Money value={dir === 'in' ? c.inflow : c.outflow} /> },
];

const anomalyColumns: Column<BalanceAnomaly>[] = [
  { key: 'day', header: 'Day (UTC)', cell: (a) => a.day },
  { key: 'score', header: 'Standard deviations', numeric: true, secondary: true, cell: (a) => a.score >= 99 ? 'new activity' : a.score.toFixed(1) },
  { key: 'net', header: 'Net change', numeric: true, cell: (a) => <Money value={a.net} sign="exceptZero" tone="auto" /> },
];

const monthLabel = (m: string) => new Date(`${m}T00:00:00Z`).toLocaleDateString('en-US', { month: 'short', year: 'numeric', timeZone: 'UTC' });

export default async function FirmInvestigationPage({ params, searchParams }: { params: Promise<{ id: string }>; searchParams: Promise<SearchParams> }) {
  const access = await complianceAccess();
  if (!access.ok) return <GovGate reason={access.reason} title="Compliance access required" />;
  const { id } = await params;
  const firmId = Number(id);
  if (!/^[1-9]\d{0,9}$/.test(id)) notFound();
  const range = parseRange(await searchParams, { defaultPreset: '90d' });
  const f = await getFirmInvestigation(access.viewer, firmId, range.range);
  if (!f) notFound();

  return <>
    <BackLink href="/gov/compliance" label="Compliance" />
    <PageHeader eyebrow="Firm investigation" title={f.firm.display_name}
      meta={<DescriptionList columns={2} items={[
        { label: 'Proprietor', value: <PlayerRef uuid={f.firm.proprietor_uuid} name={f.firm.proprietor_name} /> },
        { label: 'Public page', value: <EntityLink kind="firm" href={`/firms/${encodeURIComponent(f.firm.display_name)}`} name={f.firm.display_name} /> },
      ]} />} />
    <div className="gov-controls"><RangePicker value={range.value} presets={['30d', '90d', 'ytd', '1y']} max={utcDay()} allowCompare={false} /></div>

    <MetricRow label="Firm summary">
      <MetricCard label="Balance, active accounts" value={<Money value={f.totalBalance} />} meta={`${f.accounts.length} account${f.accounts.length === 1 ? '' : 's'}`} info="The firm’s money, not its proprietor’s." />
      <MetricCard label="To the proprietor" value={<Money value={f.proprietor.toProprietor} />} meta={`${f.proprietor.accounts} personal account${f.proprietor.accounts === 1 ? '' : 's'}`} />
      <MetricCard label="From the proprietor" value={<Money value={f.proprietor.fromProprietor} />} />
      <MetricCard label="Flagged transfers" value={f.flagged.rows.length} meta="Large or round amounts" />
    </MetricRow>

    <Section title="Accounts" description="Every account the firm holds or has held.">
      <DataTable id="firm-accounts" label="Firm accounts" columns={accountColumns} rows={f.accounts} rowKey={(a) => a.account_id}
        empty={<p className="dt-empty-text">This firm holds no accounts.</p>} />
    </Section>

    <div className="gov-grid-2">
      <Section title="Money in by counterparty" divider description={<>Whole months from {f.counterparties.months.from.slice(0, 7)}. <Freshness coverage={f.counterparties.coverage} /></>}>
        <DataTable id="firm-in" label="Money in by counterparty" columns={counterpartyColumns('in')} rows={f.counterparties.incoming} rowKey={(c) => c.counterparty_id}
          empty={<p className="dt-empty-text">{f.counterparties.coverage.covered ? 'Nothing received.' : 'Preparing history.'}</p>} />
      </Section>
      <Section title="Money out by counterparty" divider>
        <DataTable id="firm-out" label="Money out by counterparty" columns={counterpartyColumns('out')} rows={f.counterparties.outgoing} rowKey={(c) => c.counterparty_id}
          empty={<p className="dt-empty-text">{f.counterparties.coverage.covered ? 'Nothing paid out.' : 'Preparing history.'}</p>} />
      </Section>
    </div>

    <Section title="Transfers with the proprietor" divider description="Between the firm’s accounts and the proprietor’s personal accounts, by month.">
      <BarChart label="Transfers between the firm and its proprietor" x="month"
        data={f.proprietor.months.map((m) => ({ month: monthLabel(m.month), out: m.outflow, in: m.inflow }))}
        series={[{ key: 'out', label: 'To the proprietor', color: 'var(--neg)' }, { key: 'in', label: 'From the proprietor', color: 'var(--pos)' }]}
        empty="No transfers with the proprietor in this period." />
    </Section>

    <Section title="Large and round-number transfers" divider
      description={<>At least <Money value={LARGE_AMOUNT} compact />, or a whole multiple of <Money value={ROUND_UNIT} compact />. <RawNote range={f.flagged.range} /></>}>
      <DataTable id="firm-flagged" label="Large and round-number transfers" rowKey={(m) => `${m.txn_id}-${m.account_id}`} rows={f.flagged.rows}
        columns={[...movementColumns.slice(0, -1), { key: 'flags', header: 'Why', secondary: true, cell: (m) => <>{m.large && <Tag tone="warn">Large</Tag>} {m.round && <Tag>Round</Tag>}</> }, movementColumns[movementColumns.length - 1]!]}
        empty={<p className="dt-empty-text">Nothing flagged in this window.</p>} />
    </Section>

    <Section title="Sudden balance changes" divider
      description={<>Days in {f.anomalies.recent.from} to {f.anomalies.recent.to} whose net change is more than three standard deviations from the 90 days before. <Freshness coverage={f.anomalies.coverage} /></>}>
      <DataTable id="firm-anomalies" label="Sudden balance changes" columns={anomalyColumns} rows={f.anomalies.rows} rowKey={(a) => a.day}
        empty={<p className="dt-empty-text">{f.anomalies.coverage.covered ? 'No day stands out against the baseline.' : 'Preparing history.'}</p>} />
    </Section>
  </>;
}
