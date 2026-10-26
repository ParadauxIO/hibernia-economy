import 'server-only';
import { sql, type RawBuilder, type SqlBool } from 'kysely';
import { db, binToUuid, uuidToBin } from '@/lib/db';
import { accountsTable, type AccountsTable, type AccountsTableState } from '@/lib/tables/accounts';
import { normaliseFilter, type FilterKey, type FilterState } from '@/lib/tables/core';
import { compileTableState, defineColumnMap, likeContains } from '@/lib/sql/tableState';

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

// ── Accounts ──────────────────────────────────────────────────────────────

/** What accountLabel() shows, in SQL: personal accounts store their owner's UUID as display_name. */
const ACCOUNT_LABEL_SQL = sql`CASE WHEN a.display_name IS NULL OR a.display_name REGEXP '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-' THEN COALESCE(fp.current_name, a.display_name) ELSE a.display_name END`;
const BALANCE_SQL = sql`COALESCE(abm.balance, 0.00)`;
const UUID_SEARCH_RE = /^[0-9a-f]{8}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{12}$/i;

/**
 * Free-text search for the accounts directory. A full UUID is an exact,
 * indexed owner match (idx_accounts_owner) and `#123` an exact account number;
 * anything else is a substring match on the account and owner names. There is
 * deliberately no partial-UUID match: it was a `LOWER(HEX(owner_uuid_bin)) LIKE`
 * scan that no index can serve.
 */
export function accountSearchSql(q: string): RawBuilder<SqlBool> | null {
  const t = q.trim();
  if (!t) return null;
  if (UUID_SEARCH_RE.test(t)) return sql<SqlBool>`a.owner_uuid_bin = ${uuidToBin(t)}`;
  const hash = /^#(\d{1,10})$/.exec(t);
  if (hash) return sql<SqlBool>`a.account_id = ${Number(hash[1])}`;
  const like = likeContains(t);
  const names = sql<SqlBool>`(a.display_name LIKE ${like} OR fp.current_name LIKE ${like})`;
  if (/^\d{1,10}$/.test(t)) return sql<SqlBool>`(a.account_id = ${Number(t)} OR ${names})`;
  return names;
}

/** True when the search text needs the economy_players join (a name search). */
function searchUsesPlayers(q: string): boolean {
  const t = q.trim();
  return t !== '' && !UUID_SEARCH_RE.test(t) && !/^#\d{1,10}$/.test(t);
}

/**
 * How the accounts table's fields reach SQL. Every filter here is index-backed
 * or bounded by the accounts table (139k rows): type → idx_accounts_type,
 * created → idx_accounts_created, firm → firm_accounts' idx_firm_account_active
 * (the semi-join drives from the firm), archived → a column on the row already
 * read. Balance is unindexed by design (V7/V9: the trigger rewrites it on every
 * posting), so it costs one PK join per scanned account, the same as sorting by it.
 */
export const accountsColumnMap = defineColumnMap({
  table: accountsTable,
  filters: {
    type: { column: sql`a.account_type`, values: { personal: 'PERSONAL', business: 'BUSINESS', government: 'GOVERNMENT', system: 'SYSTEM' } },
    balance: { column: BALANCE_SQL },
    created: { column: sql`a.created_at` },
    firm: {
      compile: (f) => {
        const ids = f.values.map(Number).filter((n) => Number.isInteger(n) && n > 0);
        if (ids.length === 0) return null;
        return sql<SqlBool>`a.account_id IN (SELECT fa.account_id FROM firm_accounts fa WHERE fa.firm_id IN (${sql.join(ids)}) AND fa.removed_at IS NULL)`;
      },
    },
    archived: { column: sql`a.is_archived` },
  },
  sorts: { name: ACCOUNT_LABEL_SQL, type: sql`a.account_type`, balance: BALANCE_SQL, created: sql`a.created_at`, id: sql`a.account_id` },
  tiebreak: { column: sql`a.account_id`, dir: 'desc' },
  search: accountSearchSql,
});

