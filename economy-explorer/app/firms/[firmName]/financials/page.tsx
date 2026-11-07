import type { AppHref } from '@/lib/routes';
import Link from 'next/link';
import { AccessGate, ExportMenu, Money, Section } from '@democracycraft/design-system';
import { RangePicker } from '@democracycraft/design-system/next';
import { DataTable, type Column } from '@democracycraft/design-system/table';
import { BarChart, InOutChart } from '@/components/charts/AreaTimeline';
import { Player } from '@/components/Player';
import { CoverageNote } from '@/components/entity/bits';
import { LoginButton } from '@/components/entity/LoginButton';
import { PrintButton } from '@/components/entity/PrintButton';
import { EntityLink } from '@democracycraft/design-system/next';
import { firmHref, itemHref, previewHref } from '@/lib/entityLinks';
import { itemIconUrl } from '@/lib/itemIcon';
import { buildMetadata } from '@/lib/metadata';
import { parseMoney, toNumber } from '@/lib/money';
import { parseRange, rangeToParams } from '@/lib/range';
import { getFirmFinancials, type FirmFinancials, type MonthStatement } from '@/lib/services/firm';
import type { FirmItemRow } from '@/lib/sql/firm';
import { FirmHeader, loadFirm } from '../shared';
import { exportHrefs } from '@/lib/export/href';
import { firmTxnsExportTable } from '@/lib/tables/accountTxns';
import { parseTableState } from '@/lib/tables/core';

export const dynamic = 'force-dynamic';

type Params = Promise<{ firmName: string }>;
type SearchParams = Promise<Record<string, string | string[] | undefined>>;

export async function generateMetadata({ params }: { params: Params }) {
  const { firmName } = await params;
  const name = decodeURIComponent(firmName);
  return buildMetadata({ title: `${name} financials`, description: `Revenue, costs and monthly statements for ${name}.`, path: `/firms/${encodeURIComponent(name)}/financials` });
}

const MONTH_FMT = new Intl.DateTimeFormat('en-US', { month: 'long', year: 'numeric', timeZone: 'UTC' });
const monthName = (m: string) => MONTH_FMT.format(new Date(`${m}-01T00:00:00Z`));

/**
 * Spec §7.3 Financials: revenue and costs by category across the firm's
 * accounts (rollups), payroll, shop performance by item and a monthly P&L.
 * Only for the firm's finance staff and staff with firm.financials.read.all.
 */
