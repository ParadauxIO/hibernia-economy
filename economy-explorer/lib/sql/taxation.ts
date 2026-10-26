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

/**
 * Tax and fine revenue per UTC day, kind and government account over
 * [from, to] (inclusive 'YYYY-MM-DD'), from explorer_rollup_account_day (V43).
 *
 * The rollup tags each account-day row with V38's tax_kind (personal,
 * corporate, income, sales), or 'fine' when the transaction is a recorded
 * government fine or its revocation. Collected is what government accounts
 * received under a tax kind; refunded is what they paid back on revoked fines.
 * A tax-tagged debit from a government account is not revenue and is ignored.
 *
 * Starts from the GOVERNMENT accounts (idx_accounts_type, a few dozen rows) and
 * reads each one's primary-key range of days, so the cost is independent of
 * how busy the rest of the economy was. Archived accounts are included.
 * Public aggregates only: never payer identities, reasons or transaction IDs.
 */
export async function getTaxRevenue(from: string, to: string): Promise<TaxRevenueRow[]> {
  const result = await sql<Omit<TaxRevenueRow, 'kind'> & { tax_kind: string }>`
    SELECT DATE_FORMAT(d.day, '%Y-%m-%d') AS date, d.tax_kind, a.account_id, a.display_name,
           CAST(SUM(d.inflow) AS CHAR) AS collected,
           CAST(SUM(CASE WHEN d.tax_kind = 'fine' THEN d.outflow ELSE 0 END) AS CHAR) AS refunded
    FROM accounts a FORCE INDEX (idx_accounts_type)
    STRAIGHT_JOIN explorer_rollup_account_day d ON d.account_id = a.account_id AND d.day BETWEEN ${from} AND ${to}
    WHERE a.account_type = 'GOVERNMENT'
      AND d.tax_kind IN ('personal', 'corporate', 'income', 'sales', 'fine')
    GROUP BY d.day, d.tax_kind, a.account_id, a.display_name
    HAVING SUM(d.inflow) <> 0 OR SUM(CASE WHEN d.tax_kind = 'fine' THEN d.outflow ELSE 0 END) <> 0
    ORDER BY date, d.tax_kind, a.account_id
  `.execute(db);
  return result.rows.map(({ tax_kind, ...row }) => ({
    ...row,
    account_id: Number(row.account_id),
    kind: (tax_kind === 'fine' ? 'fines' : tax_kind) as TaxKind,
  }));
}
