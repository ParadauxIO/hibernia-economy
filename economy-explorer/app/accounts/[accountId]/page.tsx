import { buildMetadata } from '@/lib/metadata';
import { flattenSearchParams } from '@/lib/util/searchParams';
import Link from 'next/link';
import type { Route } from 'next';
import { notFound } from 'next/navigation';
import { z } from 'zod';
import {
  findAccount,
  listAccountTransactions,
  countAccountTransactions,
  getCounterparties,
  getAccountActivityCalendar,
  canReadAccount,
} from '@/lib/sql/ledger';
import { accountLabel, fmtAmt, fmtAmtFull, fmtN, fmtTs, shortenUuid } from '@/lib/format';
import { getViewer } from '@/lib/auth/viewer';
import { isStaff } from '@/lib/auth/access';
import { auditView } from '@/lib/audit';
import { Pagination } from '@/components/Pagination';
import { PrivacyGate } from '@/components/PrivacyGate';
import { Player } from '@/components/Player';
import { BackLink } from '@/components/BackLink';
import { BalanceLine, InOutChart } from '@/components/charts/AreaTimeline';
import { ActivityCalendar } from '@/components/charts/ActivityCalendar';
import { getAccountTrajectory } from '@/lib/sql/ledger';
import { listPlayerFirmMemberships, getAccountFirmId, hasFirmFinancialAccess } from '@/lib/sql/firm';
import { memo } from '@/lib/cache';

const SP_SCHEMA = z.object({
  page: z.coerce.number().int().min(1).default(1),
  limit: z.coerce.number().int().min(1).max(200).default(20),
});

export const dynamic = 'force-dynamic';

export async function generateMetadata({ params }: { params: Promise<{ accountId: string }> }) {
  const { accountId } = await params;
  return buildMetadata({ title: `Account #${accountId}`, description: 'Balance, transactions and activity for this {server} account.', path: `/accounts/${accountId}` });
}

