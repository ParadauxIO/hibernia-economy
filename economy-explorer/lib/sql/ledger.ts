import 'server-only';
import { sql } from 'kysely';
import { db, binToUuid, uuidToBin } from '@/lib/db';

// Row interfaces mirror the Java records in
// io.paradaux.treasuryrestapi.model.*. Money columns stay as string
// (mysql2 returns DECIMAL as string with decimalNumbers:false in lib/db.ts).

export interface ExplorerAccountRow {
  account_id: number;
  account_type: string;
  display_name: string | null;
  owner_uuid: string | null;
  owner_name: string | null;
  archived: number;
  requires_authorization: number;
  allow_overdraft: number;
  credit_limit: string;
  created_at: Date;
  balance: string;
}

export interface TransactionRow {
  posting_id: number;
  txn_id: number;
  amount: string;
  memo: string | null;
  message: string | null;
  settlement_time: Date | null;
  initiator_uuid: string | null;
  initiator_name: string | null;
  plugin_system: string | null;
}

export interface ExplorerTxnRow {
  txn_id: number;
  trade_time: Date | null;
  settlement_time: Date | null;
  message: string | null;
  initiator_uuid: string | null;
  initiator_name: string | null;
  plugin_system: string | null;
  posting_count: number;
}

export interface ExplorerPostingRow {
  posting_id: number;
  txn_id: number;
  account_id: number;
  display_name: string | null;
  owner_name: string | null;
  owner_uuid: string | null;
  amount: string;
  memo: string | null;
}

export interface CounterpartyRow {
  counterparty_id: number;
  display_name: string | null;
  account_type: string;
  owner_uuid: string | null;
  owner_name: string | null;
  txn_count: number;
  total_volume: string;
}

export interface CalendarDayRow {
  date: string;
  count: number;
}

export interface AccountTrajectoryRow {
  date: string;
  credits: string;
  debits: string;
  net: string;
  postingCount: number;
}

export interface BalanceByTypeRow {
  account_type: string;
  account_count: number;
  total_balance: string;
}

export interface TopAccountRow {
  account_id: number;
  display_name: string | null;
  owner_uuid: string | null;
  owner_name: string | null;
  account_type: string;
  balance: string;
}

export interface ExplorerFirmRow {
  firm_id: number;
  display_name: string;
  discord_url: string | null;
  hq_region: string | null;
  default_account_id: number | null;
  archived: number;
  created_at: Date;
  account_count: number;
  employee_count: number;
  total_balance: string;
}

export type SortColumn = 'balance' | 'name' | 'type' | 'created' | 'account_id';
export type SortDir = 'ASC' | 'DESC';

// Sort direction is the one fragment kysely can't parameterize, so it goes
// through sql.raw. Callers already validate `dir` via a zod enum, but normalize
// to a known-safe literal here too — the DAL boundary is the right place to make
// injection impossible regardless of what a future caller passes.
function rawDir(dir: SortDir) {
  return sql.raw(dir === 'ASC' ? 'ASC' : 'DESC');
}

const ORDER_BY: Record<SortColumn, (dir: SortDir) => ReturnType<typeof sql>> = {
  balance: (dir) => sql`ORDER BY abm.balance ${rawDir(dir)}, a.account_id DESC`,
  name: (dir) => sql`ORDER BY COALESCE(a.display_name, fp.current_name) ${rawDir(dir)}, a.account_id DESC`,
  type: (dir) => sql`ORDER BY a.account_type ${rawDir(dir)}, a.account_id DESC`,
  created: (dir) => sql`ORDER BY a.created_at ${rawDir(dir)}, a.account_id DESC`,
  account_id: (dir) => sql`ORDER BY a.account_id ${rawDir(dir)}`,
};

// ── Accounts ──────────────────────────────────────────────────────────────

