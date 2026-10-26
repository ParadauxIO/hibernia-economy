import 'server-only';
import { sql } from 'kysely';
import { db, binToUuid, uuidToBin } from '@/lib/db';
import { inWindow, movementSelect, toMovement, type MovementRow, type RawMovement, type TxnWindow } from './government';

// Pure queries behind the compliance tools (/gov/compliance, spec §10.4).
// Access (firm.financials.read.all + transactions.read) is checked in
// lib/services/governmentCompliance.ts.

export interface ComplianceFirmRow {
  firm_id: number;
  display_name: string;
  proprietor_uuid: string | null;
  proprietor_name: string | null;
  is_archived: number;
  created_at: Date | null;
}

export async function findComplianceFirm(firmId: number): Promise<ComplianceFirmRow | null> {
  const r = await sql<Omit<ComplianceFirmRow, 'proprietor_uuid'> & { proprietor_uuid_bin: Buffer | null }>`
    SELECT f.firm_id, f.display_name, f.proprietor_uuid_bin, fp.current_name AS proprietor_name, f.is_archived, f.created_at
    FROM firm f
    LEFT JOIN economy_players fp ON fp.player_uuid_bin = f.proprietor_uuid_bin
    WHERE f.firm_id = ${firmId}
  `.execute(db);
  const row = r.rows[0];
  if (!row) return null;
  const { proprietor_uuid_bin, ...rest } = row;
  return { ...rest, firm_id: Number(rest.firm_id), is_archived: Number(rest.is_archived), proprietor_uuid: proprietor_uuid_bin ? binToUuid(proprietor_uuid_bin) : null };
}

export interface ComplianceAccountRow {
  account_id: number;
  display_name: string | null;
  account_type: string;
  balance: string;
  is_archived: number;
  removed_at: Date | null;
}

/** Every account the firm holds or has held (firm_accounts.removed_at marks a past one). */
export async function listFirmAccountsForCompliance(firmId: number): Promise<ComplianceAccountRow[]> {
  const r = await sql<ComplianceAccountRow>`
    SELECT a.account_id, a.display_name, a.account_type, COALESCE(abm.balance, 0.00) AS balance, a.is_archived, fa.removed_at
    FROM firm_accounts fa
    JOIN accounts a ON a.account_id = fa.account_id
    LEFT JOIN account_balances_mat abm ON abm.account_id = a.account_id
    WHERE fa.firm_id = ${firmId}
    ORDER BY fa.removed_at IS NOT NULL, COALESCE(abm.balance, 0.00) DESC, a.account_id
  `.execute(db);
  return r.rows.map((row) => ({ ...row, account_id: Number(row.account_id), is_archived: Number(row.is_archived) }));
}

/** A player's PERSONAL accounts (idx_accounts_owner). */
export async function listPersonalAccountIds(playerUuid: string): Promise<number[]> {
  const r = await sql<{ account_id: number }>`
    SELECT account_id FROM accounts WHERE owner_uuid_bin = ${uuidToBin(playerUuid)} AND account_type = 'PERSONAL'
  `.execute(db);
  return r.rows.map((row) => Number(row.account_id));
}

export interface PairMonthRow { month: string; inflow: string; outflow: string; txn_count: number }

/** Monthly flows between two sets of accounts, from `accountIds`' side (explorer_rollup_pair_month PK). */
export async function selectPairMonths(accountIds: readonly number[], counterpartyIds: readonly number[], fromMonth: string, toMonth: string): Promise<PairMonthRow[]> {
  if (accountIds.length === 0 || counterpartyIds.length === 0) return [];
  const r = await sql<PairMonthRow>`
    SELECT DATE_FORMAT(month, '%Y-%m-01') AS month, SUM(inflow) AS inflow, SUM(outflow) AS outflow, SUM(txn_count) AS txn_count
    FROM explorer_rollup_pair_month
    WHERE account_id IN (${sql.join([...accountIds])}) AND counterparty_id IN (${sql.join([...counterpartyIds])})
      AND month BETWEEN ${fromMonth} AND ${toMonth}
    GROUP BY month
    ORDER BY month
  `.execute(db);
  return r.rows.map((row) => ({ ...row, txn_count: Number(row.txn_count) }));
}

/**
 * Postings on a set of accounts in a txn window that are at least `threshold`
 * or a round multiple of `roundUnit` (at least one unit). Walks only
 * idx_postings_account_txn_amount; details join for the `limit` rows.
 */
