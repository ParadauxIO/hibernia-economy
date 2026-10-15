import Link from 'next/link';
import type { Route } from 'next';
import { buildMetadata } from '@/lib/metadata';
import { getWealthData } from '@/lib/services/economy';
import { accountLabel, fmtAmtFull, fmtN, fmtPct } from '@/lib/format';
import { SectionTabs } from '@/components/SectionTabs';
import { InfoTip } from '@/components/InfoTip';
import { WealthDonut, BalanceDistribution } from '@/components/charts/AreaTimeline';
export const dynamic = 'force-dynamic';
export async function generateMetadata() { return buildMetadata({ title: 'Wealth', description: 'Wealth distribution and balances in the {server} economy.', path: '/economy/wealth' }); }
export default async function WealthPage() {
  const { stats, distribution, giniValue, top1, top10 } = await getWealthData();
  return <>
    <div className="page-heading"><h1>Wealth</h1><span className="sub">Current balances</span><SectionTabs /></div>
    <p className="page-intro">How balances are distributed across open accounts. These are current holdings, not income or recent activity.</p>
      <div className="insight-strip">
        <div className="insight">
          <div className="ico">G</div>
          <div className="body">
            <div className="label">
              Gini (wealth)
              <InfoTip>
                Inequality of personal balances. 0 = everyone equal; 1 = all wealth in one
                account. Computed over every non-archived personal account with a positive balance.
              </InfoTip>
            </div>
            <div className="value">{giniValue != null ? giniValue.toFixed(3) : '—'}</div>
          </div>
        </div>
        <div className="insight">
          <div className="ico">1%</div>
          <div className="body">
            <div className="label">
              Top 1% share
              <InfoTip>Share of all personal wealth held by the richest 1% of accounts.</InfoTip>
            </div>
            <div className="value">{top1 != null ? fmtPct(top1) : '—'}</div>
          </div>
        </div>
        <div className="insight">
          <div className="ico">10%</div>
          <div className="body">
            <div className="label">
              Top 10% share
              <InfoTip>Share of all personal wealth held by the richest 10% of accounts.</InfoTip>
            </div>
            <div className="value">{top10 != null ? fmtPct(top10) : '—'}</div>
          </div>
        </div>
      </div>


    <div className="charts-grid">
      <div className="chart-card"><h2 className="chart-title">Personal balance distribution</h2><p className="chart-subtitle">Positive personal balances only</p><BalanceDistribution data={distribution} /></div>
      <div className="chart-card"><h2 className="chart-title">Positive net balances by type</h2><p className="chart-subtitle">System and non-positive type totals excluded; signed totals below</p><WealthDonut data={stats.byType.filter(b => b.account_type !== 'SYSTEM')} /></div>
    </div>
      <div className="card">
        <div className="card-title">Balance summary by type <span className="sub">aggregate · all accounts</span></div>
        <div className="table-wrap directory-wrap">
          <table className="data-table directory-table">
            <thead>
              <tr>
                <th>Type</th>
                <th data-secondary="true" className="amount">Accounts</th>
                <th className="amount">Total balance</th>
                <th data-secondary="true" className="amount">Avg balance</th>
              </tr>
            </thead>
            <tbody>
              {stats.byType.map((b) => {
                const total = parseFloat(b.total_balance);
                const avg = b.account_count > 0 ? total / b.account_count : 0;
                return (
                  <tr key={b.account_type}>
                    <td><span className={`badge badge-${b.account_type}`}>{b.account_type}</span><details className="mobile-only directory-details"><summary>Details</summary><p>{fmtN(b.account_count)} accounts · average {fmtAmtFull(avg)}</p></details></td>
                    <td data-secondary="true" className="amount mono">{fmtN(b.account_count)}</td>
                    <td className="amount neutral">{fmtAmtFull(b.total_balance)}</td>
                    <td data-secondary="true" className="amount neutral">{fmtAmtFull(avg)}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </div>

      <div className="card">
        <div className="card-title">Top accounts by balance <span className="sub">click to drill in</span></div>
        <div className="table-wrap directory-wrap">
          <table className="data-table directory-table">
            <thead>
              <tr><th>Account</th><th data-secondary="true">Type</th><th className="amount">Balance</th></tr>
            </thead>
            <tbody>
              {stats.topAccounts.map((a) => (
                <tr key={a.account_id}>
                  <td>
                    <Link href={`/accounts/${a.account_id}` as Route} className="rowlink" prefetch={false}>
                      <span style={{ fontWeight: 500 }}>
                        {accountLabel({
                          display_name: a.display_name,
                          owner_name: a.owner_name,
                          owner_uuid: a.owner_uuid,
                          account_id: a.account_id,
                        })}
                      </span>
                      <span className="mono muted small">{` #${a.account_id}`}</span>
                    </Link>
                    <details className="mobile-only directory-details"><summary>Details</summary><p>{a.account_type}</p></details>
                  </td>
                  <td data-secondary="true"><span className={`badge badge-${a.account_type}`}>{a.account_type}</span></td>
                  <td className="amount neutral">{fmtAmtFull(a.balance)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
</>;
}