/** Mirrors LedgerExplorerMapper.listAccounts (line 39-75). */
export async function listAccounts(args: {
  limit: number;
  offset: number;
  type: string | null;
  archived: number | null;
  q: string | null;
  sort: SortColumn;
  dir: SortDir;
}): Promise<ExplorerAccountRow[]> {
  const where = buildAccountsWhere(args.type, args.archived, args.q);
  const order = ORDER_BY[args.sort](args.dir);

  const result = await sql<RawAccountRow>`
    SELECT a.account_id, a.account_type, a.display_name, a.owner_uuid_bin, fp.current_name AS owner_name,
           a.is_archived AS archived, a.requires_authorization, a.allow_overdraft,
           a.credit_limit, a.created_at, COALESCE(abm.balance, 0.00) AS balance
    FROM accounts a
    LEFT JOIN account_balances_mat abm ON abm.account_id = a.account_id
    LEFT JOIN economy_players fp ON fp.player_uuid_bin = a.owner_uuid_bin
    ${where}
    ${order}
    LIMIT ${args.limit} OFFSET ${args.offset}
  `.execute(db);

  return result.rows.map(toExplorerAccountRow);
}

/** Mirrors LedgerExplorerMapper.countAccounts (line 77-92). */
export async function countAccounts(args: {
  type: string | null;
  archived: number | null;
  q: string | null;
}): Promise<number> {
  const where = buildAccountsWhere(args.type, args.archived, args.q);
  // economy_players is only referenced by the `q` name filter; skip the join on the
  // common no-search count so it doesn't join every account row for nothing.
  const nameJoin = args.q
    ? sql`LEFT JOIN economy_players fp ON fp.player_uuid_bin = a.owner_uuid_bin`
    : sql``;
  const result = await sql<{ c: string | number }>`
    SELECT COUNT(*) AS c
    FROM accounts a
    ${nameJoin}
    ${where}
  `.execute(db);
  return Number(result.rows[0]?.c ?? 0);
}

/** Mirrors LedgerExplorerMapper.findAccount (line 94-101). */
export async function findAccount(accountId: number): Promise<ExplorerAccountRow | null> {
  const result = await sql<RawAccountRow>`
    SELECT a.account_id, a.account_type, a.display_name, a.owner_uuid_bin, fp.current_name AS owner_name,
           a.is_archived AS archived, a.requires_authorization, a.allow_overdraft,
           a.credit_limit, a.created_at, COALESCE(abm.balance, 0.00) AS balance
    FROM accounts a
    LEFT JOIN account_balances_mat abm ON abm.account_id = a.account_id
    LEFT JOIN economy_players fp ON fp.player_uuid_bin = a.owner_uuid_bin
    WHERE a.account_id = ${accountId}
  `.execute(db);
  const row = result.rows[0];
  return row ? toExplorerAccountRow(row) : null;
}

/**
 * True when {@code viewerUuid} has explicit read access to an account — an active
 * member, authorizer, or read-only viewer (PAR-237) of it. Mirrors the in-game
 * `canView` gate's direct-UUID path; LuckPerms-group grants (account_group_*) are
 * resolved in-game only, so web access is granted by UUID. Lets e.g. a government
 * department secretary (a viewer) see their department's ledger history here —
 * scoped to the specific accounts they're attached to, never blanket.
 */
export async function canReadAccount(accountId: number, viewerUuid: string): Promise<boolean> {
  const bin = uuidToBin(viewerUuid);
  // Reads account_read_access_web (ADT-13) — the single source of truth for the
  // WEB read rule: any active account_access grant (VIEWER, MEMBER, or
  // AUTHORIZER). VIEWER is included on purpose so a department secretary reads
  // their account's history (PAR-237); the public REST API is stricter
  // (account_read_access_api). LuckPerms-group grants (account_group_access)
  // resolve in-game only, so web access is by UUID.
  const result = await sql<{ allowed: number }>`
    SELECT EXISTS(
      SELECT 1 FROM account_read_access_web
       WHERE account_id = ${accountId} AND subject_uuid_bin = ${bin}
    ) AS allowed
  `.execute(db);
  return Number(result.rows[0]?.allowed ?? 0) === 1;
}

