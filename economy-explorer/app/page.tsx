import { buildMetadata } from '@/lib/metadata';
import Link from 'next/link';
import type { Route } from 'next';
import { memo } from '@/lib/cache';
import { getEconomyStats } from '@/lib/sql/ledger';
import { getTxnVolume, getBalanceDistribution, getHourHeatmap, getPersonalBalances } from '@/lib/sql/stats';
import { SectionTabs } from '@/components/SectionTabs';
import { accountLabel, fmtAmt, fmtAmtFull, fmtN, fmtPct } from '@/lib/format';
import { gini, topShare } from '@/lib/derived';
import { VolumeTimeline, WealthDonut, BalanceDistribution } from '@/components/charts/AreaTimeline';
import { Heatmap } from '@/components/charts/Heatmap';
import { Sparkline } from '@/components/charts/Sparkline';
import { AutoRefresh } from '@/components/AutoRefresh';
import { InfoTip } from '@/components/InfoTip';

export const dynamic = 'force-dynamic';

// Dashboard aggregates are tenant-global and slow-moving. Cache them in shared
// Redis on a 30s epoch-aligned window (lib/cache: single-flight + stale-while-
// revalidate) so the full-table scans run at most once per 30s for the whole
// fleet, never block a request, and reset on the same wall-clock boundaries the
// client's AutoRefresh ticks on. 30s matches DASHBOARD_REFRESH_MS below.
const DASHBOARD_REFRESH_MS = 30_000;

const getDashboardData = () =>
  memo('dashboard-aggregates', DASHBOARD_REFRESH_MS, async () => {
    const [stats, volume, distribution, heat, balances] = await Promise.all([
      getEconomyStats(),
      getTxnVolume(30),
      getBalanceDistribution(),
      getHourHeatmap(30),
      getPersonalBalances(),
    ]);
    // Derive the inequality scalars here so the cached payload stays small: the
    // raw per-account balance array (getPersonalBalances, up to hundreds of KB)
    // never leaves this function or hits Redis.
    return {
      stats,
      volume,
      distribution,
      heat,
      giniValue: balances.length ? gini(balances) : null,
      top1: balances.length ? topShare(balances, 0.01) : null,
      top10: balances.length ? topShare(balances, 0.10) : null,
    };
  });

export async function generateMetadata() {
  return buildMetadata({ description: "Live ledger explorer for the {server} economy — balances, transactions, firms and ChestShop market activity.", path: "/" });
}

