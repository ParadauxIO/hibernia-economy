import Link from 'next/link';
import type { Route } from 'next';
import { notFound } from 'next/navigation';
import { EntityBadge, MetricCard, MetricRow, Money, PageHeader, Section, When } from '@democracycraft/design-system';
import { BackLink } from '@democracycraft/design-system/next';
import { DataTable, type Column } from '@democracycraft/design-system/table';
import { CsvButton } from '@/components/CsvButton';
import { Player } from '@/components/Player';
import { canViewFirmInternals } from '@/lib/auth';
import { hasPermission } from '@/lib/auth/policy';
import { getViewer } from '@/lib/auth/viewer';
import { auditView } from '@/lib/audit';
import { CURRENCY_SYMBOL } from '@/lib/currency';
import { fmtAmtFull } from '@/lib/format';
import { buildMetadata } from '@/lib/metadata';
import { getFirmHeader, listFirmAccounts, listFirmEmployees, type FirmAccountRow, type FirmEmployeeRow } from '@/lib/sql/firm';
import { AdminGate } from '../../_components/AdminGate';
import { RouterLink } from '../../_components/table';

export const dynamic = 'force-dynamic';

export async function generateMetadata() {
  return buildMetadata({ title: 'Admin · Firm report', description: 'Per-firm financial report.', path: '/admin/firms' });
}

const accountColumns: Column<FirmAccountRow>[] = [
  { key: 'account', header: 'Account', cell: (a) => <><Link href={`/accounts/${a.account_id}` as Route} prefetch={false}>{a.display_name ?? `#${a.account_id}`}</Link><span className="dt-sub">{` #${a.account_id}`}{a.archived ? ' · archived' : ''}</span></> },
  { key: 'type', header: 'Type', secondary: true, cell: (a) => <EntityBadge type={a.account_type} /> },
  { key: 'balance', header: 'Balance', numeric: true, cell: (a) => fmtAmtFull(a.balance) },
];

const employeeColumns: Column<FirmEmployeeRow>[] = [
  { key: 'player', header: 'Player', cell: (e) => <Player name={e.player_name} uuid={e.player_uuid} /> },
  { key: 'role', header: 'Role', cell: (e) => e.role_name },
  { key: 'joined', header: 'Joined', secondary: true, cell: (e) => <When value={e.joined_at} /> },
];

/** Admin per-firm report (PAR-224): header, accounts and balances, roster. Read-only, audited, CSV export. */
export default async function FirmReportPage({ params }: { params: Promise<{ id: string }> }) {
  const viewer = await getViewer();
  if (!hasPermission(viewer, 'firm.admin.read')) return <AdminGate viewer={viewer} title="Firm administration access required" permission="firm.admin.read" />;
  const firmId = Number((await params).id);
  if (!Number.isInteger(firmId) || firmId <= 0) notFound();

  if (!await canViewFirmInternals(firmId, viewer)) return <AdminGate viewer={viewer} title="Firm roster permission required" permission="firm.read.all" />;
  const firm = await getFirmHeader(firmId);
  if (!firm) notFound();
  await auditView(viewer, { path: `/admin/firms/${firmId}`, targetType: 'firm', targetId: String(firmId) });

  const [accounts, employees] = await Promise.all([listFirmAccounts(firmId), listFirmEmployees(firmId)]);

  return (
    <>
      <BackLink href="/admin/firms" label="Firms" />
      <PageHeader
        eyebrow={<><EntityBadge type="BUSINESS" /> <span className="mono">#{firm.firm_id}</span></>}
        title={firm.display_name}
        subtitle={firm.archived ? 'Disbanded. Its accounts are archived.' : 'Firm report: its accounts, balances and staff.'}
      />

      <MetricRow label="Firm summary">
        <MetricCard label="Balance across its accounts" value={<Money value={firm.total_balance} currency={CURRENCY_SYMBOL} />} />
        <MetricCard label="Proprietor" value={<Player name={firm.proprietor_name} uuid={firm.proprietor_uuid} />} />
        <MetricCard label="HQ region" value={firm.hq_region ?? '—'} />
        <MetricCard label="Discord" value={firm.discord_url ?? '—'} />
      </MetricRow>

      <Section
        id="firm-accounts"
        title={`Accounts (${accounts.length})`}
        actions={<CsvButton filename={`firm-${firmId}-accounts.csv`} headers={['Account', 'Name', 'Type', 'Balance', 'Archived']}
          rows={accounts.map((a) => [a.account_id, a.display_name ?? '', a.account_type, a.balance, a.archived ? 'yes' : 'no'])} />}
      >
        <DataTable id="firm-accounts-table" label="Firm accounts" columns={accountColumns} rows={accounts} rowKey={(a) => a.account_id} link={RouterLink}
          empty={<p className="dt-empty-text">No live accounts.</p>} />
      </Section>

      <Section
        id="firm-employees"
        title={`Employees (${employees.length})`}
        divider
        actions={<CsvButton filename={`firm-${firmId}-employees.csv`} headers={['Player', 'Role', 'Joined']}
          rows={employees.map((e) => [e.player_name ?? e.player_uuid ?? '', e.role_name, e.joined_at?.toISOString() ?? ''])} />}
      >
        <DataTable id="firm-employees-table" label="Firm employees" columns={employeeColumns} rows={employees}
          rowKey={(e) => `${e.player_uuid ?? ''}:${e.role_name}`} link={RouterLink} empty={<p className="dt-empty-text">No current employees.</p>} />
      </Section>
    </>
  );
}
