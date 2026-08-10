import { buildMetadata } from '@/lib/metadata';
import { flattenSearchParams } from '@/lib/util/searchParams';
import Link from 'next/link';
import type { Route } from 'next';
import { z } from 'zod';
import { getRecentFines, getFineCategorySummary, getGovernmentAccounts } from '@/lib/sql/government';
import { memo } from '@/lib/cache';
import { accountLabel, fmtAmtFull, fmtN, fmtTs } from '@/lib/format';
import { Player } from '@/components/Player';
import { SectionTabs } from '@/components/SectionTabs';
import { WindowTabs } from '@/components/WindowTabs';

const SP_SCHEMA = z.object({ days: z.coerce.number().int().min(7).max(30).default(30) });

export const dynamic = 'force-dynamic';

export async function generateMetadata() {
  return buildMetadata({ title: "Government", description: "Government accounts and public finances on {server}.", path: "/government" });
}

export default async function GovernmentPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  // Public — players are expected to see government account holdings and the
  // public record of fines.
  const sp = SP_SCHEMA.parse(flattenSearchParams(await searchParams));
  const [cats, fines, govAccounts] = await memo(`government:${sp.days}`, 60_000, () =>
    Promise.all([
      getFineCategorySummary(sp.days),
      getRecentFines(sp.days, 100),
      getGovernmentAccounts(),
    ]),
  );

  const totalFineAmount = fines.reduce((s, f) => s + (f.revoked ? 0 : parseFloat(f.amount)), 0);
  const activeFines = fines.filter((f) => !f.revoked).length;

  return (
    <>
      <div className="page-heading">
        <h1>Government</h1>
        <span className="sub">fines and gov accounts · last {sp.days} days</span>
        <WindowTabs basePath="/government" days={sp.days} />
        <SectionTabs />
      </div>

      <div className="kpi-grid">
        <div className="kpi">
          <div className="kpi-label">Active fines</div>
          <div className="kpi-value">{fmtN(activeFines)}</div>
          <div className="kpi-meta">non-revoked in window</div>
        </div>
        <div className="kpi">
          <div className="kpi-label">Active fine amount</div>
          <div className="kpi-value">{fmtAmtFull(totalFineAmount)}</div>
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

      <div className="card">
        <div className="card-title">
          Fine categories <span className="sub">grouped by first word of reason</span>
        </div>
        <div className="table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th>Category</th>
                <th className="amount">Issued</th>
                <th className="amount">Total amount</th>
                <th className="amount">Active</th>
                <th className="amount">Active amount</th>
              </tr>
            </thead>
            <tbody>
              {cats.length === 0 && (
                <tr><td colSpan={5} className="empty">No fines in this window.</td></tr>
              )}
              {cats.map((c) => (
                <tr key={c.category}>
                  <td>{c.category}</td>
                  <td className="amount mono">{fmtN(c.fine_count)}</td>
                  <td className="amount neutral">{fmtAmtFull(c.total_amount)}</td>
                  <td className="amount mono">{fmtN(c.active_fine_count)}</td>
                  <td className="amount neutral">{fmtAmtFull(c.active_amount)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

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

      <div className="card">
        <div className="card-title">
          Recent fines <span className="sub">{fines.length} in this window</span>
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
    </>
  );
}

