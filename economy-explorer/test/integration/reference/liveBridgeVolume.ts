import { sql } from 'kysely';
import { db } from '@/lib/db';

// Test-only reference implementation (PAR-541). These are the live-ledger
// queries the overview used to run in the request path: the bridge-pair CTE
// (PAR-159) and the daily volume / period comparison built on it. A cold run
// over 30 days hit the 15 s statement timeout on production-sized data, so the
// pages now read explorer_rollup_economy_day instead (lib/rollup computes the
// same figures one day at a time). They stay here, unchanged, as the oracle the
// rollup equality tests (rollup.test.ts) and the PAR-159 edge-case tests
// (sql.test.ts) compare against. Nothing under app/ or lib/ may import this.

/**
 * Aggregate estimate only (PAR-159), never a sale identity or an audit view.
 * Adjacent SYSTEM credit/debit postings with equal amounts, plugin/message and
 * <=1s separation count as one event; both transactions must be balanced
 * two-posting transfers to non-SYSTEM accounts. Keep the outgoing leg's
 * settlement date; discard the incoming leg's volume.
 */
export function bridgePairs(days: number) {
  return sql`
    bridge_postings AS (
      SELECT p.posting_id,
             LAG(p.posting_id) OVER (PARTITION BY p.account_id ORDER BY p.posting_id) AS previous_id
      FROM ledger_txns t FORCE INDEX (idx_ledger_settle_time)
      STRAIGHT_JOIN ledger_postings p ON p.txn_id = t.txn_id
      STRAIGHT_JOIN accounts a ON a.account_id = p.account_id AND a.account_type = 'SYSTEM'
      WHERE t.settlement_time >= NOW() - INTERVAL ${days} DAY - INTERVAL 1 SECOND
    ),
    bridge_candidates AS (
      SELECT DISTINCT credit.txn_id AS incoming_txn, debit.txn_id AS outgoing_txn,
             credit.posting_id AS credit_id, debit.posting_id AS debit_id, debit.account_id AS system_acct,
             payer.account_id AS src_acct, payee.account_id AS dst_acct,
             credit.amount AS amt, outgoing.settlement_time, outgoing.message
      FROM bridge_postings ordered
      STRAIGHT_JOIN ledger_postings debit ON debit.posting_id = ordered.posting_id AND debit.amount < 0
      STRAIGHT_JOIN ledger_txns outgoing ON outgoing.txn_id = debit.txn_id
      STRAIGHT_JOIN ledger_postings credit ON credit.posting_id = ordered.previous_id
      STRAIGHT_JOIN ledger_txns incoming ON incoming.txn_id = credit.txn_id
      STRAIGHT_JOIN ledger_postings payer ON payer.txn_id = credit.txn_id
        AND payer.account_id != credit.account_id AND payer.amount = -credit.amount
      STRAIGHT_JOIN accounts source ON source.account_id = payer.account_id AND source.account_type != 'SYSTEM'
      STRAIGHT_JOIN ledger_postings payee ON payee.txn_id = debit.txn_id
        AND payee.account_id != debit.account_id AND payee.amount = -debit.amount
      STRAIGHT_JOIN accounts destination ON destination.account_id = payee.account_id AND destination.account_type != 'SYSTEM'
      WHERE outgoing.settlement_time >= NOW() - INTERVAL ${days} DAY
        AND credit.amount = -debit.amount
        AND incoming.settlement_time BETWEEN outgoing.settlement_time - INTERVAL 1 SECOND AND outgoing.settlement_time
        AND BINARY incoming.plugin_system = BINARY outgoing.plugin_system
        AND BINARY (CASE WHEN BINARY incoming.message LIKE 'Vault withdraw (%)'
          THEN CONCAT('Vault deposit ', SUBSTRING(incoming.message, 16)) ELSE incoming.message END) = BINARY outgoing.message
        AND (SELECT COUNT(*) FROM ledger_postings p WHERE p.txn_id = credit.txn_id) = 2
        AND (SELECT COUNT(*) FROM ledger_postings p WHERE p.txn_id = debit.txn_id) = 2
    ),
    bridge_blocked AS (
      SELECT DISTINCT c.outgoing_txn
      FROM bridge_candidates c
      STRAIGHT_JOIN ledger_postings intervening FORCE INDEX (PRIMARY)
        ON intervening.posting_id > c.credit_id AND intervening.posting_id < c.debit_id
        AND intervening.account_id = c.system_acct
    ),
    bridge_pairs AS (
      SELECT c.* FROM bridge_candidates c
      LEFT JOIN bridge_blocked b ON b.outgoing_txn = c.outgoing_txn
      WHERE b.outgoing_txn IS NULL
    )
  `;
}

export interface DailyVolumePoint { date: string; txn_count: number; total_volume: string }

/** Daily economic-event count and volume over a rolling window, bridge pairs counted once. */
export async function getDailyVolume(days: number): Promise<DailyVolumePoint[]> {
  const r = await sql<{ txn_date: string; txn_count: string | number; total_volume: string }>`
    WITH ${bridgePairs(days)}
    SELECT DATE_FORMAT(DATE(settlement_time), '%Y-%m-%d') AS txn_date,
           COUNT(*) AS txn_count,
           COALESCE(SUM(volume), 0.00) AS total_volume
    FROM ledger_txn_volume v
    WHERE settlement_time >= NOW() - INTERVAL ${days} DAY
      AND NOT EXISTS (SELECT 1 FROM bridge_pairs b WHERE b.incoming_txn = v.txn_id)
    GROUP BY DATE(settlement_time)
    ORDER BY txn_date ASC
  `.execute(db);
  return r.rows.map((row) => ({ date: row.txn_date, txn_count: Number(row.txn_count), total_volume: row.total_volume }));
}

/** Equal rolling windows evaluated in one statement, so their boundary agrees. */
export async function getActivityComparison(days: number) {
  const r = await sql<{ period: string; transactions: string | number; volume: string }>`
    WITH ${bridgePairs(days * 2)}
    SELECT CASE WHEN settlement_time >= NOW() - INTERVAL ${days} DAY THEN 'current' ELSE 'previous' END AS period,
      COUNT(*) AS transactions,
      COALESCE(SUM(volume), 0) AS volume
    FROM ledger_txn_volume v
    WHERE settlement_time >= NOW() - INTERVAL ${days * 2} DAY
      AND NOT EXISTS (SELECT 1 FROM bridge_pairs b WHERE b.incoming_txn = v.txn_id)
    GROUP BY period
  `.execute(db);
  const current = r.rows.find((row) => row.period === 'current');
  const previous = r.rows.find((row) => row.period === 'previous');
  return { current: { transactions: Number(current?.transactions ?? 0), volume: Number(current?.volume ?? 0) },
    previous: { transactions: Number(previous?.transactions ?? 0), volume: Number(previous?.volume ?? 0) } };
}
