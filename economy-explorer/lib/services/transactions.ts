import 'server-only';
import { memo } from '@/lib/cache';
import { abs, sumBy, toDbString, tryParseMoney } from '@/lib/money';
import type { KeysetCursor } from '@/lib/pagination/cursor';
import { inPageOrder, type KeysetPage } from '@/lib/sql/keyset';
import {
  countExplorerTransactions,
  countTxnsByPlugin,
  findFirstTxnAtOrAfter,
  findLastTxnAtOrBefore,
  findPostingsForTxns,
  findTransaction,
  listAccountMovements,
  listExplorerTransactions,
  listLedgerPlugins,
  txnPageMode,
  type AccountMovementRow,
  type AccountTypeValue,
  type ExplorerTxnListRow,
  type MoneyBound,
  type TxnExplorerQuery,
  type TxnPostingRow,
} from '@/lib/sql/ledger';
import { findAccountLabels, findPlayerNames, findPlayerUuidsByName } from '@/lib/sql/search';
import type { FilterState } from '@/lib/tables/core';
import type { FeedNav, FeedPosition } from '@/lib/tables/feed';
import { scanWindow, type ScanWindow } from '@/lib/tables/scanWindow';
import { AMOUNT_WINDOW_DAYS, NO_PLUGIN, ROW_SCAN_DAYS, categoryPlugins, transactionsTable, type TransactionsTable, type TransactionsTableState } from '@/lib/tables/transactions';

// The transaction explorer (spec §9.4): turns the table state into an
// index-driven ledger query, pages it by keyset, and shapes rows for the page,
// the detail drawer and the export. The SQL and the reasons each filter is
// cheap are in lib/sql/ledger.ts ("Transaction explorer").

/** The summary line counts no further than this ("1,000+ transactions"). */
export const COUNT_CAP = 1_000;
const PLUGIN_TTL_MS = 10 * 60_000;
const FACET_DAYS = 30;

const ACCOUNT_TYPE_SQL: Record<string, AccountTypeValue> = { personal: 'PERSONAL', business: 'BUSINESS', government: 'GOVERNMENT', system: 'SYSTEM' };
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

/** The table with this tenant's plugins as Plugin options, cached for 10 minutes. */
export async function getTransactionsTable(): Promise<TransactionsTable> {
  return transactionsTable(await ledgerPlugins());
}

/** Every plugin_system on the ledger (null for none), most recently used first. */
function ledgerPlugins(): Promise<(string | null)[]> {
  return memo('txn-explorer:plugins', PLUGIN_TTL_MS, async () => (await listLedgerPlugins()).map((p) => p.plugin_system));
}

/** Plugin option counts for the filter popover: transactions in the last 30 days. */
export async function getPluginFacets(table: TransactionsTable): Promise<Record<string, number>> {
  return memo('txn-explorer:plugin-facets', PLUGIN_TTL_MS, async () => {
    const values = table.filters.plugin.options.map((o) => (o.value === NO_PLUGIN ? null : o.value));
    const since = new Date(Date.now() - FACET_DAYS * 86_400_000);
    const counts = await countTxnsByPlugin(values, since);
    const out: Record<string, number> = {};
    for (const [plugin, n] of counts) out[plugin ?? NO_PLUGIN] = n;
    return out;
  });
}

export interface ResolvedTxnQuery {
  query: TxnExplorerQuery;
  window: ScanWindow;
  /** A chip names a player who doesn't exist, or a category no plugin is in: nothing can match. */
  empty: boolean;
}

function filter(state: TransactionsTableState, field: string): FilterState | undefined {
  return state.filters.find((f) => f.field === field);
}

/** Money chip → DECIMAL bounds. `between` is two inclusive bounds. */
export function moneyBounds(f: FilterState | undefined): MoneyBound[] {
  if (!f) return [];
  const dec = (v: string | undefined) => {
    const cents = tryParseMoney(v ?? '');
    return cents === null ? null : toDbString(cents);
  };
  if (f.op === 'between') {
    const lo = dec(f.values[0]);
    const hi = dec(f.values[1]);
    return lo && hi ? [{ op: 'gte', value: lo }, { op: 'lte', value: hi }] : [];
  }
  const v = dec(f.values[0]);
  if (!v || (f.op !== 'gt' && f.op !== 'gte' && f.op !== 'lt' && f.op !== 'lte' && f.op !== 'eq')) return [];
  return [{ op: f.op, value: v }];
}

