import 'server-only';
import { sql } from 'kysely';
import { db, binToUuid, uuidToBin } from '@/lib/db';
import type { ExplorerAccountRow, CounterpartyRow } from '@/lib/sql/ledger';

/** Mirrors LedgerExplorerMapper.findAccountsForPlayer (line 625-635). */
export async function findAccountsForPlayer(playerUuid: string): Promise<ExplorerAccountRow[]> {
  const bin = uuidToBin(playerUuid);
  const r = await sql<{
    account_id: number;
    account_type: string;
    display_name: string | null;
    owner_uuid_bin: Buffer | null;
    owner_name: string | null;
    archived: number;
    requires_authorization: number;
    allow_overdraft: number;
    credit_limit: string;
    created_at: Date;
    balance: string;
  }>`
    SELECT a.account_id, a.account_type, a.display_name, a.owner_uuid_bin, fp.current_name AS owner_name,
           a.is_archived AS archived, a.requires_authorization, a.allow_overdraft,
           a.credit_limit, a.created_at, COALESCE(abm.balance, 0.00) AS balance
    FROM accounts a
    LEFT JOIN account_balances_mat abm ON abm.account_id = a.account_id
    LEFT JOIN economy_players fp ON fp.player_uuid_bin = a.owner_uuid_bin
    WHERE a.owner_uuid_bin = ${bin}
       -- Same WEB read rule as canReadAccount via the shared account_read_access_web
       -- view (ADT-13): VIEWER counts. Previously this list filtered MEMBER/AUTHORIZER
       -- only, so an account a player could open (canReadAccount) never appeared in
       -- their own "my accounts" list — the explorer disagreeing with itself.
       OR a.account_id IN (SELECT account_id FROM account_read_access_web
                           WHERE subject_uuid_bin = ${bin})
    ORDER BY balance DESC
  `.execute(db);
  return r.rows.map((row) => ({
    account_id: row.account_id,
    account_type: row.account_type,
    display_name: row.display_name,
    owner_uuid: row.owner_uuid_bin ? binToUuid(row.owner_uuid_bin) : null,
    owner_name: row.owner_name,
    archived: row.archived,
    requires_authorization: row.requires_authorization,
    allow_overdraft: row.allow_overdraft,
    credit_limit: row.credit_limit,
    created_at: row.created_at,
    balance: row.balance,
  }));
}

// The trajectory / count / counterparties queries below take the player's
// resolved account-id set (owner ∪ active members) rather than re-deriving it
// with a `IN (SELECT … UNION …)` correlated subquery on every call. The caller
// (the /me page) already fetches that set via findAccountsForPlayer, so we pass
// the ids straight in as `IN (?, ?, …)` — the optimizer can use idx_postings_account
// cleanly instead of materialising the UNION subquery (twice, in counterparties).

/**
 * Exact money rollups for the dashboard KPIs, summed in SQL (never folded
 * through a JS double). `totalBalance` is the current balance across the whole
 * account set; `income`/`spend`/`net` are the windowed credit/debit/net over the
 * last `days`. Returned as DECIMAL strings so the viewer matches the ledger to the
 * cent — the windowed net uses the same SUM path as its all-time siblings
 * (getTotalSupply) so the two can never disagree.
 */
export interface PlayerTotals {
  totalBalance: string;
  income: string;
  spend: string;
  net: string;
}

export async function getPlayerTotals(accountIds: number[], days: number): Promise<PlayerTotals> {
  if (accountIds.length === 0) return { totalBalance: '0.00', income: '0.00', spend: '0.00', net: '0.00' };
  const ids = sql.join(accountIds);
  const [bal, flow] = await Promise.all([
    sql<{ s: string | null }>`
      SELECT COALESCE(SUM(abm.balance), 0.00) AS s
      FROM account_balances_mat abm
      WHERE abm.account_id IN (${ids})
    `.execute(db),
    sql<{ income: string | null; spend: string | null; net: string | null }>`
      SELECT COALESCE(SUM(CASE WHEN lp.amount > 0 THEN lp.amount ELSE 0 END), 0.00) AS income,
             COALESCE(SUM(CASE WHEN lp.amount < 0 THEN -lp.amount ELSE 0 END), 0.00) AS spend,
             COALESCE(SUM(lp.amount), 0.00) AS net
      FROM ledger_postings lp
      JOIN ledger_txns lt ON lt.txn_id = lp.txn_id
      WHERE lp.account_id IN (${ids})
        AND lt.settlement_time >= NOW() - INTERVAL ${days} DAY
    `.execute(db),
  ]);
  return {
    totalBalance: bal.rows[0]?.s ?? '0.00',
    income: flow.rows[0]?.income ?? '0.00',
    spend: flow.rows[0]?.spend ?? '0.00',
    net: flow.rows[0]?.net ?? '0.00',
  };
}

/** Distinct txn count over the player's account set, last `days`. Mirrors countPlayerTransactions. */
export async function countPlayerTransactions(accountIds: number[], days: number): Promise<number> {
  if (accountIds.length === 0) return 0;
  const r = await sql<{ c: string | number }>`
    SELECT COUNT(DISTINCT lp.txn_id) AS c
    FROM ledger_postings lp JOIN ledger_txns lt ON lt.txn_id = lp.txn_id
    WHERE lp.account_id IN (${sql.join(accountIds)})
      AND lt.settlement_time >= NOW() - INTERVAL ${days} DAY
  `.execute(db);
  return Number(r.rows[0]?.c ?? 0);
}