export default async function FirmFinancialsPage({ params, searchParams }: { params: Params; searchParams: SearchParams }) {
  const [{ firmName }, sp] = await Promise.all([params, searchParams]);
  const loaded = await loadFirm(firmName, 'financials');
  const base = firmHref(loaded.firm.display_name);
  if (!loaded.canSeeFinancials) {
    return <>
      <FirmHeader loaded={loaded} tab="financials" />
      <AccessGate kind={loaded.viewer.anon ? 'login' : 'request'} title="Financials are private to the firm"
        description={<p>Revenue, costs, payroll and statements for {loaded.firm.display_name} are visible to the people who run its money. Its balances and 30-day totals are public on the overview.</p>}
        grants={['The firm’s proprietor', 'An employee whose role has the Financial or Admin permission', 'Staff with permission to read every firm’s financials']}
        action={loaded.viewer.anon ? <LoginButton returnTo={`${base}/financials`} label="Log in" /> : <Link href={base} className="btn">Back to overview</Link>} />
    </>;
  }

  const range = parseRange(sp, { defaultPreset: '30d' });
  const accountIds = loaded.pub.accounts.map((a) => a.account_id);
  const fin = await getFirmFinancials(loaded.firm.firm_id, accountIds, range.range);
  const month = typeof sp.month === 'string' && fin.statements.some((s) => s.month === sp.month) ? sp.month : fin.statements[0]?.month ?? null;
  const statement = fin.statements.find((s) => s.month === month) ?? null;
  const rangeQs = new URLSearchParams(rangeToParams(range)).toString();
  // The firm's transactions for this range, through the export registry (one account after another).
  const txnExportState = { ...parseTableState({}, firmTxnsExportTable), filters: [
    { field: 'firm' as const, op: 'in' as const, values: [String(loaded.firm.firm_id)] },
    { field: 'date' as const, op: 'between' as const, values: [range.range.from, range.range.to] },
  ] };
  const cats = fin.range.categories.data.filter((c) => c.category !== 'internal' && (c.inflow !== '0.00' || c.outflow !== '0.00'));

  return <>
    <FirmHeader loaded={loaded} tab="financials" />
    <RangePicker value={range.value} max={range.range.to} resetParams={['month']} />

    <Section title="Revenue and costs by category" description={`${range.label}, across the firm’s accounts. Transfers between them are left out.`}>
      <CoverageNote coverage={fin.range.categories.coverage} />
      <BarChart data={cats.map((c) => ({ label: c.taxKind ? `${c.label} (${c.taxKind})` : c.label, in: toNumber(parseMoney(c.inflow)), out: toNumber(parseMoney(c.outflow)), inRaw: c.inflow, outRaw: c.outflow }))}
        x="label" horizontal height={Math.max(160, cats.length * 44)} label="Revenue and costs by category"
        series={[{ key: 'in', label: 'Revenue', format: 'money', color: 'var(--pos)', rawKey: 'inRaw' }, { key: 'out', label: 'Costs', format: 'money', color: 'var(--neg)', rawKey: 'outRaw' }]}
        empty="No money moved in this range." />
    </Section>

    <Section title="Money in and out" description={range.label} divider
      actions={<ExportMenu label="Export transactions" hrefs={exportHrefs('firm-transactions', txnExportState, firmTxnsExportTable)} allDescription={`Every transaction on the firm's accounts, ${range.label.toLowerCase()}`} />}>
      <CoverageNote coverage={fin.range.days.coverage} />
      <InOutChart data={fin.range.days.data.map((d) => ({ day: d.day, cr: d.inflow, dr: d.outflow }))} />
    </Section>

    <div className="entity-pair">
      <Section title="Payroll" description={`Paid to current staff, ${range.label.toLowerCase()} (by whole months)`} divider>
        <CoverageNote coverage={fin.payrollCoverage} />
        <Payroll rows={fin.payroll} />
      </Section>
      <Section title="Shop performance" description={`By item, ${range.label.toLowerCase()}`} divider>
        <ShopItems rows={fin.shopItems} />
      </Section>
    </div>

    <Section id="statement" className="statement" title="Monthly statement" divider
      description={statement ? `Profit and loss for ${monthName(statement.month)}` : 'Profit and loss by month'}
      actions={statement && <div className="entity-actions">
        <PrintButton />
        <ExportMenu hrefs={{ all: { csv: `${base}/financials/export?format=csv`, json: `${base}/financials/export?format=json` } }} allDescription="Every month with its categories" />
      </div>}>
      {fin.statementCoverage && <CoverageNote coverage={fin.statementCoverage} />}
      {statement ? <Statement statement={statement} /> : <p className="muted">No statements yet.</p>}
      <Months statements={fin.statements} selected={month} href={(m) => `${base}/financials?${rangeQs}&month=${m}#statement`} />
    </Section>
  </>;
}

function Payroll({ rows }: { rows: FirmFinancials['payroll'] }) {
  const columns: Column<FirmFinancials['payroll'][number]>[] = [
    { key: 'who', header: 'Employee', cell: (p) => <Player name={p.name} uuid={p.uuid} /> },
    { key: 'role', header: 'Role', secondary: true, cell: (p) => p.role },
    { key: 'paid', header: 'Paid', numeric: true, cell: (p) => <Money value={p.amount} /> },
  ];
  return <DataTable id="firm-payroll" label="Payroll" columns={columns} rows={rows} rowKey={(p) => p.uuid} density="compact"
    empty={<p className="dt-empty-text">No payments to current staff in this range.</p>} />;
}

