import 'server-only';
import { sql, type RawBuilder, type SqlBool } from 'kysely';
import { db, binToUuid } from '@/lib/db';
import { CATEGORIES, PLUGIN_CATEGORIES, type AccountType, type Category } from '@/lib/categories';
import { accountTxnsTable, type AccountTxnsState } from '@/lib/tables/accountTxns';
import { compileTableState, defineColumnMap, likeContains } from '@/lib/sql/tableState';

// One account's transaction history (spec §7.1), built to stay fast on the
// busiest accounts: the ChestShop system account has over 1.2M postings.
//
// Every page walks ledger_postings' (account_id, txn_id, amount) index
// backwards from a cursor and stops after a fixed number of transactions:
//
//   * Unfiltered, it reads exactly `limit + 1` index entries.
//   * Filtered, it reads at most `scan` entries (a "scan window"); filters are
//     applied to that window only, so a filter that matches rarely can never
//     turn into a walk over the whole history. The page reports how far back it
//     looked, and the next page continues from there.
//   * A date filter first maps the dates to txn_id bounds with one LIMIT 1 probe
//     per bound on ledger_txns.settlement_time, so the window starts and ends
//     inside the range.
//
// Rows are one per transaction (the account's legs summed), newest first by
// txn_id, which is monotonic with creation. Nothing here reads more than one
// account's postings or more than the window. EXPLAINs are in the PR (PAR-537).

export interface HistoryRow {
  txn_id: number;
  /** This account's net amount in the transaction (DECIMAL string, signed). */
  amount: string;
  /** Sum of this account's amounts in transactions newer than this one, inside the scan window. */
  newer: string;
  settlement_time: Date;
  message: string | null;
  plugin_system: string | null;
  tax_kind: string | null;
  is_fine: boolean;
  initiator_uuid: string | null;
  initiator_name: string | null;
  /** Transactions the window examined, and the oldest txn_id in it. */
  scanned: number;
  oldest_scanned: number;
  oldest_scanned_at: Date | null;
}

export interface HistoryLeg {
  txn_id: number;
  posting_id: number;
  account_id: number;
  amount: string;
  memo: string | null;
  display_name: string | null;
  account_type: string;
  owner_uuid: string | null;
  owner_name: string | null;
  firm_id: number | null;
}

/** What the page needs to bound the scan. Dates are mapped to txn ids by {@link findTxnBounds}. */
export interface HistoryWindow {
  accountId: number;
  /** Exclusive: rows older than this txn (the cursor, or the first txn after a date range). */
  beforeTxnId: number | null;
  /** Inclusive: no rows older than this txn (the first txn of a date range). */
  minTxnId: number | null;
  /** Rows to return (the service asks for one extra to know if there is a next page). */
  limit: number;
  /** Transactions to examine when filtering. Ignored when there are no filters. */
  scan: number;
}

const ROW = sql`w`;

/** The largest opposite-signed other leg's account type: the counterparty, as the rollups pick it. */
const COUNTERPARTY_TYPE = sql`(
  SELECT ca.account_type FROM ledger_postings cp JOIN accounts ca ON ca.account_id = cp.account_id
  WHERE cp.txn_id = w.txn_id AND cp.account_id <> w.account_id AND SIGN(cp.amount) = -SIGN(w.amount)
  ORDER BY ABS(cp.amount) DESC, cp.posting_id
  LIMIT 1
)`;
const PLUGIN_KEY = sql`LOWER(TRIM(COALESCE(lt.plugin_system, '')))`;
const NO_TAX = sql<SqlBool>`(lt.tax_kind IS NULL OR lt.tax_kind = '')`;
const IS_FINE = sql<SqlBool>`(EXISTS (SELECT 1 FROM government_fines gf WHERE gf.txn_id = w.txn_id)
  OR EXISTS (SELECT 1 FROM government_fines gr WHERE gr.revoke_txn_id = w.txn_id))`;
const PLAYERS: readonly AccountType[] = ['PERSONAL', 'BUSINESS'];

/**
 * A category (lib/categories.ts) as a condition on one history row, mirroring
 * `categorize`: fines, then taxes, then the plugin map. Internal is not
 * expressible per row and never matches. Exported for the unit tests.
 */
