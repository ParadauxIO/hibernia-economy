import 'server-only';
import { sql, type RawBuilder, type SqlBool } from 'kysely';
import { db, binToUuid, uuidToBin } from '@/lib/db';
import { accountsTable, type AccountsTable, type AccountsTableState } from '@/lib/tables/accounts';
import { normaliseFilter, type FilterKey, type FilterState } from '@/lib/tables/core';
import { compileTableState, defineColumnMap, likeContains } from '@/lib/sql/tableState';
import { afterCursor, afterId, orderById, orderByTime, readDir, type KeysetPage } from '@/lib/sql/keyset';

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

// ── Transaction explorer (/transactions, spec §9.4) ───────────────────────
//
// Keyset pages over 9.3M ledger_txns with no COUNT(*) and no OFFSET. Each query
// is driven off exactly one index, chosen from the filters (`txnDriver`):
//
//   txn      #123 in the search box   → PRIMARY
//   account  postings of the account  → idx_postings_account_txn_amount (account_id, txn_id)
//   player   initiator or authorizer  → idx_ledger_initiator / idx_ledger_authorizer (uuid, txn_id)
//   category plugins of the category    → idx_ledger_plugin_time per plugin, idx_ledger_tax_kind_time for Taxes
//   plugin   plugin_system            → idx_ledger_plugin_time (plugin_system, settlement_time)
//   amount   total moved              → idx_ltv_settle_volume (settlement_time, volume), index-only
//   time     everything else          → idx_ledger_settle_time (settlement_time)
//
// A multi-value chip (two plugins, two accounts) becomes one ordered, LIMITed
// branch per value under a UNION, so every branch stays an index range that
// stops at the page size; MariaDB can't merge ordered ranges itself and would
// filesort or scan instead.
//
// The account and player indexes are ordered by txn_id, not settlement_time, so
// those drivers page on txn_id ("id" mode). txn_id is assigned at insert and
// settlement_time defaults to the insert time, so the two orders agree except
// for a transfer whose settlement_time was later rewritten. Ordering a busy
// player's 4.6M rows by settlement_time would filesort them all; this walks the
// index and stops after one page. The cursor still carries both fields.
//
// With no entity or plugin filter, an amount filter drives from
// ledger_txn_volume (V36): its (settlement_time, volume) index holds both the
// window and the amount, so the scan is index-only and never reads a
// ledger_txns row that doesn't match. On 1M local transactions a 31-day window
// takes 0.2 s for a rare amount, against 1.4 s for a primary-key probe per row.
//
// The remaining filters are residual checks on rows the driver yields. Plugin,
// player, settlement window and text are row columns. A residual amount is one
// ledger_txn_volume primary-key lookup per row, and the account-type filters a
// correlated EXISTS over the transaction's postings (idx_postings_txn). Per-row
// checks are only cheap while matches are common, so the service never sends
// them without a bounded settlement window (AMOUNT_WINDOW_DAYS for amount,
// ROW_SCAN_DAYS for account types and text; lib/tables/transactions.ts).

export type AccountTypeValue = 'PERSONAL' | 'BUSINESS' | 'GOVERNMENT' | 'SYSTEM';

/** One amount bound, compared exactly as DECIMAL. `value` is a DB decimal string. */
export interface MoneyBound {
  op: 'gt' | 'gte' | 'lt' | 'lte' | 'eq';
  value: string;
}

/** The transaction explorer's filters, resolved by the service (names → UUIDs, windows clamped). */
export interface TxnExplorerQuery {
  txnId?: number | null;
  /** null in the list = transactions with no plugin_system. */
  plugins?: readonly (string | null)[] | null;
  accounts?: readonly number[] | null;
  /** Player UUIDs: matches the initiator or the authorizer. */
  players?: readonly string[] | null;
  /** Half-open settlement window [from, to). */
  from?: Date | null;
  to?: Date | null;
  amount?: readonly MoneyBound[] | null;
  fromTypes?: readonly AccountTypeValue[] | null;
  toTypes?: readonly AccountTypeValue[] | null;
  /** Literal substring of the message. */
  message?: string | null;
  /**
   * A category chip, resolved by the service: untaxed transactions of these
   * plugins, plus every taxed transaction (V38 tax_kind) when `taxes`.
   */
  category?: { plugins: readonly (string | null)[]; taxes: boolean } | null;
}

