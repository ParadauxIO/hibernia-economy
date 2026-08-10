import { buildMetadata } from '@/lib/metadata';
import { flattenSearchParams } from '@/lib/util/searchParams';
import { z } from 'zod';
import { getMoneyFlow } from '@/lib/sql/moneyFlow';
import { memo } from '@/lib/cache';
import { fmtAmtFull, fmtN } from '@/lib/format';
import { MoneyFlowDiagram } from '@/components/charts/MoneyFlowDiagram';
import { SectionTabs } from '@/components/SectionTabs';
import { WindowTabs } from '@/components/WindowTabs';
import { InfoTip } from '@/components/InfoTip';

const SP_SCHEMA = z.object({ days: z.coerce.number().int().min(1).max(30).default(30) });

export const dynamic = 'force-dynamic';

export async function generateMetadata() {
  return buildMetadata({ title: "Money Flow", description: "How money moves between account types across the {server} economy.", path: "/money-flow" });
}

export default async function MoneyFlowPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const raw = await searchParams;
  const sp = SP_SCHEMA.parse(flattenSearchParams(raw));
  // The money-flow self-join is the heaviest aggregate in the explorer (it was
  // effectively inaccessible uncached) — cache it per pod/fleet for 60s, keyed
  // by the day window, via the shared-Redis SWR cache.
  const edges = await memo(`money-flow:${sp.days}`, 60_000, () => getMoneyFlow(sp.days));

  // Roll up totals per (from, to) pair for the header KPIs.
  const totalAmount = edges.reduce((s, e) => s + parseFloat(e.amount), 0);
  const totalTxns = edges.reduce((s, e) => s + e.txn_count, 0);

  return (
    <>
      <div className="page-heading">
        <h1>Money flow</h1>
        <span className="sub">between account types · last {sp.days} days</span>
        <WindowTabs basePath="/money-flow" days={sp.days} />
        <SectionTabs />
      </div>

      <p className="page-intro">
        Money moving <strong>between different account types</strong> — personal, business,
        government, system (e.g. a player paying a business, or government issuing a grant).
        Transfers within the same type (personal&nbsp;→&nbsp;personal) are excluded.
      </p>

      <div className="kpi-grid">
        <div className="kpi">
          <div className="kpi-label">Total flow</div>
          <div className="kpi-value">{fmtAmtFull(totalAmount)}</div>
          <div className="kpi-meta">across {edges.length} type pairs</div>
        </div>
        <div className="kpi">
          <div className="kpi-label">
            Transactions
            <InfoTip>
              Count of transactions that moved money between accounts of different types.
              Same-type transfers (e.g. personal → personal) aren&rsquo;t counted here.
            </InfoTip>
          </div>
          <div className="kpi-value">{fmtN(totalTxns)}</div>
          <div className="kpi-meta">between different types</div>
        </div>
      </div>

      <div className="card sankey-card">
        <div className="card-title">
          Flow diagram
          <span className="sub">arrow width = flow magnitude · excludes initial player funding</span>
        </div>
        <MoneyFlowDiagram edges={edges} />
      </div>

      <div className="card">
        <div className="card-title">
          Flows by account-type pair <span className="sub">sorted by total amount</span>
        </div>
        <div className="table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th>From</th>
                <th>To</th>
                <th className="amount">Amount</th>
                <th className="amount">Transactions</th>
              </tr>
            </thead>
            <tbody>
              {edges.length === 0 && (
                <tr>
                  <td colSpan={4} className="empty">No cross-type flows in this window.</td>
                </tr>
              )}
              {edges.map((e) => (
                <tr key={`${e.from_type}-${e.to_type}`}>
                  <td><span className={`badge badge-${e.from_type}`}>{e.from_type}</span></td>
                  <td><span className={`badge badge-${e.to_type}`}>{e.to_type}</span></td>
                  <td className="amount neutral">{fmtAmtFull(e.amount)}</td>
                  <td className="amount mono">{fmtN(e.txn_count)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </>
  );
}