export function categoryCondition(category: Category): RawBuilder<SqlBool> {
  if (category === 'internal') return sql<SqlBool>`FALSE`;
  if (category === 'taxes') return sql<SqlBool>`NOT ${NO_TAX}`;
  if (category === 'fines') return sql<SqlBool>`(${NO_TAX} AND ${IS_FINE})`;
  const parts: RawBuilder<SqlBool>[] = [];
  const keys = Object.keys(PLUGIN_CATEGORIES);
  for (const key of keys) {
    const rule = PLUGIN_CATEGORIES[key];
    if (rule === undefined) continue;
    if (typeof rule === 'string') {
      if (rule === category) parts.push(sql<SqlBool>`${PLUGIN_KEY} = ${key}`);
    } else if ('in' in rule) {
      if (rule.in === category) parts.push(sql<SqlBool>`(${PLUGIN_KEY} = ${key} AND ${ROW}.amount >= 0)`);
      if (rule.out === category) parts.push(sql<SqlBool>`(${PLUGIN_KEY} = ${key} AND ${ROW}.amount < 0)`);
    } else {
      const types = rule.counterparty.length ? rule.counterparty : PLAYERS;
      if (rule.category === category) parts.push(sql<SqlBool>`(${PLUGIN_KEY} = ${key} AND ${COUNTERPARTY_TYPE} IN (${sql.join(types)}))`);
      if (rule.otherwise === category) {
        parts.push(sql<SqlBool>`(${PLUGIN_KEY} = ${key} AND COALESCE(${COUNTERPARTY_TYPE}, '') NOT IN (${sql.join(types)}))`);
      }
    }
  }
  // Unknown plugins fall into Other.
  if (category === 'other') parts.push(sql<SqlBool>`${PLUGIN_KEY} NOT IN (${sql.join(keys)})`);
  if (parts.length === 0) return sql<SqlBool>`FALSE`;
  return sql<SqlBool>`(${NO_TAX} AND NOT ${IS_FINE} AND (${sql.join(parts, sql` OR `)}))`;
}

/** Text in the transaction's message or this account's memo. */
function textCondition(text: string): RawBuilder<SqlBool> {
  const like = likeContains(text);
  return sql<SqlBool>`(lt.message LIKE ${like} OR EXISTS (
    SELECT 1 FROM ledger_postings mp WHERE mp.account_id = w.account_id AND mp.txn_id = w.txn_id AND mp.memo LIKE ${like}))`;
}

/**
 * The history table's filters as conditions on the scan window (`w`, joined to
 * its ledger_txns row `lt`). Every condition is evaluated per examined row, so
 * none of them can widen the scan.
 */
export const accountTxnsColumnMap = defineColumnMap({
  table: accountTxnsTable,
  filters: {
    category: {
      compile: (f) => {
        const cats = f.values.filter((v): v is Category => (CATEGORIES as readonly string[]).includes(v));
        return cats.length ? sql<SqlBool>`(${sql.join(cats.map(categoryCondition), sql` OR `)})` : null;
      },
    },
    direction: {
      compile: (f) => {
        const dirs = new Set(f.values);
        if (dirs.has('in') && dirs.has('out')) return null;
        if (dirs.has('in')) return sql<SqlBool>`w.amount > 0`;
        if (dirs.has('out')) return sql<SqlBool>`w.amount < 0`;
        return null;
      },
    },
    counterparty: {
      compile: (f) => {
        const ids = f.values.map(Number).filter((n) => Number.isInteger(n) && n > 0);
        if (ids.length === 0) return null;
        // A scalar subquery, not EXISTS: MariaDB would turn EXISTS into a semi-join
        // that drives from the counterparty's postings, which can be the bigger side.
        return sql<SqlBool>`(SELECT 1 FROM ledger_postings cp WHERE cp.account_id IN (${sql.join(ids)}) AND cp.txn_id = w.txn_id LIMIT 1) IS NOT NULL`;
      },
    },
    amount: { column: sql`ABS(w.amount)` },
    date: { column: sql`lt.settlement_time` },
    memo: { compile: (f) => (f.values[0] ? textCondition(f.values[0]) : null) },
  },
  sorts: { date: sql`w.txn_id` },
  search: (q) => textCondition(q),
});

/** True when the state filters rows (a scan window is needed). */
export function historyIsFiltered(state: AccountTxnsState): boolean {
  return state.q !== '' || state.filters.length > 0;
}

/**
 * First txn at or after `from`, and first txn at or after `to`, by settlement
 * time. Each is one LIMIT 1 read of idx_ledger_settle_time (index-only).
 */
export async function findTxnBounds(from: Date | null, to: Date | null): Promise<{ first: number | null; after: number | null }> {
  const probe = (at: Date | null) => at
    ? sql`(SELECT txn_id FROM ledger_txns WHERE settlement_time >= ${at} ORDER BY settlement_time, txn_id LIMIT 1)`
    : sql`NULL`;
  const r = await sql<{ first: number | string | null; after: number | string | null }>`
    SELECT ${probe(from)} AS first, ${probe(to)} AS after
  `.execute(db);
  const row = r.rows[0];
  const num = (v: number | string | null | undefined) => (v === null || v === undefined ? null : Number(v));
  return { first: num(row?.first), after: num(row?.after) };
}

