import 'server-only';
import { sql } from 'kysely';
import { db, binToUuid, uuidToBin } from '@/lib/db';

export interface FineCategoryRow {
  category: string;
  fine_count: number;
  total_amount: string;
  active_fine_count: number;
  active_amount: string;
}

export interface FineRow {
  fine_id: number;
  player_uuid: string | null;
  player_name: string | null;
  gov_account_id: number | null;
  gov_account_name: string | null;
  amount: string;
  reason: string | null;
  txn_id: number | null;
  issued_by_uuid: string | null;
  issued_by_name: string | null;
  issued_at: Date | null;
  revoked: number;
}

/** Mirrors LedgerExplorerMapper.getRecentFines (line 434-447). */
export async function getRecentFines(days: number, limit: number, accountIds: readonly number[] | null = [], options: { offset?: number; q?: string | undefined; status?: string | undefined } = {}): Promise<FineRow[]> {
  const r = await sql<{
    fineId: number;
    playerUuidBin: Buffer | null;
    playerName: string | null;
    govAccountId: number | null;
    govAccountName: string | null;
    amount: string;
    reason: string | null;
    txnId: number | null;
    issuedByUuidBin: Buffer | null;
    issuedByName: string | null;
    issuedAt: Date | null;
    revoked: number;
  }>`
    SELECT gf.fine_id AS fineId, gf.player_uuid_bin AS playerUuidBin,
           fp_p.current_name AS playerName, gf.gov_account_id AS govAccountId,
           a.display_name AS govAccountName, gf.amount, gf.reason,
           gf.txn_id AS txnId, gf.issued_by_uuid_bin AS issuedByUuidBin,
           fp_i.current_name AS issuedByName, gf.issued_at AS issuedAt,
           gf.revoked
    FROM government_fines gf
    LEFT JOIN economy_players fp_p ON fp_p.player_uuid_bin = gf.player_uuid_bin
    LEFT JOIN economy_players fp_i ON fp_i.player_uuid_bin = gf.issued_by_uuid_bin
    LEFT JOIN accounts a ON a.account_id = gf.gov_account_id
    WHERE gf.issued_at >= NOW() - INTERVAL ${days} DAY
      AND ${accountIds === null ? sql`TRUE` : accountIds.length ? sql`gf.gov_account_id IN (${sql.join(accountIds)})` : sql`FALSE`}
      AND ${options.q ? sql`gf.reason LIKE ${'%' + options.q + '%'}` : sql`TRUE`}
      AND ${options.status === 'active' ? sql`gf.revoked = 0` : options.status === 'revoked' ? sql`gf.revoked = 1` : sql`TRUE`}
    ORDER BY gf.issued_at DESC, gf.fine_id DESC
    LIMIT ${limit} OFFSET ${options.offset ?? 0}
  `.execute(db);

  return r.rows.map((row) => ({
    fine_id: row.fineId,
    player_uuid: row.playerUuidBin ? binToUuid(row.playerUuidBin) : null,
    player_name: row.playerName,
    gov_account_id: row.govAccountId,
    gov_account_name: row.govAccountName,
    amount: row.amount,
    reason: row.reason,
    txn_id: row.txnId,
    issued_by_uuid: row.issuedByUuidBin ? binToUuid(row.issuedByUuidBin) : null,
    issued_by_name: row.issuedByName,
    issued_at: row.issuedAt,
    revoked: row.revoked,
  }));
}

