import 'server-only';
import { sql } from 'kysely';
import { db, uuidToBin } from '@/lib/db';

/**
 * Whether the player can read at least one active GOVERNMENT account: the
 * same scope as getGovernmentAccounts (ownership or account_read_access_web),
 * stopped at the first match. Drives the top bar's Government entry (§10.1).
 * Walks the handful of GOVERNMENT accounts by idx_accounts_type and probes the
 * relationship view by (account_id, subject).
 */
export async function hasGovernmentAccountInScope(playerUuid: string): Promise<boolean> {
  const bin = uuidToBin(playerUuid);
  const r = await sql<{ in_scope: number }>`
    SELECT EXISTS (
      SELECT 1 FROM accounts a
       WHERE a.account_type = 'GOVERNMENT' AND a.is_archived = 0
         AND (a.owner_uuid_bin = ${bin}
           OR EXISTS (SELECT 1 FROM account_read_access_web ar
                       WHERE ar.account_id = a.account_id AND ar.subject_uuid_bin = ${bin}))
    ) AS in_scope
  `.execute(db);
  return Number(r.rows[0]?.in_scope ?? 0) === 1;
}

/**
 * Balances of the PERSONAL accounts the player owns, as DECIMAL strings. Owned
 * only: an account someone shared with them is not their money, and firm or
 * government accounts never count (spec §3 rule 5). Archived accounts are
 * included because the ledger is shown as it is. Uses idx_accounts_owner.
 */
export async function findPersonalBalances(playerUuid: string): Promise<string[]> {
  const r = await sql<{ balance: string }>`
    SELECT COALESCE(abm.balance, 0.00) AS balance
      FROM accounts a
      LEFT JOIN account_balances_mat abm ON abm.account_id = a.account_id
     WHERE a.owner_uuid_bin = ${uuidToBin(playerUuid)} AND a.account_type = 'PERSONAL'
  `.execute(db);
  return r.rows.map((row) => String(row.balance));
}