interface RawHistoryRow {
  txn_id: number | string;
  amount: string;
  newer: string | null;
  settlement_time: Date;
  message: string | null;
  plugin_system: string | null;
  tax_kind: string | null;
  is_fine: number | string;
  initiator_uuid_bin: Buffer | null;
  initiator_name: string | null;
  scanned: number | string;
  oldest_scanned: number | string;
  oldest_scanned_at: Date | null;
}

/**
 * One page of an account's history. See the file comment for the bounds.
 * `now` anchors relative date filters.
 */
export async function listAccountHistory(window: HistoryWindow, state: AccountTxnsState, now: Date = new Date()): Promise<HistoryRow[]> {
  const filtered = historyIsFiltered(state);
  const compiled = compileTableState(state, accountTxnsColumnMap, now);
  const scan = filtered ? Math.max(window.scan, window.limit) : window.limit;
  const r = await sql<RawHistoryRow>`
    SELECT w.txn_id, w.amount, w.newer, w.scanned, w.oldest_scanned, lo.settlement_time AS oldest_scanned_at,
           lt.settlement_time, lt.message, lt.plugin_system, lt.tax_kind, lt.initiator_uuid_bin,
           ip.current_name AS initiator_name,
           ${IS_FINE} AS is_fine
    FROM (
      SELECT s.txn_id, s.amount, ${window.accountId} AS account_id,
             SUM(s.amount) OVER (ORDER BY s.txn_id DESC ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING) AS newer,
             COUNT(*) OVER () AS scanned,
             MIN(s.txn_id) OVER () AS oldest_scanned
      FROM (
        SELECT txn_id, SUM(amount) AS amount
        FROM ledger_postings
        WHERE account_id = ${window.accountId}
          ${window.beforeTxnId !== null ? sql`AND txn_id < ${window.beforeTxnId}` : sql``}
          ${window.minTxnId !== null ? sql`AND txn_id >= ${window.minTxnId}` : sql``}
        GROUP BY txn_id
        ORDER BY txn_id DESC
        LIMIT ${scan}
      ) s
    ) w
    STRAIGHT_JOIN ledger_txns lt ON lt.txn_id = w.txn_id
    LEFT JOIN ledger_txns lo ON lo.txn_id = w.oldest_scanned
    LEFT JOIN economy_players ip ON ip.player_uuid_bin = lt.initiator_uuid_bin
    ${compiled.where}
    ORDER BY w.txn_id DESC
    LIMIT ${window.limit}
  `.execute(db);
  return r.rows.map((row) => ({
    txn_id: Number(row.txn_id),
    amount: row.amount,
    newer: row.newer ?? '0.00',
    settlement_time: row.settlement_time,
    message: row.message,
    plugin_system: row.plugin_system,
    tax_kind: row.tax_kind,
    is_fine: Number(row.is_fine) === 1,
    initiator_uuid: row.initiator_uuid_bin ? binToUuid(row.initiator_uuid_bin) : null,
    initiator_name: row.initiator_name,
    scanned: Number(row.scanned),
    oldest_scanned: Number(row.oldest_scanned),
    oldest_scanned_at: row.oldest_scanned_at,
  }));
}

/**
 * The edge of a scan window that matched nothing: how many transactions it
 * examined and the oldest one, so the page can offer to keep looking. Reads
 * the same index range as the window, no further.
 */
export async function findScanEdge(window: HistoryWindow): Promise<{ scanned: number; oldest: number | null; oldestAt: Date | null }> {
  const r = await sql<{ scanned: number | string; oldest: number | string | null; oldest_at: Date | null }>`
    SELECT e.scanned, e.oldest, lt.settlement_time AS oldest_at
    FROM (
      SELECT COUNT(*) AS scanned, MIN(s.txn_id) AS oldest
      FROM (
        SELECT txn_id FROM ledger_postings
        WHERE account_id = ${window.accountId}
          ${window.beforeTxnId !== null ? sql`AND txn_id < ${window.beforeTxnId}` : sql``}
          ${window.minTxnId !== null ? sql`AND txn_id >= ${window.minTxnId}` : sql``}
        GROUP BY txn_id
        ORDER BY txn_id DESC
        LIMIT ${window.scan}
      ) s
    ) e
    LEFT JOIN ledger_txns lt ON lt.txn_id = e.oldest
  `.execute(db);
  const row = r.rows[0];
  return {
    scanned: Number(row?.scanned ?? 0),
    oldest: row?.oldest === null || row?.oldest === undefined ? null : Number(row.oldest),
    oldestAt: row?.oldest_at ?? null,
  };
}