/**
 * Top counterparties over the player's account set, last `days`. Mirrors
 * getPlayerCounterparties. The original was unbounded in time — an all-history
 * self-join of ledger_postings — which is what pinned /me at the 50s timeout for
 * busy accounts. Bounding to the page's window (joining ledger_txns for
 * settlement_time) keeps it consistent with the rest of /me and cheap.
 */
export async function getPlayerCounterparties(accountIds: number[], limit: number, days: number): Promise<CounterpartyRow[]> {
  if (accountIds.length === 0) return [];
  const ids = sql.join(accountIds);
  const r = await sql<{
    counterparty_id: number;
    display_name: string | null;
    account_type: string;
    owner_uuid_bin: Buffer | null;
    owner_name: string | null;
    txn_count: string | number;
    total_volume: string | null;
  }>`
    SELECT partner.account_id AS counterparty_id, a.display_name AS display_name,
           a.account_type AS account_type, a.owner_uuid_bin, fp.current_name AS owner_name,
           COUNT(*) AS txn_count, SUM(ABS(partner.amount)) AS total_volume
    FROM ledger_postings me
    JOIN ledger_txns lt ON lt.txn_id = me.txn_id
    JOIN ledger_postings partner ON partner.txn_id = me.txn_id AND partner.account_id != me.account_id
    JOIN accounts a ON a.account_id = partner.account_id
    LEFT JOIN economy_players fp ON fp.player_uuid_bin = a.owner_uuid_bin
    WHERE me.account_id IN (${ids})
      AND lt.settlement_time >= NOW() - INTERVAL ${days} DAY
      AND partner.account_id NOT IN (${ids})
    GROUP BY partner.account_id, a.display_name, a.account_type, a.owner_uuid_bin, fp.current_name
    ORDER BY total_volume DESC
    LIMIT ${limit}
  `.execute(db);
  return r.rows.map((row) => ({
    counterparty_id: row.counterparty_id,
    display_name: row.display_name,
    account_type: row.account_type,
    owner_uuid: row.owner_uuid_bin ? binToUuid(row.owner_uuid_bin) : null,
    owner_name: row.owner_name,
    txn_count: Number(row.txn_count),
    total_volume: row.total_volume ?? '0.00',
  }));
}

/** Resolve a player's current name from the economy_players cache (null if unknown). */
export async function findPlayerName(uuid: string): Promise<string | null> {
  const r = await sql<{ current_name: string | null }>`
    SELECT current_name FROM economy_players WHERE player_uuid_bin = ${uuidToBin(uuid)} LIMIT 1
  `.execute(db);
  return r.rows[0]?.current_name ?? null;
}

/** Name/UUID search over the economy_players cache, for the admin player lookup. */
export async function searchPlayers(q: string, limit: number): Promise<{ uuid: string; name: string }[]> {
  const r = await sql<{ player_uuid_bin: Buffer; current_name: string }>`
    SELECT player_uuid_bin, current_name FROM economy_players
    WHERE current_name LIKE CONCAT('%', ${q}, '%')
    ORDER BY current_name
    LIMIT ${limit}
  `.execute(db);
  return r.rows.map((row) => ({ uuid: binToUuid(row.player_uuid_bin), name: row.current_name }));
}

export interface PlayerTxnRow {
  posting_id: number;
  txn_id: number;
  account_id: number;
  account_name: string | null;
  account_type: string;
  amount: string;
  memo: string | null;
  message: string | null;
  settlement_time: Date | null;
  plugin_system: string | null;
}

/**
 * The player's most recent transactions across their whole account set, newest
 * first — so "My data" can show activity without drilling into each account.
 * Ordered by txn_id (monotonic) to walk the (account_id, txn_id) index without a
 * filesort (same reasoning as listAccountTransactions). Self-data only.
 */
export async function getPlayerTransactions(accountIds: number[], limit: number): Promise<PlayerTxnRow[]> {
  if (accountIds.length === 0) return [];
  const r = await sql<{
    posting_id: number;
    txn_id: number;
    account_id: number;
    account_name: string | null;
    account_type: string;
    amount: string;
    memo: string | null;
    message: string | null;
    settlement_time: Date | null;
    plugin_system: string | null;
  }>`
    SELECT lp.posting_id, lp.txn_id, lp.account_id,
           a.display_name AS account_name, a.account_type,
           lp.amount, lp.memo, lt.message, lt.settlement_time, lt.plugin_system
    FROM ledger_postings lp
    JOIN ledger_txns lt ON lt.txn_id = lp.txn_id
    JOIN accounts a ON a.account_id = lp.account_id
    WHERE lp.account_id IN (${sql.join(accountIds)})
    ORDER BY lp.txn_id DESC, lp.posting_id DESC
    LIMIT ${limit}
  `.execute(db);
  return r.rows.map((row) => ({
    posting_id: row.posting_id,
    txn_id: row.txn_id,
    account_id: row.account_id,
    account_name: row.account_name,
    account_type: row.account_type,
    amount: row.amount,
    memo: row.memo,
    message: row.message,
    settlement_time: row.settlement_time,
    plugin_system: row.plugin_system,
  }));
}