/** V38's tax_kind values (ledger_txns.tax_kind, idx_ledger_tax_kind_time). */
export const TAX_KINDS = ['personal', 'corporate', 'income', 'sales'] as const;

export type TxnDriver = 'txn' | 'account' | 'player' | 'category' | 'plugin' | 'amount' | 'time';

const has = <T,>(v: readonly T[] | null | undefined): v is readonly T[] => Array.isArray(v) && v.length > 0;

/** The index a query is driven from. Most selective first. */
export function txnDriver(q: TxnExplorerQuery): TxnDriver {
  if (q.txnId != null) return 'txn';
  if (has(q.accounts)) return 'account';
  if (has(q.players)) return 'player';
  if (q.category) return 'category';
  if (has(q.plugins)) return 'plugin';
  if (has(q.amount)) return 'amount';
  return 'time';
}

/** Whether pages are keyed on txn_id alone (account and player drivers) or (settlement_time, txn_id). */
export function txnPageMode(q: TxnExplorerQuery): 'id' | 'time' {
  const d = txnDriver(q);
  return d === 'account' || d === 'player' ? 'id' : 'time';
}

const MONEY_OPS = { gt: '>', gte: '>=', lt: '<', lte: '<=', eq: '=' } as const;

function pluginCondition(plugins: readonly (string | null)[]): RawBuilder<SqlBool> {
  const named = plugins.filter((p): p is string => p !== null);
  const parts: RawBuilder<SqlBool>[] = [];
  if (named.length) parts.push(sql<SqlBool>`lt.plugin_system IN (${sql.join(named)})`);
  if (named.length !== plugins.length) parts.push(sql<SqlBool>`lt.plugin_system IS NULL`);
  return parts.length === 1 ? (parts[0] as RawBuilder<SqlBool>) : sql<SqlBool>`(${sql.join(parts, sql` OR `)})`;
}

/**
 * A per-row existence check, written as `(SELECT 1 … LIMIT 1) = 1` rather than
 * EXISTS on purpose: MariaDB turns an EXISTS into a semi-join and may
 * materialize it first, e.g. every posting ever made to a government account
 * (EXPLAIN on production chose exactly that). The scalar form stays a
 * dependent subquery: one index probe per transaction the driver yields.
 */
function perRow(subquery: RawBuilder<unknown>): RawBuilder<SqlBool> {
  return sql<SqlBool>`(${subquery} LIMIT 1) = 1`;
}

function sideTypes(types: readonly AccountTypeValue[], side: 'from' | 'to'): RawBuilder<SqlBool> {
  const sign = sql.raw(side === 'from' ? '<' : '>');
  return perRow(sql`SELECT 1 FROM ledger_postings sp JOIN accounts sa ON sa.account_id = sp.account_id
    WHERE sp.txn_id = lt.txn_id AND sp.amount ${sign} 0 AND sa.account_type IN (${sql.join([...types])})`);
}

function volumeBounds(amount: readonly MoneyBound[]): RawBuilder<SqlBool> {
  return sql<SqlBool>`${sql.join(amount.map((b) => sql`v.volume ${sql.raw(MONEY_OPS[b.op])} CAST(${b.value} AS DECIMAL(19,2))`), sql` AND `)}`;
}