export async function selectFlaggedPostings(accountIds: readonly number[], window: TxnWindow, threshold: string, roundUnit: string, limit: number): Promise<MovementRow[]> {
  if (accountIds.length === 0) return [];
  const inner = sql`
    SELECT p.txn_id, p.account_id, p.amount
    FROM ledger_postings p
    WHERE p.account_id IN (${sql.join([...accountIds])}) AND ${inWindow(sql`p.txn_id`, window)}
      AND (ABS(p.amount) >= CAST(${threshold} AS DECIMAL(19,2))
        OR (ABS(p.amount) >= CAST(${roundUnit} AS DECIMAL(19,2)) AND MOD(ABS(p.amount), CAST(${roundUnit} AS DECIMAL(19,2))) = 0))
    ORDER BY ABS(p.amount) DESC, p.txn_id DESC
    LIMIT ${limit}`;
  const r = await sql<RawMovement>`${movementSelect(inner)} ORDER BY ABS(p.amount) DESC, p.txn_id DESC`.execute(db);
  return r.rows.map(toMovement);
}

export interface AccountFlowRow { account_id: number; inflow: string; outflow: string; txn_count: number }

/** Gross in and out per account over a day range (rollups). */
export async function selectAccountFlows(accountIds: readonly number[], from: string, to: string): Promise<AccountFlowRow[]> {
  if (accountIds.length === 0) return [];
  const r = await sql<AccountFlowRow>`
    SELECT account_id, SUM(inflow) AS inflow, SUM(outflow) AS outflow, SUM(txn_count) AS txn_count
    FROM explorer_rollup_account_day
    WHERE account_id IN (${sql.join([...accountIds])}) AND day BETWEEN ${from} AND ${to}
    GROUP BY account_id
  `.execute(db);
  return r.rows.map((row) => ({ ...row, account_id: Number(row.account_id), txn_count: Number(row.txn_count) }));
}

// ── watchlist ─────────────────────────────────────────────────────────────

export interface WatchRow {
  account_id: number;
  display_name: string | null;
  account_type: string;
  owner_uuid: string | null;
  owner_name: string | null;
  firm_id: number | null;
  firm_name: string | null;
  balance: string;
  created_at: Date;
}

export async function listWatchlist(viewerUuid: string): Promise<WatchRow[]> {
  const r = await sql<Omit<WatchRow, 'owner_uuid'> & { owner_uuid_bin: Buffer | null }>`
    SELECT w.account_id, a.display_name, a.account_type, a.owner_uuid_bin, fp.current_name AS owner_name,
           fa.firm_id, f.display_name AS firm_name, COALESCE(abm.balance, 0.00) AS balance, w.created_at
    FROM explorer_watchlist w
    JOIN accounts a ON a.account_id = w.account_id
    LEFT JOIN account_balances_mat abm ON abm.account_id = a.account_id
    LEFT JOIN economy_players fp ON fp.player_uuid_bin = a.owner_uuid_bin
    LEFT JOIN firm_accounts fa ON fa.account_id = a.account_id AND fa.removed_at IS NULL
    LEFT JOIN firm f ON f.firm_id = fa.firm_id
    WHERE w.viewer_uuid_bin = ${uuidToBin(viewerUuid)}
    ORDER BY w.created_at DESC, w.account_id
  `.execute(db);
  return r.rows.map(({ owner_uuid_bin, ...row }) => ({
    ...row, account_id: Number(row.account_id), firm_id: row.firm_id === null ? null : Number(row.firm_id), owner_uuid: owner_uuid_bin ? binToUuid(owner_uuid_bin) : null,
  }));
}

export async function watchedIds(viewerUuid: string, accountIds: readonly number[]): Promise<Set<number>> {
  if (accountIds.length === 0) return new Set();
  const r = await sql<{ account_id: number }>`
    SELECT account_id FROM explorer_watchlist WHERE viewer_uuid_bin = ${uuidToBin(viewerUuid)} AND account_id IN (${sql.join([...accountIds])})
  `.execute(db);
  return new Set(r.rows.map((row) => Number(row.account_id)));
}

export async function addWatch(viewerUuid: string, accountId: number): Promise<void> {
  await sql`INSERT IGNORE INTO explorer_watchlist (viewer_uuid_bin, account_id) VALUES (${uuidToBin(viewerUuid)}, ${accountId})`.execute(db);
}

export async function removeWatch(viewerUuid: string, accountId: number): Promise<void> {
  await sql`DELETE FROM explorer_watchlist WHERE viewer_uuid_bin = ${uuidToBin(viewerUuid)} AND account_id = ${accountId}`.execute(db);
}

export async function accountExists(accountId: number): Promise<boolean> {
  const r = await sql<{ ok: number }>`SELECT EXISTS(SELECT 1 FROM accounts WHERE account_id = ${accountId}) AS ok`.execute(db);
  return Number(r.rows[0]?.ok ?? 0) === 1;
}
