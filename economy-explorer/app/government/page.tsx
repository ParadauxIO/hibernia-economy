import { buildMetadata } from '@/lib/metadata';
import { flattenSearchParams } from '@/lib/util/searchParams';
import Link from 'next/link';
import type { Route } from 'next';
import { z } from 'zod';
import { Toolbar, FilterSelect } from '@/components/Toolbar';
import { Pagination } from '@/components/Pagination';
import { getGovernmentDashboard } from '@/lib/services/government';
import { getViewer, hasPermission } from '@/lib/auth';
import { PrivacyGate } from '@/components/PrivacyGate';
import { accountLabel, fmtAmtFull, fmtN, fmtTs } from '@/lib/format';
import { Player } from '@/components/Player';
import { SectionTabs } from '@/components/SectionTabs';
import { WindowTabs } from '@/components/WindowTabs';

const SP_SCHEMA = z.object({ days: z.coerce.number().int().min(7).max(30).default(30), view: z.enum(['accounts', 'fines']).catch('accounts'), page: z.coerce.number().int().min(1).catch(1), q: z.string().optional(), status: z.enum(['active', 'revoked']).optional().catch(undefined) });

export const dynamic = 'force-dynamic';

export async function generateMetadata() {
  return buildMetadata({ title: "Government", description: "Government accounts and public finances on {server}.", path: "/government" });
}

export default async function GovernmentPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const viewer = await getViewer();
  if (!hasPermission(viewer, 'government.read') && !hasPermission(viewer, 'government.read.all')) {
    return <PrivacyGate kind={viewer.anon ? 'login' : 'private'} title="Government access required" />;
  }
  const sp = SP_SCHEMA.parse(flattenSearchParams(await searchParams));
  const { fines, govAccounts, totals, total } = await getGovernmentDashboard(sp.days, sp.view === 'fines' ? sp : undefined);


  return (
    <>
      <div className="page-heading">
        <h1>Government</h1>
        <span className="sub">government accounts and fines · last {sp.days} days</span>
        <WindowTabs basePath="/government" days={sp.days} params={{ view: sp.view, q: sp.q, status: sp.status }} />
        <SectionTabs />
      </div>

      <div className="kpi-grid">
        <div className="kpi">
          <div className="kpi-label">Active fines</div>
          <div className="kpi-value">{fmtN(totals.activeCount)}</div>
          <div className="kpi-meta">non-revoked in window</div>
        </div>
        <div className="kpi">
          <div className="kpi-label">Active fine amount</div>
          <div className="kpi-value">{fmtAmtFull(totals.activeAmount)}</div>
        </div>
        <div className="kpi">
          <div className="kpi-label">Gov accounts</div>
          <div className="kpi-value">{fmtN(govAccounts.length)}</div>
        </div>
        <div className="kpi">
          <div className="kpi-label">Gov treasury</div>
          <div className="kpi-value">{fmtAmtFull(govAccounts.reduce((s, g) => s + parseFloat(g.balance), 0))}</div>
        </div>
      </div>

      <nav className="window-tabs" aria-label="Government views">
        <Link href={`/government?days=${sp.days}`} className={sp.view === 'accounts' ? 'active' : ''}>Accounts</Link>
        <Link href={`/government?view=fines&days=${sp.days}`} className={sp.view === 'fines' ? 'active' : ''}>Fines</Link>
      </nav>
      {sp.view === 'accounts' ? <>
      <div className="card">
        <div className="card-title">
          Government accounts <span className="sub">non-archived</span>
        </div>
        <div className="table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th>Account</th>
                <th>Owner</th>
                <th className="amount">Balance</th>
              </tr>
            </thead>
            <tbody>
              {govAccounts.length === 0 && (
                <tr><td colSpan={3} className="empty">No government accounts.</td></tr>
              )}
              {govAccounts.map((g) => (
                <tr key={g.account_id}>
                  <td>
                    <Link href={`/accounts/${g.account_id}` as Route} className="rowlink" prefetch={false}>
                      <span style={{ fontWeight: 500 }}>
                        {accountLabel({
                          display_name: g.display_name,
                          owner_name: g.owner_name,
                          owner_uuid: g.owner_uuid,
                          account_id: g.account_id,
                        })}
                      </span>
                      <span className="mono muted small">{` #${g.account_id}`}</span>
                    </Link>
                  </td>
                  <td><Player name={g.owner_name} uuid={g.owner_uuid} /></td>
                  <td className="amount neutral">{fmtAmtFull(g.balance)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      </> : <>
      <Toolbar searchPlaceholder="Search fine reasons…" filters={<FilterSelect paramKey="status" ariaLabel="Fine status" options={[{value: '', label: 'All statuses'}, {value: 'active', label: 'Active'}, {value: 'revoked', label: 'Revoked'}]} />} />
      <div className="card">
        <div className="card-title">
          Recent fines <span className="sub">{fmtN(total)} matching this window</span>
        </div>
        <div className="table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th>When</th>
                <th>Player</th>
                <th className="amount">Amount</th>
                <th>Reason</th>
                <th>Issued by</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              {fines.length === 0 && (
                <tr><td colSpan={6} className="empty">No fines.</td></tr>
              )}
              {fines.map((f) => (
                <tr key={f.fine_id}>
                  <td className="ts">{fmtTs(f.issued_at)}</td>
                  <td><Player name={f.player_name} uuid={f.player_uuid} /></td>
                  <td className="amount neutral">{fmtAmtFull(f.amount)}</td>
                  <td>{f.reason ?? <span className="muted">—</span>}</td>
                  <td><Player name={f.issued_by_name} uuid={f.issued_by_uuid} /></td>
                  <td>
                    {f.revoked
                      ? <span className="badge badge-archived">Revoked</span>
                      : <span className="badge badge-active">Active</span>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
      <Pagination page={sp.page} totalPages={Math.max(1, Math.ceil(total / 25))} totalItems={total} basePath="/government" searchParams={{ view: 'fines', days: String(sp.days), q: sp.q, status: sp.status }} />
      </>}
    </>
  );
}