/** A category as a row check: untaxed transactions of its plugins, or any taxed one. */
function categoryCondition(c: NonNullable<TxnExplorerQuery['category']>): RawBuilder<SqlBool> {
  const parts: RawBuilder<SqlBool>[] = [];
  if (c.plugins.length) parts.push(sql<SqlBool>`(${pluginCondition(c.plugins)} AND lt.tax_kind IS NULL)`);
  if (c.taxes) parts.push(sql<SqlBool>`lt.tax_kind IS NOT NULL`);
  return parts.length ? sql<SqlBool>`(${sql.join(parts, sql` OR `)})` : sql<SqlBool>`FALSE`;
}

/** Residual checks for every filter the driver doesn't already apply. */
function txnResiduals(q: TxnExplorerQuery, driver: TxnDriver): RawBuilder<SqlBool>[] {
  const out: RawBuilder<SqlBool>[] = [];
  // The category driver applies the plugin chip itself (as an intersection).
  if (driver !== 'plugin' && driver !== 'category' && has(q.plugins)) out.push(pluginCondition(q.plugins));
  if (driver !== 'category' && q.category) out.push(categoryCondition(q.category));
  if (driver !== 'account' && has(q.accounts)) {
    out.push(perRow(sql`SELECT 1 FROM ledger_postings ap WHERE ap.txn_id = lt.txn_id AND ap.account_id IN (${sql.join([...q.accounts])})`));
  }
  if (driver !== 'player' && has(q.players)) {
    const bins = q.players.map(uuidToBin);
    out.push(sql<SqlBool>`(lt.initiator_uuid_bin IN (${sql.join(bins)}) OR lt.authorizer_uuid_bin IN (${sql.join(bins)}))`);
  }
  // The amount driver applies the window on ledger_txn_volume's own copy of settlement_time.
  if (q.from && driver !== 'amount') out.push(sql<SqlBool>`lt.settlement_time >= ${q.from}`);
  if (q.to && driver !== 'amount') out.push(sql<SqlBool>`lt.settlement_time < ${q.to}`);
  if (q.message) out.push(sql<SqlBool>`lt.message LIKE ${likeContains(q.message)}`);
  if (driver !== 'amount' && has(q.amount)) {
    out.push(perRow(sql`SELECT 1 FROM ledger_txn_volume v WHERE v.txn_id = lt.txn_id AND ${volumeBounds(q.amount)}`));
  }
  if (has(q.fromTypes)) out.push(sideTypes(q.fromTypes, 'from'));
  if (has(q.toTypes)) out.push(sideTypes(q.toTypes, 'to'));
  return out;
}

function whereAll(conds: readonly RawBuilder<SqlBool>[]): RawBuilder<unknown> {
  return conds.length ? sql`WHERE ${sql.join([...conds], sql` AND `)}` : sql``;
}

/**
 * One SELECT per driver value, each `ORDER BY … LIMIT limit` on its index.
 * Every branch yields `(txn_id, settlement_time)`.
 */