/** A directory query: the parsed table state plus the page window. */
export interface AccountsQuery {
  state: AccountsTableState;
  limit: number;
  offset: number;
  /** Show archived accounts too when the state has no archived filter (admin tools). Default: active only. */
  includeArchived?: boolean;
  /** Anchors relative dates ("last 30 days"). */
  now?: Date;
}

/** The pre-PAR-534 argument shape, still used by the admin accounts tool. */
export interface LegacyAccountsArgs {
  type: string | null;
  archived: number | null;
  q: string | null;
}

function fromLegacy(args: LegacyAccountsArgs & { limit?: number; offset?: number; sort?: SortColumn; dir?: SortDir }): AccountsQuery {
  const filters: FilterState<FilterKey<AccountsTable>>[] = [];
  const type = args.type ? normaliseFilter(accountsTable, 'type', 'in', [args.type]) : null;
  if (type) filters.push(type as FilterState<FilterKey<AccountsTable>>);
  if (args.archived !== null) filters.push({ field: 'archived', op: 'eq', values: [args.archived ? 'true' : 'false'] });
  const sortField = args.sort === undefined || args.sort === 'account_id' ? 'id' : args.sort;
  const limit = args.limit ?? accountsTable.pageSize;
  return {
    state: { q: args.q ?? '', filters, sort: { field: sortField, dir: args.dir === 'ASC' ? 'asc' : 'desc' }, page: 1, cursor: null, limit },
    limit,
    offset: args.offset ?? 0,
    includeArchived: args.archived === null,
  };
}

function isAccountsQuery<T extends object>(args: T): args is Extract<T, { state: AccountsTableState }> {
  return 'state' in args;
}

/**
 * WHERE / ORDER BY for the accounts directory, from the parsed table state,
 * plus which optional joins the WHERE needs (the count skips the rest).
 */
export function buildAccountsWhere(query: Omit<AccountsQuery, 'limit' | 'offset'>) {
  const compiled = compileTableState(query.state, accountsColumnMap, query.now);
  const conditions = [...compiled.conditions];
  const hasArchived = query.state.filters.some((f) => f.field === 'archived');
  if (!hasArchived && !query.includeArchived) conditions.unshift(sql<SqlBool>`a.is_archived = 0`);
  const sortField = query.state.sort.field;
  return {
    where: conditions.length ? sql`WHERE ${sql.join(conditions, sql` AND `)}` : sql``,
    orderBy: compiled.orderBy,
    /** The WHERE reads account_balances_mat / economy_players. */
    needsBalance: query.state.filters.some((f) => f.field === 'balance'),
    needsPlayers: searchUsesPlayers(query.state.q),
    /** The ORDER BY reads them. */
    sortsByBalance: sortField === 'balance',
    sortsByName: sortField === 'name',
  };
}

const BALANCE_JOIN = sql`LEFT JOIN account_balances_mat abm ON abm.account_id = a.account_id`;
const PLAYER_JOIN = sql`LEFT JOIN economy_players fp ON fp.player_uuid_bin = a.owner_uuid_bin`;

/**
 * One page of the accounts directory. Mirrors LedgerExplorerMapper.listAccounts (line 39-75).
 *
 * A deferred join: the inner query picks the page's ids joining only what its
 * WHERE and ORDER BY read, then the outer query fetches the display columns for
 * those 50 rows. On 140k accounts this halves the default balance-sorted page
 * (about 300 ms → 140 ms), because the player-name join no longer runs for
 * every account before the sort.
 */