/** Player chip values (names or UUIDs) → UUIDs. Unknown names drop out. */
async function resolvePlayers(values: readonly string[]): Promise<string[]> {
  const uuids = values.filter((v) => UUID_RE.test(v));
  const names = values.filter((v) => !UUID_RE.test(v));
  const found = await findPlayerUuidsByName(names);
  return [...uuids, ...names.map((n) => found.get(n.toLowerCase())).filter((u): u is string => !!u)];
}

/**
 * Table state → ledger query. Amount, account-type and message filters are
 * per-row checks, so their settlement window is bounded (scanWindow): amount to
 * AMOUNT_WINDOW_DAYS, account types and message text to ROW_SCAN_DAYS. The page
 * says when that happened.
 */
export async function resolveTxnQuery(state: TransactionsTableState, now: Date = new Date()): Promise<ResolvedTxnQuery> {
  const q = state.q.trim();
  const txnMatch = /^#?(\d{1,15})$/.exec(q);
  const txnId = txnMatch ? Number(txnMatch[1]) : null;
  const message = txnId === null && q ? q : null;

  const amount = moneyBounds(filter(state, 'amount'));
  const types = (field: string) => (filter(state, field)?.values ?? []).map((v) => ACCOUNT_TYPE_SQL[v]).filter((t): t is AccountTypeValue => !!t);
  const fromTypes = types('fromType');
  const toTypes = types('toType');
  const rowChecks = fromTypes.length > 0 || toTypes.length > 0 || message !== null;
  const perRow = amount.length > 0 || rowChecks;
  const window = scanWindow(filter(state, 'settled'), perRow && txnId === null, rowChecks ? ROW_SCAN_DAYS : AMOUNT_WINDOW_DAYS, now);

  const playerFilter = filter(state, 'player');
  const players = playerFilter ? await resolvePlayers(playerFilter.values) : null;
  const plugins = filter(state, 'plugin')?.values.map((v) => (v === NO_PLUGIN ? null : v)) ?? null;
  const accounts = filter(state, 'account')?.values.map(Number).filter((n) => Number.isSafeInteger(n) && n > 0) ?? null;
  const categoryFilter = filter(state, 'category');
  const category = categoryFilter ? categoryPlugins(categoryFilter.values, await ledgerPlugins()) : null;

  return {
    query: {
      txnId,
      plugins,
      accounts,
      players,
      from: window.from,
      to: window.to,
      amount,
      fromTypes,
      toTypes,
      message,
      category,
    },
    window,
    empty: (playerFilter !== undefined && (players?.length ?? 0) === 0) || (category !== null && category.plugins.length === 0 && !category.taxes),
  };
}

export interface TxnSide {
  account_id: number;
  label: string;
  account_type: string | null;
  amount: string;
}

export interface TxnListItem extends ExplorerTxnListRow {
  from: TxnSide[];
  to: TxnSide[];
  posting_count: number;
}

function sideOf(p: TxnPostingRow): TxnSide {
  const dn = p.display_name?.trim();
  const label = dn && !/^[0-9a-f]{8}-?[0-9a-f]{4}-/i.test(dn) ? dn : p.owner_name ?? `Account #${p.account_id}`;
  return { account_id: p.account_id, label, account_type: p.account_type, amount: p.amount };
}

/** Attach payer and payee sides to a page of transactions (one postings query). */
async function withSides(rows: readonly ExplorerTxnListRow[]): Promise<TxnListItem[]> {
  const postings = await findPostingsForTxns(rows.map((r) => r.txn_id));
  const byTxn = new Map<number, TxnPostingRow[]>();
  for (const p of postings) {
    const list = byTxn.get(p.txn_id) ?? [];
    list.push(p);
    byTxn.set(p.txn_id, list);
  }
  return rows.map((r) => {
    const legs = byTxn.get(r.txn_id) ?? [];
    return {
      ...r,
      from: legs.filter((p) => (tryParseMoney(p.amount) ?? 0n) < 0n).map(sideOf),
      to: legs.filter((p) => (tryParseMoney(p.amount) ?? 0n) > 0n).map(sideOf),
      posting_count: legs.length,
    };
  });
}