function txnBranches(q: TxnExplorerQuery, page: KeysetPage | null, limit: number): { branches: RawBuilder<unknown>[]; distinct: boolean } {
  const driver = txnDriver(q);
  const dir = page ? readDir(page) : 'desc';
  const residual = txnResiduals(q, driver);
  const timeKeys = { time: sql`lt.settlement_time`, id: sql`lt.txn_id` };
  const timeCursor = page?.cursor ? [afterCursor(timeKeys.time, timeKeys.id, page.cursor, dir)] : [];

  switch (driver) {
    case 'txn':
      return { branches: [sql`SELECT lt.txn_id, lt.settlement_time FROM ledger_txns lt ${whereAll([sql<SqlBool>`lt.txn_id = ${q.txnId}`, ...residual, ...timeCursor])} LIMIT 1`], distinct: false };
    case 'account': {
      const idCursor = page?.cursor ? [afterId(sql`lp.txn_id`, page.cursor, dir)] : [];
      return {
        // STRAIGHT_JOIN: drive from the account's postings. Left to itself the
        // optimizer walks ledger_txns' primary key backwards when another
        // filter (a plugin) is present, which for a quiet account is half the ledger.
        branches: (q.accounts ?? []).map((accountId) => sql`
          SELECT lp.txn_id, MAX(lt.settlement_time) AS settlement_time
          FROM ledger_postings lp STRAIGHT_JOIN ledger_txns lt ON lt.txn_id = lp.txn_id
          ${whereAll([sql<SqlBool>`lp.account_id = ${accountId}`, ...idCursor, ...residual])}
          GROUP BY lp.txn_id ${orderById(sql`lp.txn_id`, dir)} LIMIT ${limit}`),
        distinct: true,
      };
    }
    case 'player': {
      const idCursor = page?.cursor ? [afterId(timeKeys.id, page.cursor, dir)] : [];
      const branches: RawBuilder<unknown>[] = [];
      for (const uuid of q.players ?? []) {
        for (const column of [sql`lt.initiator_uuid_bin`, sql`lt.authorizer_uuid_bin`]) {
          branches.push(sql`SELECT lt.txn_id, lt.settlement_time FROM ledger_txns lt
            ${whereAll([sql<SqlBool>`${column} = ${uuidToBin(uuid)}`, ...idCursor, ...residual])}
            ${orderById(timeKeys.id, dir)} LIMIT ${limit}`);
        }
      }
      // A player can be both initiator and authorizer of one transaction.
      return { branches, distinct: true };
    }
    case 'category': {
      // One branch per plugin in the category (idx_ledger_plugin_time), untaxed
      // only, and one per tax kind for Taxes (idx_ledger_tax_kind_time). The
      // sets are disjoint, so UNION ALL. A plugin chip narrows both.
      const c = q.category ?? { plugins: [], taxes: false };
      const chip = has(q.plugins) ? new Set(q.plugins) : null;
      const plugins = chip ? c.plugins.filter((p) => chip.has(p)) : c.plugins;
      const branch = (cond: RawBuilder<SqlBool>[]) => sql`SELECT lt.txn_id, lt.settlement_time FROM ledger_txns lt
        ${whereAll([...cond, ...timeCursor, ...residual])}
        ${orderByTime(timeKeys.time, timeKeys.id, dir)} LIMIT ${limit}`;
      const branches = plugins.map((plugin) => branch([pluginCondition([plugin]), sql<SqlBool>`lt.tax_kind IS NULL`]));
      if (c.taxes) {
        for (const kind of TAX_KINDS) branches.push(branch([sql<SqlBool>`lt.tax_kind = ${kind}`, ...(chip ? [pluginCondition([...chip])] : [])]));
      }
      // Nothing can match: keep the query valid and empty.
      if (branches.length === 0) branches.push(sql`SELECT lt.txn_id, lt.settlement_time FROM ledger_txns lt WHERE FALSE`);
      return { branches, distinct: false };
    }
    case 'plugin':
      return {
        branches: (q.plugins ?? []).map((plugin) => sql`SELECT lt.txn_id, lt.settlement_time FROM ledger_txns lt
          ${whereAll([pluginCondition([plugin]), ...timeCursor, ...residual])}
          ${orderByTime(timeKeys.time, timeKeys.id, dir)} LIMIT ${limit}`),
        distinct: false,
      };
    case 'amount': {
      // ledger_txn_volume's (settlement_time, volume) index answers the window
      // and the amount without touching ledger_txns: an index-only range scan
      // with the bound checked in the index, then a top-N sort of the matches.
      // ledger_txns is joined only when another filter needs its columns.
      const vCursor = page?.cursor ? [afterCursor(sql`v.settlement_time`, sql`v.txn_id`, page.cursor, dir)] : [];
      const window = [
        ...(q.from ? [sql<SqlBool>`v.settlement_time >= ${q.from}`] : []),
        ...(q.to ? [sql<SqlBool>`v.settlement_time < ${q.to}`] : []),
      ];
      const ltOnly = residual;
      return {
        branches: [sql`SELECT v.txn_id, v.settlement_time FROM ledger_txn_volume v
          ${ltOnly.length ? sql`STRAIGHT_JOIN ledger_txns lt ON lt.txn_id = v.txn_id` : sql``}
          ${whereAll([volumeBounds(q.amount ?? []), ...window, ...vCursor, ...ltOnly])}
          ${orderByTime(sql`v.settlement_time`, sql`v.txn_id`, dir)} LIMIT ${limit}`],
        distinct: false,
      };
    }
    case 'time':
      return {
        branches: [sql`SELECT lt.txn_id, lt.settlement_time FROM ledger_txns lt
          ${whereAll([...timeCursor, ...residual])}
          ${orderByTime(timeKeys.time, timeKeys.id, dir)} LIMIT ${limit}`],
        distinct: false,
      };
  }
}

