import 'server-only';
import { sql } from 'kysely';

/**
 * Aggregate estimate only (PAR-159), never a sale identity or an audit view.
 * The Oct 17 task evidence measured adjacent SYSTEM credit/debit postings with
 * equal amounts, plugin/message and <=1s separation. No global plugin whitelist:
 * unmatched rewards, fees and escrow must remain in the aggregates.
 *
 * Order only SYSTEM postings in the window (+1s for its boundary) with LAG.
 * The intervening-posting guard preserves exact posting-ID adjacency even if
 * settlement times are out of order. It MUST be an inner primary-key range join:
 * a correlated predecessor/outer range join scans whole accounts per candidate.
 * Both transactions must be balanced two-posting transfers to non-SYSTEM accounts.
 * Keep the outgoing leg's settlement date; discard the incoming leg's volume.
 * PAR-235 intentionally requires stricter uniqueness for persisted sale links.
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
      -- Prevent derived-table merging from duplicating the candidate joins.
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
    -- An old-settled posting can be absent from the window yet sit between two
    -- recent postings. Inner join permits MariaDB's range-checked access; the
    -- subsequent anti-join uses the materialized outgoing transaction IDs.
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