const cursorOf = (r: ExplorerTxnListRow): KeysetCursor => ({ at: r.settlement_time, id: r.txn_id });

export interface TransactionsPage {
  rows: TxnListItem[];
  position: FeedPosition;
  count: { count: number; capped: boolean };
  window: ScanWindow;
  mode: 'id' | 'time';
  /** Chip names for entity ids on the URL, keyed `field:value`. */
  entityLabels: Record<string, string>;
}

/** Where a jump-to-date starts: the first transaction after the chosen day (newest first) or before it. */
async function jumpCursor(nav: FeedNav, dir: 'asc' | 'desc'): Promise<KeysetCursor | null> {
  if (!nav.jump) return null;
  if (dir === 'desc') {
    const after = await findFirstTxnAtOrAfter(nav.jump.end);
    return after ? { at: after.settlement_time, id: after.txn_id } : null;
  }
  const before = await findLastTxnAtOrBefore(new Date(nav.jump.start.getTime() - 1000));
  return before ? { at: before.settlement_time, id: before.txn_id } : { at: new Date(0), id: 0 };
}

async function chipLabels(state: TransactionsTableState): Promise<Record<string, string>> {
  const accountIds = filter(state, 'account')?.values.map(Number) ?? [];
  const uuids = (filter(state, 'player')?.values ?? []).filter((v) => UUID_RE.test(v));
  const [accounts, players] = await Promise.all([findAccountLabels(accountIds), findPlayerNames(uuids)]);
  const out: Record<string, string> = {};
  for (const [id, label] of accounts) out[`account:${id}`] = label;
  for (const [uuid, name] of players) out[`player:${uuid}`] = name;
  return out;
}

/** One page of the explorer plus its capped count and chip labels. */
export async function getTransactionsPage(state: TransactionsTableState, nav: FeedNav, cursor: KeysetCursor | null, now: Date = new Date()): Promise<TransactionsPage> {
  const resolved = await resolveTxnQuery(state, now);
  const mode = txnPageMode(resolved.query);
  const labels = chipLabels(state);
  if (resolved.empty) {
    return { rows: [], position: { first: null, last: null, hasPrev: false, hasNext: false }, count: { count: 0, capped: false }, window: resolved.window, mode, entityLabels: await labels };
  }
  const dir = state.sort.dir;
  const jumped = await jumpCursor(nav, dir);
  const start = jumped ?? cursor;
  const fetch = (page: KeysetPage) => listExplorerTransactions(resolved.query, page).then((rows) => inPageOrder(rows, page));

  // `back` only means something with a cursor: a filter change drops the cursor and keeps the param.
  let direction = nav.jump || !start ? 'next' as const : nav.direction;
  let result = await fetch({ cursor: start, direction, dir, limit: state.limit });
  if (direction === 'prev' && !result.more) {
    // Paged back to the start of the feed: show the first page in full.
    direction = 'next';
    result = await fetch({ cursor: null, direction, dir, limit: state.limit });
  }
  const unfiltered = state.filters.length === 0 && !state.q;
  const [rows, count, entityLabels] = await Promise.all([
    withSides(result.rows),
    // A complete first page is its own count; only run the capped count when
    // more rows exist (it repeats the page's scan up to COUNT_CAP rows).
    direction === 'next' && start === null && !result.more
      ? Promise.resolve({ count: result.rows.length, capped: false })
      : unfiltered
        ? memo('txn-explorer:count-all', 60_000, () => countExplorerTransactions(resolved.query, COUNT_CAP))
        : countExplorerTransactions(resolved.query, COUNT_CAP),
    labels,
  ]);
  const firstRow = result.rows[0];
  const lastRow = result.rows[result.rows.length - 1];
  const atStart = direction === 'next' && start === null;
  return {
    rows,
    position: {
      first: firstRow ? cursorOf(firstRow) : null,
      last: lastRow ? cursorOf(lastRow) : null,
      hasPrev: direction === 'prev' ? result.more : !atStart,
      hasNext: direction === 'next' ? result.more : true,
    },
    count,
    window: resolved.window,
    mode,
    entityLabels,
  };
}

