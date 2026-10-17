import { canViewFirmInternals } from '@/lib/auth';
import { PrivacyGate } from '@/components/PrivacyGate';
import { hasPermission } from '@/lib/auth/policy';
import { buildMetadata } from '@/lib/metadata';
import Link from 'next/link';
import type { Route } from 'next';
import { notFound } from 'next/navigation';
import { getViewer } from '@/lib/auth/viewer';
import { auditView } from '@/lib/audit';
import { getFirmHeader, listFirmAccounts, listFirmEmployees } from '@/lib/sql/firm';
import { fmtAmt } from '@/lib/format';
import { LocalTimestamp } from '@/components/LocalTimestamp';
import { Player } from '@/components/Player';
import { CsvButton } from '@/components/CsvButton';
import { TableWrap } from '@/components/TableWrap';

export const dynamic = 'force-dynamic';

export async function generateMetadata() {
  return buildMetadata({ title: 'Admin · Firm report', description: 'Per-firm financial report.', path: '/admin/firms' });
}

/** Admin per-firm report (PAR-224): header, accounts + balances + total, employee
 *  roster — read-only, audited, with CSV export. */
export default async function FirmReportPage({ params }: { params: Promise<{ id: string }> }) {
  const viewer = await getViewer();
  if (viewer.anon || !hasPermission(viewer, 'firm.admin.read')) return null; // layout gates
  const firmId = Number((await params).id);
  if (!Number.isInteger(firmId) || firmId <= 0) notFound();

  if (!await canViewFirmInternals(firmId, viewer)) return <PrivacyGate kind="private" title="Firm roster permission required" />;
  const firm = await getFirmHeader(firmId);
  if (!firm) notFound();
  await auditView(viewer, { path: `/admin/firms/${firmId}`, targetType: 'firm', targetId: String(firmId) });

  const [accounts, employees] = await Promise.all([listFirmAccounts(firmId), listFirmEmployees(firmId)]);

  return (
    <>
      <div className="page-heading">
        <h1>{firm.display_name}</h1>
        <span className="sub">firm report · #{firm.firm_id}{firm.archived ? ' · disbanded' : ''}</span>
      </div>
      <p style={{ marginTop: -4 }}>
        <Link href={'/admin/firms' as Route} className="rowlink small">← back to firms</Link>
      </p>

      <div className="admin-tool-grid" style={{ marginBottom: 16 }}>
        <div className="card"><div className="card-title">Total balance</div><div className="mono" style={{ fontSize: 18 }}>{fmtAmt(firm.total_balance)}</div></div>
        <div className="card"><div className="card-title">Proprietor</div><div><Player name={firm.proprietor_name} uuid={firm.proprietor_uuid} /></div></div>
        <div className="card"><div className="card-title">HQ region</div><div>{firm.hq_region ?? '—'}</div></div>
        <div className="card"><div className="card-title">Discord</div><div className="small">{firm.discord_url ?? '—'}</div></div>
      </div>

      <div className="card">
        <div className="card-title" style={{ display: 'flex', justifyContent: 'space-between' }}>
          <span>Accounts ({accounts.length})</span>
          <CsvButton filename={`firm-${firmId}-accounts.csv`} headers={['Account', 'Name', 'Type', 'Balance', 'Archived']}
            rows={accounts.map((a) => [a.account_id, a.display_name ?? '', a.account_type, a.balance, a.archived ? 'yes' : 'no'])} />
        </div>
        <TableWrap label="Firm accounts"><table className="data-table">
          <thead><tr><th>Account</th><th>Name</th><th>Type</th><th className="amount">Balance</th></tr></thead>
          <tbody>
            {accounts.length === 0 && <tr><td colSpan={4} className="empty">No live accounts.</td></tr>}
            {accounts.map((a) => (
              <tr key={a.account_id}>
                <td><Link href={`/accounts/${a.account_id}` as Route} className="mono rowlink">#{a.account_id}</Link></td>
                <td>{a.display_name ?? '—'}{a.archived ? ' (archived)' : ''}</td>
                <td><span className={`badge badge-${a.account_type}`}>{a.account_type}</span></td>
                <td className="amount mono">{fmtAmt(a.balance)}</td>
              </tr>
            ))}
          </tbody>
        </table></TableWrap>
      </div>

      <div className="card" style={{ marginTop: 12 }}>
        <div className="card-title" style={{ display: 'flex', justifyContent: 'space-between' }}>
          <span>Employees ({employees.length})</span>
          <CsvButton filename={`firm-${firmId}-employees.csv`} headers={['Player', 'Role', 'Joined']}
            rows={employees.map((e) => [e.player_name ?? e.player_uuid ?? '', e.role_name, e.joined_at?.toISOString() ?? ''])} />
        </div>
        <TableWrap label="Firm employees"><table className="data-table">
          <thead><tr><th>Player</th><th>Role</th><th className="ts">Joined</th></tr></thead>
          <tbody>
            {employees.length === 0 && <tr><td colSpan={3} className="empty">No current employees.</td></tr>}
            {employees.map((e, i) => (
              <tr key={i}>
                <td><Player name={e.player_name} uuid={e.player_uuid} /></td>
                <td>{e.role_name}</td>
                <td className="ts"><LocalTimestamp value={e.joined_at} /></td>
              </tr>
            ))}
          </tbody>
        </table></TableWrap>
      </div>
    </>
  );
}