// ── Account-scoped postings ───────────────────────────────────────────────

/** Mirrors LedgerExplorerMapper.listAccountTransactions (line 105-116). */
export async function listAccountTransactions(args: {
  accountId: number;
  limit: number;
  offset: number;
}): Promise<TransactionRow[]> {
  const result = await sql<RawTxnRow>`
    SELECT lp.posting_id, lp.txn_id, lp.amount, lp.memo,
           lt.message, lt.settlement_time, lt.initiator_uuid_bin,
           fp.current_name AS initiator_name, lt.plugin_system
    FROM ledger_postings lp
    JOIN ledger_txns lt ON lp.txn_id = lt.txn_id
    LEFT JOIN economy_players fp ON fp.player_uuid_bin = lt.initiator_uuid_bin
    WHERE lp.account_id = ${args.accountId}
    -- Order by txn_id (auto-increment, monotonic with creation) rather than
    -- lt.settlement_time: settlement_time lives on the other table, so ordering
    -- by it forces a full filesort of every posting for the account before the
    -- LIMIT — on the busiest accounts (~1.26M postings) that's a multi-second
    -- sort of 1.7M rows for 20 results. txn_id DESC walks the (account_id, txn_id)
    -- index and stops at the LIMIT. The two orders differ only for the rare
    -- settled-out-of-order txn, which is immaterial for a recent-activity list.
    ORDER BY lp.txn_id DESC, lp.posting_id DESC
    LIMIT ${args.limit} OFFSET ${args.offset}
  `.execute(db);
  return result.rows.map(toTransactionRow);
}

/** Mirrors LedgerExplorerMapper.countAccountTransactions (line 118-119). */
export async function countAccountTransactions(accountId: number): Promise<number> {
  const result = await sql<{ c: string | number }>`
    SELECT COUNT(*) AS c FROM ledger_postings WHERE account_id = ${accountId}
  `.execute(db);
  return Number(result.rows[0]?.c ?? 0);
}

// ── Counterparties ────────────────────────────────────────────────────────

/** Mirrors LedgerExplorerMapper.getCounterparties (line 341-359). */
export async function getCounterparties(accountId: number, limit: number): Promise<CounterpartyRow[]> {
  const result = await sql<RawCounterpartyRow>`
    SELECT partner.account_id AS counterparty_id,
           a.display_name AS display_name,
           a.account_type AS account_type,
           a.owner_uuid_bin,
           fp.current_name AS owner_name,
           COUNT(*) AS txn_count,
           SUM(ABS(partner.amount)) AS total_volume
    FROM ledger_postings me
    JOIN ledger_postings partner ON partner.txn_id = me.txn_id
                                  AND partner.account_id != me.account_id
    JOIN accounts a ON a.account_id = partner.account_id
    LEFT JOIN economy_players fp ON fp.player_uuid_bin = a.owner_uuid_bin
    WHERE me.account_id = ${accountId}
    GROUP BY partner.account_id, a.display_name, a.account_type,
             a.owner_uuid_bin, fp.current_name
    ORDER BY total_volume DESC
    LIMIT ${limit}
  `.execute(db);
  return result.rows.map((r) => ({
    counterparty_id: r.counterparty_id,
    display_name: r.display_name,
    account_type: r.account_type,
    owner_uuid: r.owner_uuid_bin ? binToUuid(r.owner_uuid_bin) : null,
    owner_name: r.owner_name,
    txn_count: Number(r.txn_count),
    total_volume: r.total_volume ?? '0.00',
  }));
}

// ── Activity calendars ────────────────────────────────────────────────────

/** Mirrors LedgerExplorerMapper.getAccountActivityCalendar (line 594-603). */
export async function getAccountActivityCalendar(accountId: number, days: number): Promise<CalendarDayRow[]> {
  const result = await sql<{ date: string; count: string | number }>`
    SELECT DATE_FORMAT(DATE(lt.settlement_time), '%Y-%m-%d') AS date,
           COUNT(DISTINCT lp.posting_id) AS count
    FROM ledger_postings lp
    JOIN ledger_txns lt ON lt.txn_id = lp.txn_id
    WHERE lp.account_id = ${accountId}
      AND lt.settlement_time >= NOW() - INTERVAL ${days} DAY
    GROUP BY DATE(lt.settlement_time)
    ORDER BY date ASC
  `.execute(db);
  return result.rows.map((r) => ({ date: r.date, count: Number(r.count) }));
}

