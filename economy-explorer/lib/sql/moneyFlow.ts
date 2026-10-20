import 'server-only';
import { sql } from 'kysely';
import { db } from '@/lib/db';

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

/** Aggregate only clean two-posting transfers in the selected time window.
 * The outer time index is essential: MariaDB otherwise scans all transaction
 * headers and evaluates the lateral posting aggregate millions of times.
 * With the range scan, the existing txn index resolves only recent postings. */
export async function getMoneyFlow(days: number): Promise<MoneyFlowEdge[]> {
  const r = await sql<{ fromType: string; toType: string; amount: string; amountExSeed: string; txnCount: string | number }>`
    SELECT a_src.account_type AS fromType,
           a_dst.account_type AS toType,
           SUM(d.amt) AS amount,
           -- Seed transfers carry this exact message (LedgerServiceImpl
           -- .resolveOrCreatePersonal → "Initial player funds"). Excluded from
           -- the diagram's magnitude so account seeding doesn't dwarf real flow.
           SUM(CASE WHEN lt.message = 'Initial player funds' THEN 0 ELSE d.amt END) AS amountExSeed,
           COUNT(*) AS txnCount
    FROM (
      -- Pivot the debit and credit; three or more postings are ambiguous.
      -- The outer settlement range drives the lateral aggregate; an additional
      -- transaction-ID cutoff adds work without narrowing these lookups.
      SELECT lp.txn_id,
             MAX(CASE WHEN lp.amount < 0 THEN lp.account_id END) AS src_acct,
             MAX(CASE WHEN lp.amount > 0 THEN lp.account_id END) AS dst_acct,
             MAX(CASE WHEN lp.amount > 0 THEN lp.amount END) AS amt,
             COUNT(*) AS n
      FROM ledger_postings lp
      GROUP BY lp.txn_id
      HAVING n = 2 AND src_acct IS NOT NULL AND dst_acct IS NOT NULL
    ) d
    JOIN ledger_txns lt FORCE INDEX (idx_ledger_settle_time) ON lt.txn_id = d.txn_id
    JOIN accounts a_src ON a_src.account_id = d.src_acct
    JOIN accounts a_dst ON a_dst.account_id = d.dst_acct
    WHERE a_src.account_type != a_dst.account_type
      AND lt.settlement_time >= NOW() - INTERVAL ${days} DAY
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