export async function listAccounts(
  args: AccountsQuery | (LegacyAccountsArgs & { limit: number; offset: number; sort: SortColumn; dir: SortDir }),
): Promise<ExplorerAccountRow[]> {
  const query = isAccountsQuery(args) ? args : fromLegacy(args);
  const b = buildAccountsWhere(query);

  const result = await sql<RawAccountRow>`
    SELECT a.account_id, a.account_type, a.display_name, a.owner_uuid_bin, fp.current_name AS owner_name,
           a.is_archived AS archived, a.requires_authorization, a.allow_overdraft,
           a.credit_limit, a.created_at, COALESCE(abm.balance, 0.00) AS balance
    FROM (
      SELECT a.account_id
      FROM accounts a
      ${b.needsBalance || b.sortsByBalance ? BALANCE_JOIN : sql``}
      ${b.needsPlayers || b.sortsByName ? PLAYER_JOIN : sql``}
      ${b.where}
      ${b.orderBy}
      LIMIT ${query.limit} OFFSET ${query.offset}
    ) page
    JOIN accounts a ON a.account_id = page.account_id
    ${BALANCE_JOIN}
    ${PLAYER_JOIN}
    ${b.orderBy}
  `.execute(db);

  return result.rows.map(toExplorerAccountRow);
}

/** Matching accounts for the directory summary. Mirrors LedgerExplorerMapper.countAccounts (line 77-92). */
export async function countAccounts(args: Omit<AccountsQuery, 'limit' | 'offset'> | LegacyAccountsArgs): Promise<number> {
  const query = isAccountsQuery(args) ? args : fromLegacy(args);
  const { where, needsBalance, needsPlayers } = buildAccountsWhere(query);
  // Join only what the WHERE reads, so the common count stays on `accounts` alone.
  const result = await sql<{ c: string | number }>`
    SELECT COUNT(*) AS c
    FROM accounts a
    ${needsBalance ? BALANCE_JOIN : sql``}
    ${needsPlayers ? PLAYER_JOIN : sql``}
    ${where}
  `.execute(db);
  return Number(result.rows[0]?.c ?? 0);
}

/**
 * Account counts by type and archived state, for the Type filter's facet
 * counts. One pass over `accounts` with no joins; callers cache it (memo).
 */
export async function countAccountsByType(): Promise<{ account_type: string; archived: number; count: number }[]> {
  const result = await sql<{ account_type: string; archived: number; c: string | number }>`
    SELECT a.account_type, a.is_archived AS archived, COUNT(*) AS c
    FROM accounts a
    GROUP BY a.account_type, a.is_archived
  `.execute(db);
  return result.rows.map((r) => ({ account_type: r.account_type, archived: Number(r.archived), count: Number(r.c) }));
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

/** Account relationship lookup, including fresh effective LP group memberships.
 * Call Auth.canViewAccount for the full RBAC + relationship decision. */
export async function canReadAccount(accountId: number, viewerUuid: string): Promise<boolean> {
  const bin = uuidToBin(viewerUuid);
  const result = await sql<{ allowed: number }>`
    SELECT EXISTS(
      SELECT 1 FROM account_read_access_web
       WHERE account_id = ${accountId} AND subject_uuid_bin = ${bin}
    ) AS allowed
  `.execute(db);
  return Number(result.rows[0]?.allowed ?? 0) === 1;
}

/** Batched ownership + relationship lookup: which of `accountIds` exist and are
 * owned by, or readable through account_read_access_web for, the viewer. One
 * round trip instead of findAccount + canReadAccount per id. Call
 * Auth.readableAccountIds for the full RBAC + relationship decision. */
export async function findReadableAccountIds(accountIds: readonly number[], viewerUuid: string): Promise<number[]> {
  if (accountIds.length === 0) return [];
  const bin = uuidToBin(viewerUuid);
  const result = await sql<{ account_id: number }>`
    SELECT a.account_id FROM accounts a
     WHERE a.account_id IN (${sql.join([...new Set(accountIds)])})
       AND (a.owner_uuid_bin = ${bin}
         OR EXISTS (SELECT 1 FROM account_read_access_web ar
                     WHERE ar.account_id = a.account_id AND ar.subject_uuid_bin = ${bin}))
  `.execute(db);
  return result.rows.map((r) => Number(r.account_id));
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

/** Minimal resource identifiers for authorization before loading transaction details. */
export async function findTransactionAccountIds(txnId: number): Promise<number[]> {
  const result = await sql<{ account_id: number }>`
    SELECT DISTINCT account_id FROM ledger_postings WHERE txn_id = ${txnId}
    ORDER BY account_id
  `.execute(db);
  return result.rows.map(row => row.account_id);
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
