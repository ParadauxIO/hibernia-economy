import 'server-only';
import { sql } from 'kysely';
import { db } from '@/lib/db';
import { bridgePairs } from './bridgeAnalytics';

export interface MoneyFlowEdge {
  from_type: string;
  to_type: string;
  amount: string;
  /** `amount` minus starting-balance seed transfers ("Initial player funds").
   * Used by the flow diagram so per-player account seeding — a huge, one-off
   * GOVERNMENT→PERSONAL flow — doesn't crush every organic edge to a hairline.
   * The table/KPIs still use the full `amount`. */
  amount_ex_seed: string;
  txn_count: number;
}

/** Clean two-posting transfers, with estimated bridge pairs replaced before
 * grouping by account type. Settlement indexes bound both candidate scans. */
export async function getMoneyFlow(days: number): Promise<MoneyFlowEdge[]> {
  const r = await sql<{ fromType: string; toType: string; amount: string; amountExSeed: string; txnCount: string | number }>`
    WITH ${bridgePairs(days)}, transfers AS (
      SELECT lt.txn_id, lt.message,
             MAX(CASE WHEN lp.amount < 0 THEN lp.account_id END) AS src_acct,
             MAX(CASE WHEN lp.amount > 0 THEN lp.account_id END) AS dst_acct,
             MAX(CASE WHEN lp.amount > 0 THEN lp.amount END) AS amt
      FROM ledger_txns lt FORCE INDEX (idx_ledger_settle_time)
      STRAIGHT_JOIN ledger_postings lp ON lp.txn_id = lt.txn_id
      WHERE lt.settlement_time >= NOW() - INTERVAL ${days} DAY
        AND NOT EXISTS (SELECT 1 FROM bridge_pairs b WHERE b.incoming_txn = lt.txn_id)
        AND NOT EXISTS (SELECT 1 FROM bridge_pairs b WHERE b.outgoing_txn = lt.txn_id)
      GROUP BY lt.txn_id, lt.message
      HAVING COUNT(*) = 2 AND SUM(lp.amount) = 0 AND src_acct IS NOT NULL AND dst_acct IS NOT NULL
      UNION ALL
      SELECT outgoing_txn, message, src_acct, dst_acct, amt FROM bridge_pairs
    )
    SELECT a_src.account_type AS fromType,
           a_dst.account_type AS toType,
           SUM(d.amt) AS amount,
           SUM(CASE WHEN d.message = 'Initial player funds' THEN 0 ELSE d.amt END) AS amountExSeed,
           COUNT(*) AS txnCount
    FROM transfers d
    JOIN accounts a_src ON a_src.account_id = d.src_acct
    JOIN accounts a_dst ON a_dst.account_id = d.dst_acct
    WHERE a_src.account_type != a_dst.account_type
    GROUP BY a_src.account_type, a_dst.account_type
    HAVING SUM(d.amt) > 0
    ORDER BY amount DESC
  `.execute(db);
  return r.rows.map((row) => ({
    from_type: row.fromType,
    to_type: row.toType,
    amount: row.amount,
    amount_ex_seed: row.amountExSeed,
    txn_count: Number(row.txnCount),
  }));
}
