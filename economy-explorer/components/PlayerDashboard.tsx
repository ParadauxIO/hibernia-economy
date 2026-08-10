import 'server-only';
import Link from 'next/link';
import type { Route } from 'next';
import {
  findAccountsForPlayer,
  getPlayerTotals,
  countPlayerTransactions,
  getPlayerCounterparties,
  getPlayerTransactions,
} from '@/lib/sql/me';
import { accountLabel, fmtAmtFull, fmtN, fmtTs } from '@/lib/format';
import { CsvButton } from '@/components/CsvButton';
import { JsonButton } from '@/components/JsonButton';

const DAYS = 90;

/**
 * The personal economy dashboard for one player — balances, recent transactions,
 * and top counterparties. Keyed on a Minecraft UUID, so it backs both the
 * self-service `/me` page and the admin player lookup (`/admin/players/[uuid]`).
 * The caller renders the page heading + access gate; this is the body only.
 */
export async function PlayerDashboard({ uuid, name, self = false }: { uuid: string; name: string | null; self?: boolean }) {
  const accounts = await findAccountsForPlayer(uuid);
  const accountIds = accounts.map((a) => a.account_id);
  const [totals, txnCount, partners, recentTxns] = await Promise.all([
    getPlayerTotals(accountIds, DAYS),
    countPlayerTransactions(accountIds, DAYS),
    getPlayerCounterparties(accountIds, 12, DAYS),
    getPlayerTransactions(accountIds, 25),
  ]);

  // Money rollups are summed in SQL and carried as exact DECIMAL strings — never
  // folded through a JS double (behaviour/0001) — so the KPIs match the ledger to
  // the cent. Only the sign class needs a numeric compare of the net string.
  const { totalBalance, income, spend, net } = totals;
  const netNonNegative = !net.trimStart().startsWith('-');

  const prefix = (self ? 'my' : (name ?? uuid)).toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '') || 'player';

  return (
    <>
      <div className="kpi-grid">
        <div className="kpi">
          <div className="kpi-label">Total balance</div>
          <div className="kpi-value">{fmtAmtFull(totalBalance)}</div>
          <div className="kpi-meta">across {fmtN(accounts.length)} accounts</div>
        </div>
        <div className="kpi">
          <div className="kpi-label">Income / {DAYS}d</div>
          <div className="kpi-value">{fmtAmtFull(income)}</div>
          <div className="kpi-meta">credits</div>
        </div>
        <div className="kpi">
          <div className="kpi-label">Spend / {DAYS}d</div>
          <div className="kpi-value">{fmtAmtFull(spend)}</div>
          <div className="kpi-meta">debits</div>
        </div>
        <div className="kpi">
          <div className="kpi-label">Net / {DAYS}d</div>
          <div className={`kpi-value ${netNonNegative ? 'pos' : 'neg'}`}>{netNonNegative ? '+' : ''}{fmtAmtFull(net)}</div>
          <div className="kpi-meta">{fmtN(txnCount)} txns</div>
        </div>
      </div>

      <div className="card">
        <div className="card-title">{self ? 'Your accounts' : 'Accounts'} <span className="sub">owned and shared</span>
          <span className="toolbar-export" style={{ marginLeft: 'auto' }}>
            <CsvButton
              filename={`${prefix}-accounts.csv`}
              headers={['Account ID', 'Name', 'Type', 'Role', 'Balance', 'Created']}
              rows={accounts.map((a) => [
                a.account_id,
                accountLabel(a),
                a.account_type,
                a.owner_uuid === uuid ? 'owner' : 'member',
                a.balance,
                a.created_at.toISOString(),
              ])}
            />
            <JsonButton filename={`${prefix}-accounts.json`} data={accounts} />
          </span>
        </div>
        <div className="table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th>Account</th>
                <th>Type</th>
                <th>Role</th>
                <th className="amount">Balance</th>
                <th className="ts">Created</th>
              </tr>
            </thead>
            <tbody>
              {accounts.length === 0 && (
                <tr><td colSpan={5} className="empty">No accounts found.</td></tr>
              )}
              {accounts.map((a) => (
                <tr key={a.account_id}>
                  <td>
                    <Link href={`/accounts/${a.account_id}` as Route} className="rowlink" prefetch={false}>
                      <span style={{ fontWeight: 500 }}>{accountLabel(a)}</span>
                      <span className="mono muted small">{` #${a.account_id}`}</span>
                    </Link>
                  </td>
                  <td><span className={`badge badge-${a.account_type}`}>{a.account_type}</span></td>
                  <td><span className="badge">{a.owner_uuid === uuid ? 'owner' : 'member'}</span></td>
                  <td className="amount neutral">{fmtAmtFull(a.balance)}</td>
                  <td className="ts">{fmtTs(a.created_at)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      <div className="card">
        <div className="card-title">Recent transactions <span className="sub">latest {fmtN(recentTxns.length)} across {self ? 'your' : 'their'} accounts</span>
          <span className="toolbar-export" style={{ marginLeft: 'auto' }}>
            <CsvButton
              filename={`${prefix}-transactions.csv`}
              headers={['When', 'Account', 'Account ID', 'Amount', 'Details', 'System', 'Txn']}
              rows={recentTxns.map((t) => [
                t.settlement_time?.toISOString() ?? '',
                t.account_name ?? `#${t.account_id}`,
                t.account_id,
                t.amount,
                t.message ?? t.memo ?? '',
                t.plugin_system ?? '',
                t.txn_id,
              ])}
            />
            <JsonButton filename={`${prefix}-transactions.json`} data={recentTxns} />
          </span>
        </div>
        <div className="table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th className="ts">When</th>
                <th>Account</th>
                <th className="amount">Amount</th>
                <th>Details</th>
                <th>Txn</th>
              </tr>
            </thead>
            <tbody>
              {recentTxns.length === 0 && (
                <tr><td colSpan={5} className="empty">No transactions yet.</td></tr>
              )}
              {recentTxns.map((t) => {
                const v = parseFloat(t.amount);
                return (
                  <tr key={t.posting_id}>
                    <td className="ts">{fmtTs(t.settlement_time)}</td>
                    <td>
                      <Link href={`/accounts/${t.account_id}` as Route} className="rowlink" prefetch={false}>
                        {t.account_name ?? `#${t.account_id}`}
                      </Link>
                    </td>
                    <td className={`amount ${v >= 0 ? 'pos' : 'neg'}`}>{v >= 0 ? '+' : ''}{fmtAmtFull(t.amount)}</td>
                    <td>
                      {t.message ?? <span className="muted">—</span>}
                      {t.memo && <span className="muted small">{` · ${t.memo}`}</span>}
                    </td>
                    <td className="mono muted small">#{t.txn_id}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </div>

      {partners.length > 0 && (
        <div className="card">
          <div className="card-title">Top counterparties <span className="sub">most-frequent partners</span>
            <span className="toolbar-export" style={{ marginLeft: 'auto' }}>
              <CsvButton
                filename={`${prefix}-counterparties.csv`}
                headers={['Account ID', 'Name', 'Type', 'Transactions', 'Total volume']}
                rows={partners.map((p) => [
                  p.counterparty_id,
                  accountLabel({ display_name: p.display_name, owner_name: p.owner_name, owner_uuid: p.owner_uuid, account_id: p.counterparty_id }),
                  p.account_type,
                  p.txn_count,
                  p.total_volume,
                ])}
              />
              <JsonButton filename={`${prefix}-counterparties.json`} data={partners} />
            </span>
          </div>
          <div className="table-wrap">
            <table className="data-table">
              <thead>
                <tr>
                  <th>Counterparty</th>
                  <th>Type</th>
                  <th className="amount">Transactions</th>
                  <th className="amount">Total volume</th>
                </tr>
              </thead>
              <tbody>
                {partners.map((p) => (
                  <tr key={p.counterparty_id}>
                    <td>
                      <Link href={`/accounts/${p.counterparty_id}` as Route} className="rowlink" prefetch={false}>
                        <span style={{ fontWeight: 500 }}>
                          {accountLabel({ display_name: p.display_name, owner_name: p.owner_name, owner_uuid: p.owner_uuid, account_id: p.counterparty_id })}
                        </span>
                        <span className="mono muted small">{` #${p.counterparty_id}`}</span>
                      </Link>
                    </td>
                    <td><span className={`badge badge-${p.account_type}`}>{p.account_type}</span></td>
                    <td className="amount mono">{fmtN(p.txn_count)}</td>
                    <td className="amount neutral">{fmtAmtFull(p.total_volume)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </>
  );
}
