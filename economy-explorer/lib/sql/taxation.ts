import 'server-only';
import { sql } from 'kysely';
import { db } from '@/lib/db';
import type { TaxKind } from '@/lib/taxation';

export interface TaxRevenueRow {
  date: string;
  kind: TaxKind;
  account_id: number;
  display_name: string | null;
  collected: string;
  refunded: string;
}

/** Public aggregates only: never return payer identities, reasons or transaction IDs. */
export async function getTaxRevenue(days: number): Promise<TaxRevenueRow[]> {
  const result = await sql<TaxRevenueRow>`
    SELECT DATE_FORMAT(revenue.settlement_time, '%Y-%m-%d') AS date,
           revenue.kind, revenue.account_id, revenue.display_name,
           CAST(SUM(CASE WHEN revenue.amount > 0 THEN revenue.amount ELSE 0 END) AS CHAR) AS collected,
           CAST(SUM(CASE WHEN revenue.amount < 0 THEN -revenue.amount ELSE 0 END) AS CHAR) AS refunded
    FROM (
      SELECT lt.settlement_time, lp.amount, a.account_id, a.display_name,
        CASE
          -- TaxCollection.taxType is NOT persisted. These plugin/message pairs
          -- are emitted by BalanceTaxServiceImpl, FirmBalanceTaxServiceImpl,
          -- TaxApiImpl.collectSourceIncomeTax and ChestShop EconomyServiceImpl.
          WHEN lp.amount > 0 AND lt.plugin_system = 'treasury'
            AND lt.message LIKE 'Personal balance tax (%' THEN 'personal'
          WHEN lp.amount > 0 AND lt.plugin_system = 'business'
            AND lt.message = 'Weekly Corporate Balance Tax' THEN 'corporate'
          WHEN lp.amount > 0 AND lt.plugin_system = 'treasury'
            AND lt.message LIKE 'Income tax on deposit from %' THEN 'income'
          WHEN lp.amount > 0 AND lt.plugin_system = 'ChestShop'
            AND lt.message LIKE 'ChestShop sales tax (%' THEN 'sales'
          -- Link actual fines and their reversals, not user-supplied reason text.
          -- EXISTS avoids multiplying postings if historical fine rows duplicate.
          WHEN lp.amount > 0 AND EXISTS (
            SELECT 1 FROM government_fines gf
            WHERE gf.txn_id = lt.txn_id AND gf.gov_account_id = lp.account_id
          ) THEN 'fines'
          WHEN lp.amount < 0 AND EXISTS (
            SELECT 1 FROM government_fines gf
            WHERE gf.revoke_txn_id = lt.txn_id AND gf.gov_account_id = lp.account_id
          ) THEN 'fines'
        END AS kind
      FROM ledger_txns lt
      JOIN ledger_postings lp ON lp.txn_id = lt.txn_id
      JOIN accounts a ON a.account_id = lp.account_id
      WHERE lt.settlement_time >= UTC_TIMESTAMP() - INTERVAL ${days} DAY
        AND lt.settlement_time <= UTC_TIMESTAMP()
        AND a.account_type = 'GOVERNMENT'
    ) revenue
    WHERE revenue.kind IS NOT NULL
    GROUP BY date, revenue.kind, revenue.account_id, revenue.display_name
    ORDER BY date, revenue.kind, revenue.account_id
  `.execute(db);
  return result.rows;
}