/** Mirrors LedgerExplorerMapper.getEconomyActivityCalendar (line 586-592). */
export async function getEconomyActivityCalendar(days: number): Promise<CalendarDayRow[]> {
  const result = await sql<{ date: string; count: string | number }>`
    SELECT DATE_FORMAT(DATE(settlement_time), '%Y-%m-%d') AS date,
           COUNT(*) AS count
    FROM ledger_txns
    WHERE settlement_time >= NOW() - INTERVAL ${days} DAY
    GROUP BY DATE(settlement_time)
    ORDER BY date ASC
  `.execute(db);
  return result.rows.map((r) => ({ date: r.date, count: Number(r.count) }));
}

/** Mirrors LedgerExplorerMapper.getAccountTrajectory (line 608-620). */
export async function getAccountTrajectory(accountId: number, days: number): Promise<AccountTrajectoryRow[]> {
  const result = await sql<AccountTrajectoryRow>`
    SELECT DATE_FORMAT(DATE(lt.settlement_time), '%Y-%m-%d') AS date,
           COALESCE(SUM(CASE WHEN lp.amount > 0 THEN lp.amount ELSE 0 END), 0.00) AS credits,
           COALESCE(SUM(CASE WHEN lp.amount < 0 THEN -lp.amount ELSE 0 END), 0.00) AS debits,
           COALESCE(SUM(lp.amount), 0.00) AS net,
           COUNT(*) AS postingCount
    FROM ledger_postings lp
    JOIN ledger_txns lt ON lt.txn_id = lp.txn_id
    WHERE lp.account_id = ${accountId}
      AND lt.settlement_time >= NOW() - INTERVAL ${days} DAY
    GROUP BY DATE(lt.settlement_time)
    ORDER BY date ASC
  `.execute(db);
  return result.rows;
}

// ── Global transactions ───────────────────────────────────────────────────

/** Mirrors LedgerExplorerMapper.listTransactions (line 123-151). Admin-only. */
export async function listTransactions(args: {
  limit: number;
  offset: number;
  sort: 'txnId' | 'settlement' | 'default';
  dir: SortDir;
} & TxnFilters): Promise<ExplorerTxnRow[]> {
  const where = buildTxnsWhere(args);
  let order: ReturnType<typeof sql>;
  if (args.sort === 'txnId') order = sql`ORDER BY lt.txn_id ${rawDir(args.dir)}`;
  else if (args.sort === 'settlement') order = sql`ORDER BY lt.settlement_time ${rawDir(args.dir)}, lt.txn_id DESC`;
  else order = sql`ORDER BY lt.settlement_time DESC, lt.txn_id DESC`;

  // posting_count via correlated subquery, not a join+GROUP BY over the whole
  // ledger. The old shape joined every txn to its postings and aggregated the
  // entire table before it could ORDER BY/LIMIT — O(whole ledger) per page load.
  // Now ORDER BY + LIMIT resolve against ledger_txns alone (idx_ledger_settle_time
  // backward scan), and posting_count is counted only for the ~50 returned rows,
  // each seeking idx_postings_txn.
  const result = await sql<RawTxnSummary>`
    SELECT lt.txn_id, lt.trade_time, lt.settlement_time, lt.message,
           lt.initiator_uuid_bin, fp.current_name AS initiator_name,
           lt.plugin_system,
           (SELECT COUNT(*) FROM ledger_postings lp WHERE lp.txn_id = lt.txn_id) AS posting_count
    FROM ledger_txns lt
    LEFT JOIN economy_players fp ON fp.player_uuid_bin = lt.initiator_uuid_bin
    ${where}
    ${order}
    LIMIT ${args.limit} OFFSET ${args.offset}
  `.execute(db);
  return result.rows.map(toExplorerTxnRow);
}