/** `(b1) UNION (b2) …` as a derived-table body. */
function unionOf(branches: readonly RawBuilder<unknown>[], distinct: boolean): RawBuilder<unknown> {
  if (branches.length === 1) return branches[0] as RawBuilder<unknown>;
  return sql.join(branches.map((b) => sql`(${b})`), distinct ? sql` UNION ` : sql` UNION ALL `);
}

export interface ExplorerTxnListRow {
  txn_id: number;
  settlement_time: Date;
  trade_time: Date | null;
  message: string | null;
  plugin_system: string | null;
  initiator_uuid: string | null;
  initiator_name: string | null;
  authorizer_uuid: string | null;
  authorizer_name: string | null;
  /** Sum of the positive postings (ledger_txn_volume): what the transfer moved. */
  volume: string;
}

interface RawTxnListRow {
  txn_id: number | string;
  settlement_time: Date;
  trade_time: Date | null;
  message: string | null;
  plugin_system: string | null;
  initiator_uuid_bin: Buffer | null;
  initiator_name: string | null;
  authorizer_uuid_bin: Buffer | null;
  authorizer_name: string | null;
  volume: string | null;
}

/**
 * One keyset page of the transaction explorer, in the page's read order;
 * `inPageOrder` trims the probe row and flips a prev page. Fetches
 * `page.limit + 1` rows so the caller knows whether another page exists.
 */
export async function listExplorerTransactions(q: TxnExplorerQuery, page: KeysetPage): Promise<ExplorerTxnListRow[]> {
  const probe = page.limit + 1;
  const { branches, distinct } = txnBranches(q, page, probe);
  const dir = readDir(page);
  const order = txnPageMode(q) === 'id'
    ? orderById(sql`u.txn_id`, dir)
    : orderByTime(sql`u.settlement_time`, sql`u.txn_id`, dir);
  const r = await sql<RawTxnListRow>`
    SELECT u.txn_id, lt.settlement_time, lt.trade_time, lt.message, lt.plugin_system,
           lt.initiator_uuid_bin, fi.current_name AS initiator_name,
           lt.authorizer_uuid_bin, fa.current_name AS authorizer_name,
           COALESCE(v.volume, 0.00) AS volume
    FROM (${unionOf(branches, distinct)}) u
    JOIN ledger_txns lt ON lt.txn_id = u.txn_id
    LEFT JOIN ledger_txn_volume v ON v.txn_id = u.txn_id
    LEFT JOIN economy_players fi ON fi.player_uuid_bin = lt.initiator_uuid_bin
    LEFT JOIN economy_players fa ON fa.player_uuid_bin = lt.authorizer_uuid_bin
    ${order}
    LIMIT ${probe}
  `.execute(db);
  return r.rows.map((row) => ({
    txn_id: Number(row.txn_id),
    settlement_time: row.settlement_time,
    trade_time: row.trade_time,
    message: row.message,
    plugin_system: row.plugin_system,
    initiator_uuid: row.initiator_uuid_bin ? binToUuid(row.initiator_uuid_bin) : null,
    initiator_name: row.initiator_name,
    authorizer_uuid: row.authorizer_uuid_bin ? binToUuid(row.authorizer_uuid_bin) : null,
    authorizer_name: row.authorizer_name,
    volume: row.volume ?? '0.00',
  }));
}