/** A batch for the streaming export: rows after `cursor` in the table's order. */
export async function exportTransactionsBatch(
  state: TransactionsTableState,
  cursor: KeysetCursor | null,
  limit: number,
): Promise<{ rows: TxnListItem[]; next: KeysetCursor | null }> {
  const resolved = await resolveTxnQuery(state);
  if (resolved.empty) return { rows: [], next: null };
  const page: KeysetPage = { cursor, direction: 'next', dir: state.sort.dir, limit };
  const { rows, more } = inPageOrder(await listExplorerTransactions(resolved.query, page), page);
  const last = rows[rows.length - 1];
  return { rows: await withSides(rows), next: more && last ? cursorOf(last) : null };
}

// ── Detail drawer and "follow the money" ─────────────────────────────────

export const FOLLOW_HOURS = [1, 6, 24, 72] as const;
export const DEFAULT_FOLLOW_HOURS = 24;
const FOLLOW_LIMIT = 10;

export interface FollowedAccount {
  account_id: number;
  label: string;
  account_type: string | null;
  received: string;
  before: AccountMovementRow[];
  after: AccountMovementRow[];
}

export interface TransactionDetail {
  txn: NonNullable<Awaited<ReturnType<typeof findTransaction>>>;
  postings: TxnPostingRow[];
  /** Sum of the positive postings. */
  volume: string;
}

export async function getTransactionDetail(txnId: number): Promise<TransactionDetail | null> {
  const [txn, postings] = await Promise.all([findTransaction(txnId), findPostingsForTxns([txnId])]);
  if (!txn) return null;
  const credits = postings.filter((p) => (tryParseMoney(p.amount) ?? 0n) > 0n);
  return { txn, postings, volume: toDbString(sumBy(credits, (p) => p.amount)) };
}

/**
 * From a transaction, the receiving accounts' previous and next transactions
 * within `hours` either side, read from a bounded slice of each account's
 * neighbouring postings (listAccountMovements), never its whole history.
 * `canFollow` decides which receiving accounts the viewer may see history for.
 */
export async function followTheMoney(
  detail: TransactionDetail,
  hours: number,
  canFollow: (accountIds: number[]) => Promise<Set<number>>,
): Promise<FollowedAccount[]> {
  const at = detail.txn.settlement_time;
  if (!at) return [];
  const receivers = new Map<number, TxnPostingRow[]>();
  for (const p of detail.postings) {
    if ((tryParseMoney(p.amount) ?? 0n) <= 0n) continue;
    receivers.set(p.account_id, [...(receivers.get(p.account_id) ?? []), p]);
  }
  const allowed = await canFollow([...receivers.keys()]);
  const ids = [...receivers.keys()].filter((id) => allowed.has(id));
  if (ids.length === 0) return [];
  const from = new Date(at.getTime() - hours * 3_600_000);
  const to = new Date(at.getTime() + hours * 3_600_000);
  return Promise.all(ids.map(async (accountId) => {
    const legs = receivers.get(accountId) ?? [];
    const [before, after] = await Promise.all([
      listAccountMovements({ accountId, txnId: detail.txn.txn_id, direction: 'before', from, to, limit: FOLLOW_LIMIT }),
      listAccountMovements({ accountId, txnId: detail.txn.txn_id, direction: 'after', from, to, limit: FOLLOW_LIMIT }),
    ]);
    const first = legs[0] as TxnPostingRow;
    return {
      account_id: accountId,
      label: sideOf(first).label,
      account_type: first.account_type,
      received: toDbString(abs(sumBy(legs, (p) => p.amount))),
      before,
      after,
    };
  }));
}