/** Mirrors LedgerExplorerMapper.countTransactions (line 153-165). */
export async function countTransactions(args: TxnFilters): Promise<number> {
  const where = buildTxnsWhere(args);
  // The economy_players join only exists to let the `q` filter match on
  // initiator_name; with no text query it's pure overhead on a full-table count,
  // so skip it (the default firehose count is the common case).
  const nameJoin = args.q
    ? sql`LEFT JOIN economy_players fp ON fp.player_uuid_bin = lt.initiator_uuid_bin`
    : sql``;
  const result = await sql<{ c: string | number }>`
    SELECT COUNT(*) AS c FROM ledger_txns lt
    ${nameJoin}
    ${where}
  `.execute(db);
  return Number(result.rows[0]?.c ?? 0);
}

/** Mirrors LedgerExplorerMapper.findTransaction (line 167-173). */
export async function findTransaction(txnId: number): Promise<ExplorerTxnRow | null> {
  const result = await sql<RawTxnSummary>`
    SELECT lt.txn_id, lt.trade_time, lt.settlement_time, lt.message,
           lt.initiator_uuid_bin, fp.current_name AS initiator_name,
           lt.plugin_system, 0 AS posting_count
    FROM ledger_txns lt
    LEFT JOIN economy_players fp ON fp.player_uuid_bin = lt.initiator_uuid_bin
    WHERE lt.txn_id = ${txnId}
  `.execute(db);
  const row = result.rows[0];
  return row ? toExplorerTxnRow(row) : null;
}

/** Mirrors LedgerExplorerMapper.findPostingsByTxnId (line 177-181). */
export async function findPostingsByTxnId(txnId: number): Promise<ExplorerPostingRow[]> {
  const result = await sql<{
    posting_id: number;
    txn_id: number;
    account_id: number;
    display_name: string | null;
    owner_name: string | null;
    owner_uuid_bin: Buffer | null;
    amount: string;
    memo: string | null;
  }>`
    SELECT lp.posting_id, lp.txn_id, lp.account_id, a.display_name,
           fp.current_name AS owner_name, a.owner_uuid_bin, lp.amount, lp.memo
    FROM ledger_postings lp
    LEFT JOIN accounts a ON a.account_id = lp.account_id
    LEFT JOIN economy_players fp ON fp.player_uuid_bin = a.owner_uuid_bin
    WHERE lp.txn_id = ${txnId}
    ORDER BY lp.posting_id
  `.execute(db);
  return result.rows.map((r) => ({
    posting_id: r.posting_id,
    txn_id: r.txn_id,
    account_id: r.account_id,
    display_name: r.display_name,
    owner_name: r.owner_name,
    owner_uuid: r.owner_uuid_bin ? binToUuid(r.owner_uuid_bin) : null,
    amount: r.amount,
    memo: r.memo,
  }));
}

// ── Economy stats ─────────────────────────────────────────────────────────

export interface EconomyStats {
  totalAccounts: number;
  archivedAccounts: number;
  totalSupply: string;
  personalSupply: string;
  totalTransactions: number;
  byType: BalanceByTypeRow[];
  topAccounts: TopAccountRow[];
}

export async function getEconomyStats(): Promise<EconomyStats> {
  const [active, archived, supply, personal, txnTotal, byType, top] = await Promise.all([
    countActiveAccounts(),
    countArchivedAccounts(),
    getTotalSupply(),
    getPersonalSupply(),
    countTransactions({ q: null, pluginSystem: null }),
    getBalanceByType(),
    getTopAccounts(10),
  ]);
  return {
    totalAccounts: active,
    archivedAccounts: archived,
    totalSupply: supply,
    personalSupply: personal,
    totalTransactions: txnTotal,
    byType,
    topAccounts: top,
  };
}

