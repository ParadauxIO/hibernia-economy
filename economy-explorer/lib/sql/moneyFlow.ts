import 'server-only';
import { sql } from 'kysely';
import { db } from '@/lib/db';

// Money flow between account types, from explorer_rollup_account_day (V43).
// Each account-day row records its counterparty's account type (the largest
// opposite-signed leg of the same transaction), so summing outflows by
// (payer type, counterparty type) gives the type-to-type matrix without
// touching the ledger. Days are 'YYYY-MM-DD' UTC, inclusive.
//
// The ledger is shown as it is (spec §3 rule 8): a Vault bridge transfer is two
// ledger transactions through a SYSTEM account, so it appears here as
// PERSONAL → SYSTEM and SYSTEM → PERSONAL, and minting accounts appear as
// GOVERNMENT or SYSTEM payers. Transfers between an account holder's own
// accounts (is_internal) are left out: they move no money between parties.

export interface TypeFlowRow {
  from_type: string;
  to_type: string;
  /** DECIMAL string: money paid by `from_type` accounts to `to_type` accounts. */
  amount: string;
}

/**
 * Outflows over [from, to] grouped by payer account type and counterparty type.
 * Reads the day index (idx_erad_day) and one accounts primary-key lookup per
 * row: roughly 3k rows a day on production, so a year is about a million rows.
 */
export async function selectTypeFlows(from: string, to: string): Promise<TypeFlowRow[]> {
  const r = await sql<TypeFlowRow>`
    SELECT a.account_type AS from_type, d.counterparty_type AS to_type, SUM(d.outflow) AS amount
    FROM explorer_rollup_account_day d FORCE INDEX (idx_erad_day)
    STRAIGHT_JOIN accounts a ON a.account_id = d.account_id
    WHERE d.day BETWEEN ${from} AND ${to}
      AND d.outflow > 0 AND d.counterparty_type <> '' AND d.is_internal = 0
    GROUP BY a.account_type, d.counterparty_type
    ORDER BY amount DESC, from_type, to_type
  `.execute(db);
  return r.rows.map((row) => ({ from_type: row.from_type, to_type: row.to_type, amount: String(row.amount) }));
}