export default async function DashboardPage() {
  const { stats, volume, distribution, heat, giniValue, top1, top10 } = await getDashboardData();

  const windowTxns = volume.reduce((s, v) => s + v.txn_count, 0);
  const volSeries = volume.map((v) => parseFloat(v.total_volume));
  const windowVolume = volSeries.reduce((s, x) => s + x, 0);

  // Money velocity = 30d volume ÷ player-held (personal) supply. The non-SYSTEM
  // total can go negative (business/government liabilities net below zero), so
  // personal supply is the meaningful circulating denominator.
  const personal = parseFloat(stats.personalSupply);
  const velocity = personal > 0 ? windowVolume / personal : null;


  const sparkVol = volume.map((v) => ({ value: parseFloat(v.total_volume) }));
  const sparkCnt = volume.map((v) => ({ value: v.txn_count }));

  return (
    <>
      <div className="page-heading">
        <h1>Economy</h1>
        <span className="sub">last 30d window</span>
        <AutoRefresh windowMs={DASHBOARD_REFRESH_MS} />
        <SectionTabs />
      </div>

      <div className="kpi-grid">
        <div className="kpi">
          <div className="kpi-label">Personal supply</div>
          <div className="kpi-value">{fmtAmt(stats.personalSupply)}</div>
          <div className="kpi-meta">held by players</div>

        </div>
        <div className="kpi">
          <div className="kpi-label">Open accounts</div>
          <div className="kpi-value">{fmtN(stats.totalAccounts)}</div>
          <div className="kpi-meta">{fmtN(stats.archivedAccounts)} archived</div>
        </div>
        <div className="kpi">
          <div className="kpi-label">Transactions</div>
          <div className="kpi-value">{fmtN(windowTxns)}</div>
          <div className="kpi-meta">last 30 days</div>
          <div className="kpi-spark"><Sparkline data={sparkCnt} stroke="var(--c-3)" /></div>
        </div>
        <div className="kpi">
          <div className="kpi-label">Volume</div>
          <div className="kpi-value">{fmtAmt(windowVolume)}</div>
          <div className="kpi-meta">last 30 days</div>
          <div className="kpi-spark"><Sparkline data={sparkVol} stroke="var(--c-2)" /></div>
        </div>
      </div>

      <div className="insight-strip">
        <div className="insight">
          <div className="ico">v</div>
          <div className="body">
            <div className="label">
              Money velocity
              <InfoTip>
                Total transaction volume over the last 30 days ÷ player-held (personal)
                balances. Higher = money moving faster.
              </InfoTip>
            </div>
            <div className="value">{velocity != null ? `${velocity.toFixed(2)}×` : '—'}</div>
          </div>
        </div>
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

      <div className="charts-grid wide-left">
        <div className="chart-card">
          <div className="chart-header">
            <div>
              <div className="chart-title">Transaction activity</div>
              <div className="chart-subtitle">Volume and count, last 30 days</div>
            </div>
            <span className="chart-tag">$ + #</span>
          </div>
          <VolumeTimeline data={volume} />
        </div>
        <div className="chart-card">
          <div className="chart-header">
            <div>
              <div className="chart-title">Positive net balances by type</div>
              <div className="chart-subtitle">Types with a positive net balance; negative totals excluded</div>
            </div>
          </div>
          {/* Exclude SYSTEM — it's plumbing (mint/sink), not held wealth. */}
          <WealthDonut data={stats.byType.filter((b) => b.account_type !== 'SYSTEM')} />
        </div>
      </div>

      <div className="charts-grid">
        <div className="chart-card">
          <div className="chart-header">
            <div>
              <div className="chart-title">Balance distribution</div>
              <div className="chart-subtitle">Personal accounts bucketed · empty (0) excluded</div>
            </div>
          </div>
          <BalanceDistribution data={distribution} />
        </div>
        <div className="chart-card">
          <div className="chart-header">
            <div>
              <div className="chart-title">When the economy moves</div>
              <div className="chart-subtitle">hour × day of week · last 30d</div>
            </div>
          </div>
          <Heatmap cells={heat} />
        </div>
      </div>

      <div className="card">
        <div className="card-title">Balance summary by type <span className="sub">aggregate · all accounts</span></div>
        <div className="table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th>Type</th>
                <th className="amount">Accounts</th>
                <th className="amount">Total balance</th>
                <th className="amount">Avg balance</th>
              </tr>
            </thead>
            <tbody>
              {stats.byType.map((b) => {
                const total = parseFloat(b.total_balance);
                const avg = b.account_count > 0 ? total / b.account_count : 0;
                return (
                  <tr key={b.account_type}>
                    <td><span className={`badge badge-${b.account_type}`}>{b.account_type}</span></td>
                    <td className="amount mono">{fmtN(b.account_count)}</td>
                    <td className="amount neutral">{fmtAmtFull(b.total_balance)}</td>
                    <td className="amount neutral">{fmtAmtFull(avg)}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </div>

      <div className="card">
        <div className="card-title">Top accounts by balance <span className="sub">click to drill in</span></div>
        <div className="table-wrap">
          <table className="data-table">
            <thead>
              <tr><th>Account</th><th>Type</th><th className="amount">Balance</th></tr>
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
                  </td>
                  <td><span className={`badge badge-${a.account_type}`}>{a.account_type}</span></td>
                  <td className="amount neutral">{fmtAmtFull(a.balance)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </>
  );
}