export async function countActiveAccounts(): Promise<number> {
  const r = await sql<{ c: string | number }>`SELECT COUNT(*) AS c FROM accounts WHERE is_archived = 0`.execute(db);
  return Number(r.rows[0]?.c ?? 0);
}

export async function countArchivedAccounts(): Promise<number> {
  const r = await sql<{ c: string | number }>`SELECT COUNT(*) AS c FROM accounts WHERE is_archived = 1`.execute(db);
  return Number(r.rows[0]?.c ?? 0);
}

export async function getTotalSupply(): Promise<string> {
  const r = await sql<{ s: string }>`
    SELECT COALESCE(SUM(abm.balance), 0.00) AS s
    FROM account_balances_mat abm
    JOIN accounts a ON a.account_id = abm.account_id
    WHERE a.is_archived = 0 AND a.account_type != 'SYSTEM'
  `.execute(db);
  return r.rows[0]?.s ?? '0.00';
}

export async function getPersonalSupply(): Promise<string> {
  const r = await sql<{ s: string }>`
    SELECT COALESCE(SUM(abm.balance), 0.00) AS s
    FROM account_balances_mat abm
    JOIN accounts a ON a.account_id = abm.account_id
    WHERE a.is_archived = 0 AND a.account_type = 'PERSONAL'
  `.execute(db);
  return r.rows[0]?.s ?? '0.00';
}

export async function getBalanceByType(): Promise<BalanceByTypeRow[]> {
  const r = await sql<{ account_type: string; account_count: number | string; total_balance: string }>`
    SELECT a.account_type, COUNT(*) AS account_count,
           COALESCE(SUM(abm.balance), 0.00) AS total_balance
    FROM accounts a LEFT JOIN account_balances_mat abm ON abm.account_id = a.account_id
    WHERE a.is_archived = 0
    GROUP BY a.account_type
    ORDER BY total_balance DESC
  `.execute(db);
  return r.rows.map((row) => ({
    account_type: row.account_type,
    account_count: Number(row.account_count),
    total_balance: row.total_balance,
  }));
}