/**
 * How many transactions match, counted no further than `cap + 1`: the summary
 * line shows "1,000+" rather than paying for a COUNT(*) over millions of rows.
 */
export async function countExplorerTransactions(q: TxnExplorerQuery, cap: number): Promise<{ count: number; capped: boolean }> {
  const { branches, distinct } = txnBranches(q, null, cap + 1);
  const r = await sql<{ c: number | string }>`
    SELECT COUNT(*) AS c FROM (SELECT u.txn_id FROM (${unionOf(branches, distinct)}) u LIMIT ${cap + 1}) capped
  `.execute(db);
  const n = Number(r.rows[0]?.c ?? 0);
  return { count: Math.min(n, cap), capped: n > cap };
}

export interface TxnPostingRow extends ExplorerPostingRow {
  account_type: string | null;
}

/** Postings for a page of transactions (idx_postings_txn), with account labels. */
export async function findPostingsForTxns(txnIds: readonly number[]): Promise<TxnPostingRow[]> {
  if (txnIds.length === 0) return [];
  const r = await sql<{
    posting_id: number; txn_id: number; account_id: number; account_type: string | null; display_name: string | null;
    owner_name: string | null; owner_uuid_bin: Buffer | null; amount: string; memo: string | null;
  }>`
    SELECT lp.posting_id, lp.txn_id, lp.account_id, a.account_type, a.display_name,
           fp.current_name AS owner_name, a.owner_uuid_bin, lp.amount, lp.memo
    FROM ledger_postings lp
    LEFT JOIN accounts a ON a.account_id = lp.account_id
    LEFT JOIN economy_players fp ON fp.player_uuid_bin = a.owner_uuid_bin
    WHERE lp.txn_id IN (${sql.join([...txnIds])})
    ORDER BY lp.txn_id, lp.posting_id
  `.execute(db);
  return r.rows.map((row) => ({
    posting_id: Number(row.posting_id),
    txn_id: Number(row.txn_id),
    account_id: row.account_id,
    account_type: row.account_type,
    display_name: row.display_name,
    owner_name: row.owner_name,
    owner_uuid: row.owner_uuid_bin ? binToUuid(row.owner_uuid_bin) : null,
    amount: row.amount,
    memo: row.memo,
  }));
}

/** The first transaction settled at or after `at` (idx_ledger_settle_time seek), for jump-to-date and windows. */
export async function findFirstTxnAtOrAfter(at: Date): Promise<{ txn_id: number; settlement_time: Date } | null> {
  const r = await sql<{ txn_id: number | string; settlement_time: Date }>`
    SELECT lt.txn_id, lt.settlement_time FROM ledger_txns lt
    WHERE lt.settlement_time >= ${at} ORDER BY lt.settlement_time, lt.txn_id LIMIT 1
  `.execute(db);
  const row = r.rows[0];
  return row ? { txn_id: Number(row.txn_id), settlement_time: row.settlement_time } : null;
}

/** The last transaction settled at or before `at`. */
export async function findLastTxnAtOrBefore(at: Date): Promise<{ txn_id: number; settlement_time: Date } | null> {
  const r = await sql<{ txn_id: number | string; settlement_time: Date }>`
    SELECT lt.txn_id, lt.settlement_time FROM ledger_txns lt
    WHERE lt.settlement_time <= ${at} ORDER BY lt.settlement_time DESC, lt.txn_id DESC LIMIT 1
  `.execute(db);
  const row = r.rows[0];
  return row ? { txn_id: Number(row.txn_id), settlement_time: row.settlement_time } : null;
}

export interface AccountMovementRow {
  txn_id: number;
  settlement_time: Date;
  message: string | null;
  plugin_system: string | null;
  /** Net amount on the account in this transaction (positive = received). */
  amount: string;
}

/** How many of the account's neighbouring postings "follow the money" reads, at most, per side. */
export const FOLLOW_SCAN_POSTINGS = 500;