/**
 * Sum of the account's postings at or after `fromTxnId`, reading at most
 * `cap` postings. Null when there are more than `cap` (too far back to be
 * cheap); the caller then leaves the running balance out.
 */
export async function sumPostingsSince(accountId: number, fromTxnId: number, cap: number): Promise<string | null> {
  const r = await sql<{ n: number | string; total: string | null }>`
    SELECT COUNT(*) AS n, COALESCE(SUM(t.amount), 0.00) AS total
    FROM (
      SELECT amount FROM ledger_postings
      WHERE account_id = ${accountId} AND txn_id >= ${fromTxnId}
      ORDER BY txn_id
      LIMIT ${cap + 1}
    ) t
  `.execute(db);
  const row = r.rows[0];
  if (!row || Number(row.n) > cap) return null;
  return row.total ?? '0.00';
}

/**
 * The cursor for the page of `limit` transactions just newer than `txnId`:
 * the txn_id just above them, or null when they are the newest page.
 */
export async function findNewerCursor(accountId: number, txnId: number, limit: number): Promise<number | null> {
  const r = await sql<{ txn_id: number | string }>`
    SELECT txn_id FROM ledger_postings
    WHERE account_id = ${accountId} AND txn_id >= ${txnId}
    GROUP BY txn_id
    ORDER BY txn_id
    LIMIT 1 OFFSET ${limit}
  `.execute(db);
  const row = r.rows[0];
  return row ? Number(row.txn_id) : null;
}

interface RawLeg {
  txn_id: number | string;
  posting_id: number | string;
  account_id: number;
  amount: string;
  memo: string | null;
  display_name: string | null;
  account_type: string;
  owner_uuid_bin: Buffer | null;
  owner_name: string | null;
  firm_id: number | null;
}

/** Every leg of the given transactions (a page's worth), with the accounts' names. idx_postings_txn lookups. */
export async function listTransactionLegs(txnIds: readonly number[]): Promise<HistoryLeg[]> {
  if (txnIds.length === 0) return [];
  const r = await sql<RawLeg>`
    SELECT p.txn_id, p.posting_id, p.account_id, p.amount, p.memo,
           a.display_name, a.account_type, a.owner_uuid_bin, ep.current_name AS owner_name, fa.firm_id
    FROM ledger_postings p
    JOIN accounts a ON a.account_id = p.account_id
    LEFT JOIN economy_players ep ON ep.player_uuid_bin = a.owner_uuid_bin
    LEFT JOIN firm_accounts fa ON fa.account_id = p.account_id AND fa.removed_at IS NULL
    WHERE p.txn_id IN (${sql.join([...new Set(txnIds)])})
    ORDER BY p.txn_id DESC, p.posting_id
  `.execute(db);
  return r.rows.map((row) => ({
    txn_id: Number(row.txn_id),
    posting_id: Number(row.posting_id),
    account_id: row.account_id,
    amount: row.amount,
    memo: row.memo,
    display_name: row.display_name,
    account_type: row.account_type,
    owner_uuid: row.owner_uuid_bin ? binToUuid(row.owner_uuid_bin) : null,
    owner_name: row.owner_name,
    firm_id: row.firm_id,
  }));
}

export interface AccountLabelRow {
  account_id: number;
  display_name: string | null;
  account_type: string;
  owner_uuid: string | null;
  owner_name: string | null;
}

/** Names for account ids already on the URL (counterparty chips). Primary-key lookups. */
export async function findAccountLabels(ids: readonly number[]): Promise<AccountLabelRow[]> {
  if (ids.length === 0) return [];
  const r = await sql<Omit<AccountLabelRow, 'owner_uuid'> & { owner_uuid_bin: Buffer | null }>`
    SELECT a.account_id, a.display_name, a.account_type, a.owner_uuid_bin, ep.current_name AS owner_name
    FROM accounts a LEFT JOIN economy_players ep ON ep.player_uuid_bin = a.owner_uuid_bin
    WHERE a.account_id IN (${sql.join([...new Set(ids)])})
  `.execute(db);
  return r.rows.map(({ owner_uuid_bin, ...row }) => ({ ...row, owner_uuid: owner_uuid_bin ? binToUuid(owner_uuid_bin) : null }));
}