export async function getTopAccounts(limit: number): Promise<TopAccountRow[]> {
  const r = await sql<{
    account_id: number;
    display_name: string | null;
    owner_uuid_bin: Buffer | null;
    owner_name: string | null;
    account_type: string;
    balance: string;
  }>`
    SELECT a.account_id, a.display_name, a.owner_uuid_bin, fp.current_name AS owner_name,
           a.account_type, abm.balance
    FROM accounts a
    JOIN account_balances_mat abm ON abm.account_id = a.account_id
    LEFT JOIN economy_players fp ON fp.player_uuid_bin = a.owner_uuid_bin
    WHERE a.is_archived = 0
    ORDER BY abm.balance DESC
    LIMIT ${limit}
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

// ── Firms ─────────────────────────────────────────────────────────────────

export type FirmSortColumn = 'name' | 'employees' | 'created' | 'balance';

export async function listFirms(args: {
  limit: number;
  offset: number;
  q: string | null;
  archived: number | null;
  sort: FirmSortColumn;
  dir: SortDir;
}): Promise<ExplorerFirmRow[]> {
  const conds: ReturnType<typeof sql>[] = [];
  if (args.archived !== null) conds.push(sql`f.is_archived = ${args.archived}`);
  if (args.q) conds.push(sql`(f.display_name LIKE CONCAT('%', ${args.q}, '%') OR f.hq_region LIKE CONCAT('%', ${args.q}, '%'))`);
  const where = conds.length ? sql`WHERE ${sql.join(conds, sql` AND `)}` : sql``;

  let order: ReturnType<typeof sql>;
  switch (args.sort) {
    case 'name':
      order = sql`ORDER BY f.display_name ${rawDir(args.dir)}, f.firm_id DESC`;
      break;
    case 'employees':
      order = sql`ORDER BY employee_count ${rawDir(args.dir)}, total_balance DESC`;
      break;
    case 'created':
      order = sql`ORDER BY f.created_at ${rawDir(args.dir)}, f.firm_id DESC`;
      break;
    default:
      order = sql`ORDER BY total_balance ${rawDir(args.dir)}, f.firm_id DESC`;
  }

  const result = await sql<{
    firm_id: number;
    display_name: string;
    discord_url: string | null;
    hq_region: string | null;
    default_account_id: number | null;
    archived: number;
    created_at: Date;
    account_count: number | string;
    employee_count: number | string;
    total_balance: string;
  }>`
    SELECT f.firm_id, f.display_name, f.discord_url, f.hq_region, f.default_account_id,
           f.is_archived AS archived, f.created_at,
           (SELECT COUNT(*) FROM firm_accounts WHERE firm_id = f.firm_id AND removed_at IS NULL) AS account_count,
           (SELECT COUNT(*) FROM firm_employee WHERE firm_id = f.firm_id AND left_at IS NULL) AS employee_count,
           (SELECT COALESCE(SUM(abm.balance), 0.00)
            FROM firm_accounts fa2
            LEFT JOIN account_balances_mat abm ON abm.account_id = fa2.account_id
            WHERE fa2.firm_id = f.firm_id AND fa2.removed_at IS NULL) AS total_balance
    FROM firm f
    ${where}
    ${order}
    LIMIT ${args.limit} OFFSET ${args.offset}
  `.execute(db);

  return result.rows.map((r) => ({
    firm_id: r.firm_id,
    display_name: r.display_name,
    discord_url: r.discord_url,
    hq_region: r.hq_region,
    default_account_id: r.default_account_id,
    archived: r.archived,
    created_at: r.created_at,
    account_count: Number(r.account_count),
    employee_count: Number(r.employee_count),
    total_balance: r.total_balance,
  }));
}

export async function countFirms(args: { q: string | null; archived: number | null }): Promise<number> {
  const conds: ReturnType<typeof sql>[] = [];
  if (args.archived !== null) conds.push(sql`f.is_archived = ${args.archived}`);
  if (args.q) conds.push(sql`(f.display_name LIKE CONCAT('%', ${args.q}, '%') OR f.hq_region LIKE CONCAT('%', ${args.q}, '%'))`);
  const where = conds.length ? sql`WHERE ${sql.join(conds, sql` AND `)}` : sql``;
  const result = await sql<{ c: string | number }>`SELECT COUNT(*) AS c FROM firm f ${where}`.execute(db);
  return Number(result.rows[0]?.c ?? 0);
}

export async function countActiveFirms(): Promise<number> {
  const r = await sql<{ c: string | number }>`SELECT COUNT(*) AS c FROM firm WHERE is_archived = 0`.execute(db);
  return Number(r.rows[0]?.c ?? 0);
}

// ── Internal builders + row converters ────────────────────────────────────

function buildAccountsWhere(type: string | null, archived: number | null, q: string | null) {
  const conds: ReturnType<typeof sql>[] = [];
  if (type) conds.push(sql`a.account_type = ${type}`);
  if (archived !== null) conds.push(sql`a.is_archived = ${archived}`);
  if (q && q.length > 0) {
    const noDash = q.replace(/-/g, '').toLowerCase();
    conds.push(sql`(
      a.display_name LIKE CONCAT('%', ${q}, '%')
      OR fp.current_name LIKE CONCAT('%', ${q}, '%')
      OR LOWER(HEX(a.owner_uuid_bin)) LIKE CONCAT('%', ${noDash}, '%')
    )`);
  }
  if (conds.length === 0) return sql``;
  return sql`WHERE ${sql.join(conds, sql` AND `)}`;
}

/** Power filters for the admin ledger firehose (PAR-220). All optional. */
export interface TxnFilters {
  q: string | null;
  pluginSystem: string | null;
  dateFrom?: string | null;   // YYYY-MM-DD (inclusive)
  dateTo?: string | null;     // YYYY-MM-DD (inclusive — whole day)
  minAmount?: number | null;  // matches a posting with |amount| >= min
  maxAmount?: number | null;  // matches a posting with |amount| <= max
  accountId?: number | null;  // txns touching this account
}

function buildTxnsWhere(f: TxnFilters) {
  const conds: ReturnType<typeof sql>[] = [];
  if (f.pluginSystem) conds.push(sql`lt.plugin_system = ${f.pluginSystem}`);
  if (f.q) conds.push(sql`(lt.message LIKE CONCAT('%', ${f.q}, '%') OR fp.current_name LIKE CONCAT('%', ${f.q}, '%'))`);
  if (f.dateFrom) conds.push(sql`lt.settlement_time >= ${f.dateFrom}`);
  if (f.dateTo) conds.push(sql`lt.settlement_time < DATE_ADD(${f.dateTo}, INTERVAL 1 DAY)`);
  if (f.accountId != null) {
    conds.push(sql`EXISTS (SELECT 1 FROM ledger_postings lp WHERE lp.txn_id = lt.txn_id AND lp.account_id = ${f.accountId})`);
  }
  if (f.minAmount != null || f.maxAmount != null) {
    const amt: ReturnType<typeof sql>[] = [];
    if (f.minAmount != null) amt.push(sql`ABS(lp.amount) >= ${f.minAmount}`);
    if (f.maxAmount != null) amt.push(sql`ABS(lp.amount) <= ${f.maxAmount}`);
    conds.push(sql`EXISTS (SELECT 1 FROM ledger_postings lp WHERE lp.txn_id = lt.txn_id AND ${sql.join(amt, sql` AND `)})`);
  }
  if (conds.length === 0) return sql``;
  return sql`WHERE ${sql.join(conds, sql` AND `)}`;
}

interface RawAccountRow {
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
}

interface RawTxnRow {
  posting_id: number;
  txn_id: number;
  amount: string;
  memo: string | null;
  message: string | null;
  settlement_time: Date | null;
  initiator_uuid_bin: Buffer | null;
  initiator_name: string | null;
  plugin_system: string | null;
}

interface RawTxnSummary {
  txn_id: number;
  trade_time: Date | null;
  settlement_time: Date | null;
  message: string | null;
  initiator_uuid_bin: Buffer | null;
  initiator_name: string | null;
  plugin_system: string | null;
  posting_count: number | string;
}

interface RawCounterpartyRow {
  counterparty_id: number;
  display_name: string | null;
  account_type: string;
  owner_uuid_bin: Buffer | null;
  owner_name: string | null;
  txn_count: number | string;
  total_volume: string | null;
}

function toExplorerAccountRow(r: RawAccountRow): ExplorerAccountRow {
  return {
    account_id: r.account_id,
    account_type: r.account_type,
    display_name: r.display_name,
    owner_uuid: r.owner_uuid_bin ? binToUuid(r.owner_uuid_bin) : null,
    owner_name: r.owner_name,
    archived: r.archived,
    requires_authorization: r.requires_authorization,
    allow_overdraft: r.allow_overdraft,
    credit_limit: r.credit_limit,
    created_at: r.created_at,
    balance: r.balance,
  };
}

function toTransactionRow(r: RawTxnRow): TransactionRow {
  return {
    posting_id: r.posting_id,
    txn_id: r.txn_id,
    amount: r.amount,
    memo: r.memo,
    message: r.message,
    settlement_time: r.settlement_time,
    initiator_uuid: r.initiator_uuid_bin ? binToUuid(r.initiator_uuid_bin) : null,
    initiator_name: r.initiator_name,
    plugin_system: r.plugin_system,
  };
}

function toExplorerTxnRow(r: RawTxnSummary): ExplorerTxnRow {
  return {
    txn_id: r.txn_id,
    trade_time: r.trade_time,
    settlement_time: r.settlement_time,
    message: r.message,
    initiator_uuid: r.initiator_uuid_bin ? binToUuid(r.initiator_uuid_bin) : null,
    initiator_name: r.initiator_name,
    plugin_system: r.plugin_system,
    posting_count: Number(r.posting_count),
  };
}