export default async function AccountDetailPage({
  params,
  searchParams,
}: {
  params: Promise<{ accountId: string }>;
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const { accountId: idStr } = await params;
  const id = Number(idStr);
  if (!Number.isInteger(id) || id <= 0) notFound();

  const viewer = await getViewer();
  const sp = SP_SCHEMA.parse(flattenSearchParams(await searchParams));

  const account = await findAccount(id);
  if (!account) notFound();

  // Anonymous baseline: balance + metadata visible to all (matches /balance
  // in-game). Per-account history is drilldown — own-data-only: the account
  // owner sees their own activity; staff (admin / government) see everyone's.
  const isOwner = !viewer.anon && !!viewer.minecraftUuid && account.owner_uuid === viewer.minecraftUuid;
  let canSeeHistory = isStaff(viewer) || isOwner;
  // A firm account's history is also visible to that firm's finance-role
  // employees (FINANCIAL/ADMIN), matching the firm-financials access model.
  if (!canSeeHistory && !viewer.anon && viewer.minecraftUuid) {
    const firmId = await getAccountFirmId(id);
    if (firmId) canSeeHistory = await hasFirmFinancialAccess(firmId, viewer.minecraftUuid);
  }
  // Explicit account access (Treasury): a member, authorizer, or read-only viewer
  // of this account — e.g. a government department secretary (PAR-237). Scoped to
  // the specific account, and the page only ever shows ledger postings, never the
  // per-trade chestshop_sale breakdown.
  if (!canSeeHistory && !viewer.anon && viewer.minecraftUuid) {
    canSeeHistory = await canReadAccount(id, viewer.minecraftUuid);
  }

  // Audit privileged inspection: a staff member using elevated access to view
  // someone ELSE's account history (their own view needs no elevation).
  if (!viewer.anon && (viewer.role === 'admin' || viewer.role === 'government') && !isOwner) {
    await auditView(viewer, { path: `/accounts/${id}`, targetType: 'account', targetId: String(id) });
  }

  let txns: Awaited<ReturnType<typeof listAccountTransactions>> = [];
  let totalTxns = 0;
  let counterparties: Awaited<ReturnType<typeof getCounterparties>> = [];
  let calendar: Awaited<ReturnType<typeof getAccountActivityCalendar>> = [];
  let trajectory: Awaited<ReturnType<typeof getAccountTrajectory>> = [];

  if (canSeeHistory) {
    // An account's history is the same for every authorised viewer (the gate
    // decides whether you see it, not what), so cache it keyed by account — the
    // 365-day calendar + transaction scans are the heaviest per-entity queries
    // in the explorer (popular accounts hit ~15s uncached). Read only inside the
    // gate, never keyed by viewer, so nothing privileged leaks.
    [txns, totalTxns, counterparties, calendar, trajectory] = await memo(
      `account-detail:${id}:${sp.page}:${sp.limit}`,
      60_000,
      () =>
        Promise.all([
          listAccountTransactions({ accountId: id, limit: sp.limit, offset: (sp.page - 1) * sp.limit }),
          countAccountTransactions(id),
          getCounterparties(id, 8),
          getAccountActivityCalendar(id, 365),
          getAccountTrajectory(id, 90),
        ]),
    );
  }

  // Owner-context: firm memberships (public — no gate). Surfaces a deep link
  // from any account to the firms its owner is part of. Personal accounts
  // only; the owner_uuid is the natural person for BUSINESS/SYSTEM is null
  // or a system actor.
  const firmMemberships = account.owner_uuid && account.account_type === 'PERSONAL'
    ? await listPlayerFirmMemberships(account.owner_uuid)
    : [];

  // Derive a balance-over-time series: walk backwards from current balance
  // subtracting each posting amount. Newer-first → reverse so chart x-axis
  // ascends in time.
  const currentBalance = parseFloat(account.balance);
  const balanceSeries = (() => {
    if (txns.length === 0) return [] as { date: string; balance: number }[];
    let bal = currentBalance;
    const out: { date: string; balance: number }[] = [];
    for (const p of txns) {
      if (!p.settlement_time) continue;
      out.push({ date: p.settlement_time.toISOString(), balance: bal });
      bal -= parseFloat(p.amount);
    }
    return out.reverse();
  })();
  const inOutSeries = (() => {
    if (txns.length === 0) return [] as { day: string; cr: number; dr: number }[];
    const map = new Map<string, { day: string; cr: number; dr: number }>();
    for (const p of txns) {
      if (!p.settlement_time) continue;
      const day = p.settlement_time.toISOString().slice(0, 10);
      const v = parseFloat(p.amount);
      let row = map.get(day);
      if (!row) { row = { day, cr: 0, dr: 0 }; map.set(day, row); }
      if (v >= 0) row.cr += v; else row.dr += -v;
    }
    return Array.from(map.values()).sort((a, b) => a.day.localeCompare(b.day));
  })();
  const trajectorySeries = trajectory.length > 0
    ? (() => {
        // Walk back from current balance: today's EOD ≈ current; subtract each day's net for prior days.
        let running = currentBalance;
        const eodByDate = new Map<string, number>();
        for (let i = trajectory.length - 1; i >= 0; i--) {
          eodByDate.set(trajectory[i].date, running);
          running -= parseFloat(trajectory[i].net);
        }
        return trajectory.map((r) => ({ date: r.date, balance: eodByDate.get(r.date) ?? 0 }));
      })()
    : [];

  const totalPages = Math.max(1, Math.ceil(totalTxns / sp.limit));

  return (
    <>
      <BackLink href="/accounts" label="Accounts" />

      <div className="page-heading">
        <h1>{accountLabel(account)}</h1>
        <span className={`badge badge-${account.account_type}`}>{account.account_type}</span>
        <span className="mono" style={{ color: 'var(--fg-muted)', fontSize: 13 }}>#{account.account_id}</span>
        {account.archived ? <span className="badge badge-archived">Archived</span> : null}
        {account.requires_authorization ? <span className="badge">Authorizer</span> : null}
      </div>

      {firmMemberships.length > 0 && (
        <div className="card" style={{ marginBottom: 16, display: 'flex', alignItems: 'center', gap: 10, padding: '10px 14px' }}>
          <span className="kpi-label" style={{ marginBottom: 0 }}>Member of</span>
          {firmMemberships.map((m) => (
            <Link
              key={m.firm_id}
              href={`/firms/${encodeURIComponent(m.display_name)}` as Route}
              className="badge"
              prefetch={false}
              style={{ textDecoration: 'none' }}
            >
              {m.display_name} · {m.role_name}
            </Link>
          ))}
        </div>
      )}

      <div className="kpi-grid">
        <div className="kpi">
          <div className="kpi-label">Balance</div>
          <div className="kpi-value">{fmtAmtFull(account.balance)}</div>
          <div className="kpi-meta">{fmtAmt(account.balance)}</div>
        </div>
        <div className="kpi">
          <div className="kpi-label">Owner</div>
          <div
            className="kpi-value"
            style={{
              fontSize: account.owner_name ? 22 : 14,
              paddingTop: 4,
              fontFamily: account.owner_name ? 'inherit' : 'var(--mono)',
              wordBreak: 'break-all',
            }}
          >
            {account.owner_name ?? (account.owner_uuid ? shortenUuid(account.owner_uuid) : '—')}
          </div>
          <div className="kpi-meta">
            {account.owner_uuid ? (
              <span className="mono" title={account.owner_uuid}>{account.owner_uuid}</span>
            ) : (
              <>{account.account_type.toLowerCase()} account</>
            )}
          </div>
        </div>
        <div className="kpi">
          <div className="kpi-label">Created</div>
          <div className="kpi-value" style={{ fontSize: 16, paddingTop: 6 }}>{fmtTs(account.created_at)}</div>
          <div className="kpi-meta">{account.account_type.toLowerCase()} account</div>
        </div>
        <div className="kpi">
          <div className="kpi-label">Overdraft</div>
          <div className="kpi-value" style={{ fontSize: 18, paddingTop: 6 }}>
            {account.allow_overdraft ? `Allowed · ${fmtAmt(account.credit_limit)} limit` : 'Not allowed'}
          </div>
        </div>
      </div>

      {/* Charts */}
      {canSeeHistory && balanceSeries.length > 1 && (
        <div className="charts-grid wide-left">
          <div className="chart-card">
            <div className="chart-header">
              <div>
                <div className="chart-title">Balance over time</div>
                <div className="chart-subtitle">derived from the last {txns.length} postings</div>
              </div>
              <span className="chart-tag">running</span>
            </div>
            <BalanceLine data={balanceSeries} />
          </div>
          <div className="chart-card">
            <div className="chart-header">
              <div>
                <div className="chart-title">Daily in / out</div>
                <div className="chart-subtitle">credits vs debits per day</div>
              </div>
            </div>
            <InOutChart data={inOutSeries} />
          </div>
        </div>
      )}

      {canSeeHistory && trajectorySeries.length > 0 && (
        <div className="card">
          <div className="card-title">Balance trajectory <span className="sub">end-of-day · last 90 days</span></div>
          <BalanceLine data={trajectorySeries} />
        </div>
      )}

      {canSeeHistory && calendar.length > 0 && (
        <div className="card">
          <div className="card-title">Activity <span className="sub">transactions per day · last 365 days</span></div>
          <ActivityCalendar data={calendar} label="transactions" />
        </div>
      )}

      {/* Counterparties */}
      {canSeeHistory && counterparties.length > 0 && (
        <div className="card">
          <div className="card-title">
            Top counterparties <span className="sub">accounts this one transacts with most</span>
          </div>
          <div className="table-wrap"><table className="data-table">
            <thead>
              <tr>
                <th>Counterparty</th>
                <th>Type</th>
                <th className="amount">Txns</th>
                <th className="amount">Total volume</th>
              </tr>
            </thead>
            <tbody>
              {counterparties.map((c) => (
                <tr key={c.counterparty_id}>
                  <td>
                    <Link href={`/accounts/${c.counterparty_id}` as Route} className="rowlink" prefetch={false}>
                      <span style={{ fontWeight: 500 }}>
                        {accountLabel({
                          display_name: c.display_name,
                          owner_name: c.owner_name,
                          owner_uuid: c.owner_uuid,
                          account_id: c.counterparty_id,
                        })}
                      </span>
                      <span className="mono muted small">{` #${c.counterparty_id}`}</span>
                    </Link>
                  </td>
                  <td>
                    <span className={`badge badge-${c.account_type}`}>{c.account_type}</span>
                  </td>
                  <td className="amount mono">{fmtN(c.txn_count)}</td>
                  <td className="amount neutral">{fmtAmtFull(c.total_volume)}</td>
                </tr>
              ))}
            </tbody>
          </table></div>
        </div>
      )}

      <div className="card-title" style={{ marginTop: 4, marginBottom: 12 }}>
        Recent activity
        {canSeeHistory && <span className="sub">{fmtN(totalTxns)} postings</span>}
      </div>

      {!canSeeHistory && (
        <PrivacyGate
          kind={viewer.anon ? 'login' : 'private'}
          title="Transaction history is private"
          hint={
            viewer.anon
              ? 'Log in with your Minecraft account to view your own activity.'
              : 'Only the account owner and staff can view this account’s transaction history.'
          }
        />
      )}

      {canSeeHistory && (
        <>
          <div className="table-wrap"><table className="data-table">
            <thead>
              <tr>
                <th>Txn</th>
                <th className="amount">Amount</th>
                <th>Message</th>
                <th>Memo</th>
                <th className="ts">Settled</th>
                <th>Initiator</th>
                <th>Source</th>
              </tr>
            </thead>
            <tbody>
              {txns.length === 0 && (
                <tr>
                  <td colSpan={7} className="empty">No postings on file for this account.</td>
                </tr>
              )}
              {txns.map((p) => {
                const v = parseFloat(p.amount);
                const cls = v > 0 ? 'amount pos' : v < 0 ? 'amount neg' : 'amount neutral';
                return (
                  <tr key={p.posting_id}>
                    <td>
                      <Link href={`/transactions/${p.txn_id}` as Route} className="mono rowlink" prefetch={false}>
                        #{p.txn_id}
                      </Link>
                    </td>
                    <td className={cls}>{v > 0 ? '+' : ''}{fmtAmtFull(p.amount)}</td>
                    <td>{p.message}</td>
                    <td>
                      <span className="mono" style={{ color: 'var(--fg-muted)' }}>{p.memo ?? '—'}</span>
                    </td>
                    <td className="ts">{fmtTs(p.settlement_time)}</td>
                    <td><Player name={p.initiator_name} uuid={p.initiator_uuid} /></td>
                    <td>
                      <span className="mono" style={{ color: 'var(--fg-muted)' }}>{p.plugin_system ?? '—'}</span>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table></div>

          {totalTxns > sp.limit && (
            <Pagination
              page={sp.page}
              totalPages={totalPages}
              totalItems={totalTxns}
              basePath={`/accounts/${id}`}
              searchParams={{ limit: String(sp.limit) }}
            />
          )}
        </>
      )}
    </>
  );
}