/** Mirrors LedgerExplorerMapper.getFineCategorySummary (line 454-467). */
export async function getFineCategorySummary(days: number, accountIds: readonly number[] | null = []): Promise<FineCategoryRow[]> {
  const r = await sql<{
    category: string | null;
    fineCount: string | number;
    totalAmount: string;
    activeFineCount: string | number;
    activeAmount: string;
  }>`
    SELECT category,
           COUNT(*) AS fineCount,
           COALESCE(SUM(amount), 0.00) AS totalAmount,
           SUM(CASE WHEN revoked = 0 THEN 1 ELSE 0 END) AS activeFineCount,
           COALESCE(SUM(CASE WHEN revoked = 0 THEN amount ELSE 0 END), 0.00) AS activeAmount
    FROM (
      SELECT amount, revoked,
             SUBSTRING_INDEX(SUBSTRING_INDEX(TRIM(reason), ' ', 1), ',', 1) AS category
      FROM government_fines
      WHERE issued_at >= NOW() - INTERVAL ${days} DAY
        AND ${accountIds === null ? sql`TRUE` : accountIds.length ? sql`gov_account_id IN (${sql.join(accountIds)})` : sql`FALSE`}
    ) t
    GROUP BY category
    ORDER BY totalAmount DESC
  `.execute(db);
  return r.rows.map((row) => ({
    category: row.category && row.category.length > 0 ? row.category : 'Other',
    fine_count: Number(row.fineCount),
    total_amount: row.totalAmount,
    active_fine_count: Number(row.activeFineCount),
    active_amount: row.activeAmount,
  }));
}

export interface GovAccountRow {
  account_id: number;
  display_name: string | null;
  owner_uuid: string | null;
  owner_name: string | null;
  account_type: string;
  balance: string;
}

/** Mirrors LedgerExplorerMapper.getGovernmentAccounts (line 506-513). */
export async function getGovernmentAccounts(scope: { all: true } | { playerUuid: string }): Promise<GovAccountRow[]> {
  const r = await sql<{
    account_id: number;
    display_name: string | null;
    owner_uuid_bin: Buffer | null;
    owner_name: string | null;
    account_type: string;
    balance: string;
  }>`
    SELECT a.account_id, a.display_name, a.owner_uuid_bin, fp.current_name AS owner_name,
           a.account_type, COALESCE(abm.balance, 0.00) AS balance
    FROM accounts a
    LEFT JOIN account_balances_mat abm ON abm.account_id = a.account_id
    LEFT JOIN economy_players fp ON fp.player_uuid_bin = a.owner_uuid_bin
    WHERE a.is_archived = 0 AND a.account_type = 'GOVERNMENT'
      AND ${'all' in scope ? sql`TRUE` : sql`(a.owner_uuid_bin = ${uuidToBin(scope.playerUuid)} OR EXISTS (SELECT 1 FROM account_read_access_web ar WHERE ar.account_id = a.account_id AND ar.subject_uuid_bin = ${uuidToBin(scope.playerUuid)}))`}
    ORDER BY abm.balance DESC
  `.execute(db);
  return r.rows.map((row) => ({
    account_id: row.account_id,
    display_name: row.display_name,
    owner_uuid: row.owner_uuid_bin ? binToUuid(row.owner_uuid_bin) : null,
    owner_name: row.owner_name,
    account_type: row.account_type,
    balance: row.balance,
  }));
}

export async function getFineTotals(days: number, accountIds: readonly number[] | null = []) {
  const r = await sql<{ active_count: string | number; active_amount: string }>`
    SELECT COUNT(*) AS active_count, COALESCE(SUM(amount), 0.00) AS active_amount
    FROM government_fines
    WHERE revoked = 0 AND issued_at >= NOW() - INTERVAL ${days} DAY
      AND ${accountIds === null ? sql`TRUE` : accountIds.length ? sql`gov_account_id IN (${sql.join(accountIds)})` : sql`FALSE`}
  `.execute(db);
  return { activeCount: Number(r.rows[0]?.active_count ?? 0), activeAmount: r.rows[0]?.active_amount ?? '0.00' };
}

export async function countFines(days: number, accountIds: readonly number[] | null, options: { q?: string | undefined; status?: string | undefined }) {
  const r = await sql<{ n: string | number }>`SELECT COUNT(*) AS n FROM government_fines gf
    WHERE gf.issued_at >= NOW() - INTERVAL ${days} DAY
      AND ${accountIds === null ? sql`TRUE` : accountIds.length ? sql`gf.gov_account_id IN (${sql.join(accountIds)})` : sql`FALSE`}
      AND ${options.q ? sql`gf.reason LIKE ${'%' + options.q + '%'}` : sql`TRUE`}
      AND ${options.status === 'active' ? sql`gf.revoked = 0` : options.status === 'revoked' ? sql`gf.revoked = 1` : sql`TRUE`}
  `.execute(db);
  return Number(r.rows[0]?.n ?? 0);
}
