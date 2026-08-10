import { buildMetadata } from '@/lib/metadata';
import Link from 'next/link';
import type { Route } from 'next';
import { notFound } from 'next/navigation';
import { getViewer } from '@/lib/auth/viewer';
import { canViewFirmFinancials } from '@/lib/auth/access';
import { auditView } from '@/lib/audit';
import {
  findFirmByDisplayName,
  listFirmEmployees,
  listFirmRoles,
  listFirmRolePermissions,
  listFirmAccounts,
  isFirmMember,
} from '@/lib/sql/firm';
import { fmtAmtFull, fmtN, fmtTs } from '@/lib/format';
import { Player } from '@/components/Player';
import { BackLink } from '@/components/BackLink';
import { PrivacyGate } from '@/components/PrivacyGate';
import { getMarketFirm, listFirmMarketItems } from '@/lib/sql/market';

export const dynamic = 'force-dynamic';

export async function generateMetadata({ params }: { params: Promise<{ firmName: string }> }) {
  const { firmName } = await params;
  const name = decodeURIComponent(firmName);
  return buildMetadata({ title: name, description: `Profile, accounts and activity for the {server} firm ${name}.`, path: `/firms/${firmName}` });
}

export default async function FirmDetailPage({
  params,
}: {
  params: Promise<{ firmName: string }>;
}) {
  const { firmName } = await params;
  const name = decodeURIComponent(firmName);
  const firm = await findFirmByDisplayName(name);
  if (!firm) notFound();

  // Public identity (name, HQ, founded, default account, archived flag) is
  // always shown. Employees / roles / accounts are gated to: firm member ∨
  // government ∨ admin. Mirrors AUTH-SPEC §9.
  const viewer = await getViewer();
  let canSeeInternals = false;
  if (!viewer.anon) {
    if (viewer.role === 'admin' || viewer.role === 'government') {
      canSeeInternals = true;
    } else if (viewer.minecraftUuid) {
      canSeeInternals = await isFirmMember(firm.firm_id, viewer.minecraftUuid);
    }
  }

  // ChestShop sales/volume is financial drilldown — narrower than internals:
  // only finance-role employees (FINANCIAL/ADMIN) + staff. A plain member can
  // see the roster but not the books.
  const canSeeFinancials = await canViewFirmFinancials(firm.firm_id, viewer);

  // Audit privileged inspection: a staff member using elevated access to view a
  // firm's internals/financials when they aren't actually part of that firm.
  if (!viewer.anon && (viewer.role === 'admin' || viewer.role === 'government')) {
    const member = viewer.minecraftUuid ? await isFirmMember(firm.firm_id, viewer.minecraftUuid) : false;
    if (!member) {
      await auditView(viewer, { path: `/firms/${encodeURIComponent(firmName)}`, targetType: 'firm', targetId: String(firm.firm_id) });
    }
  }

  const [accounts, employees, roles, perms] = canSeeInternals
    ? await Promise.all([
        listFirmAccounts(firm.firm_id),
        listFirmEmployees(firm.firm_id),
        listFirmRoles(firm.firm_id),
        listFirmRolePermissions(firm.firm_id),
      ])
    : await Promise.all([
        Promise.resolve<Awaited<ReturnType<typeof listFirmAccounts>>>([]),
        Promise.resolve<Awaited<ReturnType<typeof listFirmEmployees>>>([]),
        Promise.resolve<Awaited<ReturnType<typeof listFirmRoles>>>([]),
        Promise.resolve<Awaited<ReturnType<typeof listFirmRolePermissions>>>([]),
      ]);

  const [marketFirm, marketItems] = canSeeFinancials
    ? await Promise.all([getMarketFirm(firm.firm_id), listFirmMarketItems(firm.firm_id, 8, 0)])
    : [null, [] as Awaited<ReturnType<typeof listFirmMarketItems>>];

  const permsByRole = new Map<number, string[]>();
  for (const p of perms) {
    const arr = permsByRole.get(p.role_id) ?? [];
    arr.push(p.permission);
    permsByRole.set(p.role_id, arr);
  }

  const totalBalance = accounts.reduce((s, a) => s + parseFloat(a.balance), 0);

  return (
    <>
      <BackLink href="/firms" label="Firms" />

      <div className="page-heading">
        <h1>{firm.display_name}</h1>
        <span className="mono" style={{ color: 'var(--fg-muted)', fontSize: 13 }}>#{firm.firm_id}</span>
        {firm.archived ? <span className="badge badge-archived">Archived</span> : null}
        {firm.exempt ? <span className="badge badge-exempt">Tax exempt</span> : null}
      </div>

      <div className="kpi-grid">
        <div className="kpi">
          <div className="kpi-label">Proprietor</div>
          <div className="kpi-value" style={{ fontSize: 16, paddingTop: 6 }}>
            {firm.proprietor_uuid
              ? <Player name={firm.proprietor_name} uuid={firm.proprietor_uuid} />
              : '—'}
          </div>
        </div>
        <div className="kpi">
          <div className="kpi-label">HQ region</div>
          <div className="kpi-value" style={{ fontSize: 18, paddingTop: 6 }}>
            {firm.hq_region ?? '—'}
          </div>
        </div>
        <div className="kpi">
          <div className="kpi-label">Founded</div>
          <div className="kpi-value" style={{ fontSize: 16, paddingTop: 6 }}>{fmtTs(firm.created_at)}</div>
        </div>
        <div className="kpi">
          <div className="kpi-label">Default account</div>
          <div className="kpi-value" style={{ fontSize: 16, paddingTop: 6 }}>
            {firm.default_account_id ? (
              <Link href={`/accounts/${firm.default_account_id}` as Route} prefetch={false}>
                #{firm.default_account_id}
              </Link>
            ) : '—'}
          </div>
        </div>
        <div className="kpi">
          <div className="kpi-label">Discord</div>
          <div className="kpi-value" style={{ fontSize: 14, paddingTop: 6 }}>
            {firm.discord_url ? (
              <a href={firm.discord_url} target="_blank" rel="noopener noreferrer">link ↗</a>
            ) : '—'}
          </div>
        </div>
      </div>

      {/* ChestShop activity is financial drilldown — only finance-role
          employees + staff (canSeeFinancials gates the fetch above). */}
      {marketFirm && marketFirm.sale_count > 0 && (
        <div className="card">
          <div className="card-title">
            ChestShop activity{' '}
            <span className="sub">
              {fmtN(marketFirm.sale_count)} sales · {fmtAmtFull(marketFirm.total_volume)} volume ·{' '}
              <Link href={`/chestshop/firms/${firm.firm_id}` as Route} className="rowlink" prefetch={false}>
                see all →
              </Link>
            </span>
          </div>
          <div className="table-wrap">
            <table className="data-table">
              <thead>
                <tr>
                  <th>Item</th>
                  <th>Material</th>
                  <th className="amount">Trades</th>
                  <th className="amount">Quantity</th>
                  <th className="amount">Volume</th>
                </tr>
              </thead>
              <tbody>
                {marketItems.map((i) => (
                  <tr key={i.item_key}>
                    <td>
                      <Link href={`/chestshop/items/${encodeURIComponent(i.item_key)}` as Route} className="rowlink" prefetch={false}>
                        {i.item_name ?? i.item_key}
                      </Link>
                    </td>
                    <td><span className="mono small" style={{ color: 'var(--fg-muted)' }}>{i.material ?? '—'}</span></td>
                    <td className="amount mono">{fmtN(i.trade_count)}</td>
                    <td className="amount mono">{fmtN(i.total_quantity)}</td>
                    <td className="amount neutral">{fmtAmtFull(i.total_volume)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {!canSeeInternals && (
        <PrivacyGate
          kind={viewer.anon ? 'login' : 'private'}
          title="Firm internals are private"
          hint="Employees, roles, and accounts are only visible to firm members, government, and admins."
        />
      )}

      {canSeeInternals && (
        <>
          {/* Accounts */}
          <div className="card">
            <div className="card-title">
              Accounts <span className="sub">{fmtN(accounts.length)} active · total {fmtAmtFull(totalBalance)}</span>
            </div>
            <div className="table-wrap">
              <table className="data-table">
                <thead>
                  <tr>
                    <th>Account</th>
                    <th>Type</th>
                    <th className="amount">Balance</th>
                    <th>Status</th>
                  </tr>
                </thead>
                <tbody>
                  {accounts.length === 0 && (
                    <tr><td colSpan={4} className="empty">No accounts.</td></tr>
                  )}
                  {accounts.map((a) => (
                    <tr key={a.account_id}>
                      <td>
                        <Link href={`/accounts/${a.account_id}` as Route} className="rowlink" prefetch={false}>
                          <span style={{ fontWeight: 500 }}>{a.display_name ?? `Account #${a.account_id}`}</span>
                          <span className="mono muted small">{` #${a.account_id}`}</span>
                        </Link>
                      </td>
                      <td><span className={`badge badge-${a.account_type}`}>{a.account_type}</span></td>
                      <td className="amount neutral">{fmtAmtFull(a.balance)}</td>
                      <td>
                        {a.archived
                          ? <span className="badge badge-archived">Archived</span>
                          : <span className="badge badge-active">Active</span>}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>

          {/* Employees */}
          <div className="card">
            <div className="card-title">
              Employees <span className="sub">{fmtN(employees.length)} active</span>
            </div>
            <div className="table-wrap">
              <table className="data-table">
                <thead>
                  <tr>
                    <th>Player</th>
                    <th>Role</th>
                    <th className="ts">Joined</th>
                  </tr>
                </thead>
                <tbody>
                  {employees.length === 0 && (
                    <tr><td colSpan={3} className="empty">No active employees.</td></tr>
                  )}
                  {employees.map((e) => (
                    <tr key={`${e.player_uuid}-${e.joined_at?.toString()}`}>
                      <td><Player name={e.player_name} uuid={e.player_uuid} /></td>
                      <td><span className="badge">{e.role_name}</span></td>
                      <td className="ts">{fmtTs(e.joined_at)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>

          {/* Roles + permissions */}
          <div className="card">
            <div className="card-title">
              Roles <span className="sub">{fmtN(roles.length)} role{roles.length === 1 ? '' : 's'}</span>
            </div>
            <div className="table-wrap">
              <table className="data-table">
                <thead>
                  <tr>
                    <th>Role</th>
                    <th className="amount">Rank</th>
                    <th>Flags</th>
                    <th>Permissions</th>
                  </tr>
                </thead>
                <tbody>
                  {roles.length === 0 && (
                    <tr><td colSpan={4} className="empty">No roles.</td></tr>
                  )}
                  {roles.map((r) => (
                    <tr key={r.role_id}>
                      <td><span style={{ fontWeight: 500 }}>{r.name}</span></td>
                      <td className="amount mono">{r.rank_order}</td>
                      <td>
                        {r.proprietor_like ? <span className="badge">proprietor</span> : null}{' '}
                        {r.default_role ? <span className="badge">default</span> : null}
                      </td>
                      <td>
                        {(permsByRole.get(r.role_id) ?? []).length === 0 ? (
                          <span className="muted">—</span>
                        ) : (
                          (permsByRole.get(r.role_id) ?? []).map((p) => (
                            <span key={p} className="mono small" style={{ background: 'var(--bg-soft)', padding: '2px 6px', borderRadius: 4, marginRight: 4, color: 'var(--fg-soft)' }}>{p}</span>
                          ))
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        </>
      )}
    </>
  );
}