function ShopItems({ rows }: { rows: FirmItemRow[] }) {
  const columns: Column<FirmItemRow>[] = [
    { key: 'item', header: 'Item', cell: (i) => <EntityLink kind="item" href={itemHref(i.item_key)} name={i.item_name ?? i.item_key} icon={itemIconUrl(i.material, i.item_custom)} preview={previewHref('item', i.item_key)} /> },
    { key: 'trades', header: 'Trades', numeric: true, secondary: true, cell: (i) => i.trades.toLocaleString('en-US') },
    { key: 'sold', header: 'Sales', numeric: true, cell: (i) => <Money value={i.sold_volume} /> },
    { key: 'bought', header: 'Bought in', numeric: true, secondary: true, cell: (i) => <Money value={i.bought_volume} /> },
  ];
  return <DataTable id="firm-shop-items" label="Shop performance by item" columns={columns} rows={rows} rowKey={(i) => i.item_key} density="compact"
    empty={<p className="dt-empty-text">No shop trades in this range.</p>} />;
}

function Statement({ statement }: { statement: MonthStatement }) {
  type Line = { key: string; label: string; amount: string | null; kind: 'head' | 'line' | 'total' | 'net' };
  const rows: Line[] = [
    { key: 'rh', label: 'Revenue', amount: null, kind: 'head' },
    ...statement.revenue.map((l) => ({ key: `r-${l.category}`, label: l.label, amount: l.amount, kind: 'line' as const })),
    { key: 'rt', label: 'Total revenue', amount: statement.totalRevenue, kind: 'total' },
    { key: 'ch', label: 'Costs', amount: null, kind: 'head' },
    ...statement.costs.map((l) => ({ key: `c-${l.category}`, label: l.label, amount: `-${l.amount}`, kind: 'line' as const })),
    { key: 'ct', label: 'Total costs', amount: `-${statement.totalCosts}`, kind: 'total' },
    { key: 'n', label: 'Net', amount: statement.net, kind: 'net' },
  ];
  const columns: Column<Line>[] = [
    { key: 'label', header: monthName(statement.month), cell: (l) => l.kind === 'line' ? <span className="dt-sub">{l.label}</span> : <strong>{l.label}</strong> },
    { key: 'amount', header: 'Amount', numeric: true, cell: (l) => l.amount === null ? '' : <Money value={l.amount.replace(/^--/, '')} sign={l.kind === 'net' ? 'exceptZero' : 'auto'} tone={l.kind === 'net' ? 'auto' : 'none'} /> },
  ];
  return <DataTable id="firm-statement" label={`Statement for ${monthName(statement.month)}`} columns={columns} rows={rows} rowKey={(l) => l.key} density="compact" />;
}

function Months({ statements, selected, href }: { statements: MonthStatement[]; selected: string | null; href: (m: string) => AppHref }) {
  const columns: Column<MonthStatement>[] = [
    { key: 'month', header: 'Month', cell: (s) => <Link href={href(s.month)} aria-current={s.month === selected ? 'true' : undefined}>{monthName(s.month)}</Link> },
    { key: 'rev', header: 'Revenue', numeric: true, cell: (s) => <Money value={s.totalRevenue} /> },
    { key: 'cost', header: 'Costs', numeric: true, secondary: true, cell: (s) => <Money value={s.totalCosts} /> },
    { key: 'net', header: 'Net', numeric: true, cell: (s) => <Money value={s.net} sign="exceptZero" tone="auto" /> },
  ];
  return statements.length > 1
    ? <DataTable id="firm-months" label="Monthly totals" columns={columns} rows={statements} rowKey={(s) => s.month} density="compact" />
    : null;
}