/**
 * "Follow the money": the account's transactions nearest to one transaction,
 * before or after it, that settled inside [from, to]. Reads at most
 * FOLLOW_SCAN_POSTINGS of the account's neighbouring postings from its
 * (account_id, txn_id) index, so a busy account costs the same as a quiet one
 * and the account's history is never walked. Neighbours are taken by txn_id and
 * the window is checked on settlement_time, so a transaction whose settlement
 * time was rewritten still lands in the right window.
 */
export async function listAccountMovements(args: {
  accountId: number;
  /** Exclusive bound: the transaction being followed. */
  txnId: number;
  direction: 'before' | 'after';
  from: Date;
  to: Date;
  limit: number;
}): Promise<AccountMovementRow[]> {
  const before = args.direction === 'before';
  const dir = before ? 'desc' : 'asc';
  const r = await sql<{ txn_id: number | string; settlement_time: Date; message: string | null; plugin_system: string | null; amount: string }>`
    SELECT n.txn_id, MAX(lt.settlement_time) AS settlement_time, MAX(lt.message) AS message,
           MAX(lt.plugin_system) AS plugin_system, SUM(n.amount) AS amount
    FROM (
      SELECT lp.txn_id, lp.amount FROM ledger_postings lp
      WHERE lp.account_id = ${args.accountId} AND lp.txn_id ${sql.raw(before ? '<' : '>')} ${args.txnId}
      ${orderById(sql`lp.txn_id`, dir)} LIMIT ${FOLLOW_SCAN_POSTINGS}
    ) n
    JOIN ledger_txns lt ON lt.txn_id = n.txn_id
    WHERE lt.settlement_time >= ${args.from} AND lt.settlement_time <= ${args.to}
    GROUP BY n.txn_id
    ${orderById(sql`n.txn_id`, dir)}
    LIMIT ${args.limit}
  `.execute(db);
  return r.rows.map((row) => ({
    txn_id: Number(row.txn_id),
    settlement_time: row.settlement_time,
    message: row.message,
    plugin_system: row.plugin_system,
    amount: row.amount,
  }));
}

/** Every plugin_system value with its latest settlement (loose index scan of idx_ledger_plugin_time). */
export async function listLedgerPlugins(): Promise<{ plugin_system: string | null; last_at: Date }[]> {
  const r = await sql<{ plugin_system: string | null; last_at: Date }>`
    SELECT lt.plugin_system, MAX(lt.settlement_time) AS last_at
    FROM ledger_txns lt GROUP BY lt.plugin_system ORDER BY last_at DESC
  `.execute(db);
  return r.rows;
}

/** Transactions per plugin since `since`: one index-only range count per plugin. */
export async function countTxnsByPlugin(plugins: readonly (string | null)[], since: Date): Promise<Map<string | null, number>> {
  if (plugins.length === 0) return new Map();
  const branches = plugins.map((p) => sql`SELECT ${p} AS plugin_system, COUNT(*) AS c FROM ledger_txns lt
    WHERE ${pluginCondition([p])} AND lt.settlement_time >= ${since}`);
  const r = await sql<{ plugin_system: string | null; c: number | string }>`${sql.join(branches.map((b) => sql`(${b})`), sql` UNION ALL `)}`.execute(db);
  return new Map(r.rows.map((row) => [row.plugin_system, Number(row.c)]));
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

/** Every transaction ever (the economy overview's headline; it caches the result). */
async function countAllTransactions(): Promise<number> {
  const result = await sql<{ c: string | number }>`SELECT COUNT(*) AS c FROM ledger_txns`.execute(db);
  return Number(result.rows[0]?.c ?? 0);
}

export async function getEconomyStats(): Promise<EconomyStats> {
  const [active, archived, supply, personal, txnTotal, byType, top] = await Promise.all([
    countActiveAccounts(),
    countArchivedAccounts(),
    getTotalSupply(),
    getPersonalSupply(),
    countAllTransactions(),
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
