import 'server-only';
import { memo } from '@/lib/cache';
import { CATEGORY_LABELS, categorize, type AccountType, type Category } from '@/lib/categories';
import { accountLabel } from '@/lib/format';
import { parseMoney, toDbString } from '@/lib/money';
import { addDays, utcDay, type DayRange } from '@/lib/rollup/days';
import { decodeCursor, encodeCursor, type KeysetCursor } from '@/lib/pagination/cursor';
import {
  findAccountLabels, findNewerCursor, findScanEdge, findTxnBounds, historyIsFiltered, listAccountHistory,
  listTransactionLegs, sumPostingsSince, type HistoryLeg, type HistoryWindow,
} from '@/lib/sql/accountHistory';
import { findFirmForAccount, listFirmAccounts, type FirmAccountRow, type FirmSummary } from '@/lib/sql/firm';
import type { ExplorerAccountRow } from '@/lib/sql/ledger';
import { listOwnedAccounts, listPlayerFirmRoles, type OwnedAccountRow, type PlayerFirmRole } from '@/lib/sql/players';
import {
  accountDaySeries, balanceSeries, categoryTotals, counterparties,
  type BalancePoint, type CategoryTotal, type CounterpartyTotal, type RangeCoverage,
} from '@/lib/services/rollup';
import { SCAN_WINDOW, TEXT_SEARCH_DEFAULT_DAYS, type AccountTxnsState } from '@/lib/tables/accountTxns';
import { dateBounds, type EntityOption, type FilterState } from '@/lib/tables/core';

// The account page's data (spec §7.1). Three tiers, each with its own cache:
//
//   * Context: who holds the account and what is related to it. Public.
//   * Activity: 365 days of daily totals from the rollups. Public for
//     GOVERNMENT and BUSINESS accounts (totals only, §14 Q2); read only behind
//     the history gate for the others.
//   * History: range-scoped charts and the transactions page. Behind the gate.
//
// The account's history is the same for every authorised viewer (the gate
// decides whether you see it, not what), so the caches are keyed by account,
// never by viewer, and are read only after the page has checked access.

const FIVE_MINUTES = 5 * 60_000;

// ── Context ───────────────────────────────────────────────────────────────

export interface AccountContext {
  /** BUSINESS: the firm holding the account. */
  firm: FirmSummary | null;
  /** BUSINESS: every open account of that firm, including this one. */
  firmAccounts: FirmAccountRow[];
  /** PERSONAL / GOVERNMENT / SYSTEM: other open accounts of the same owner and type. */
  siblings: OwnedAccountRow[];
  /** PERSONAL: the owner's firm roles (proprietor or employee). Never balances. */
  ownerFirms: PlayerFirmRole[];
}

/** Public context for the header and the Related rail. Cached for five minutes. */
export function getAccountContext(account: ExplorerAccountRow): Promise<AccountContext> {
  return memo(`account-ctx:v1:${account.account_id}`, FIVE_MINUTES, async () => {
    if (account.account_type === 'BUSINESS') {
      const firm = await findFirmForAccount(account.account_id);
      return { firm, firmAccounts: firm ? await listFirmAccounts(firm.firm_id) : [], siblings: [], ownerFirms: [] };
    }
    if (!account.owner_uuid) return { firm: null, firmAccounts: [], siblings: [], ownerFirms: [] };
    const [siblings, ownerFirms] = await Promise.all([
      listOwnedAccounts(account.owner_uuid, account.account_type, { excludeId: account.account_id, limit: 8 }),
      account.account_type === 'PERSONAL' ? listPlayerFirmRoles(account.owner_uuid) : Promise.resolve([]),
    ]);
    return { firm: null, firmAccounts: [], siblings, ownerFirms };
  });
}

// ── Activity (365 days of rollups) ────────────────────────────────────────

export interface PeriodTotals { inflow: string; outflow: string; txnCount: number }

export interface AccountActivity {
  coverage: RangeCoverage;
  /** The last 30 days, today included, and the 30 before them. */
  last30: PeriodTotals;
  prev30: PeriodTotals | null;
  /** Calendar months, oldest first, up to 12 (the current month is partial). */
  months: { month: string; txnCount: number; volume: string }[];
  /** Transactions per day for the activity calendar. */
  days: { date: string; count: number }[];
}

function totals(points: { inflow: string; outflow: string; txnCount: number }[]): PeriodTotals {
  let inflow = 0n;
  let outflow = 0n;
  let txnCount = 0;
  for (const p of points) {
    inflow += parseMoney(p.inflow);
    outflow += parseMoney(p.outflow);
    txnCount += p.txnCount;
  }
  return { inflow: toDbString(inflow), outflow: toDbString(outflow), txnCount };
}

/** A year of daily totals for one or more accounts, summarised. Cached for five minutes. */
export function getActivity(accountIds: readonly number[], opts: { excludeInternal?: boolean } = {}): Promise<AccountActivity> {
  const ids = [...accountIds].sort((a, b) => a - b);
  return memo(`account-activity:v1:${ids.join(',')}:${opts.excludeInternal ? 1 : 0}`, FIVE_MINUTES, async () => {
    const today = utcDay();
    const { data, coverage } = await accountDaySeries(ids, { from: addDays(today, -364), to: today }, opts);
    const since30 = addDays(today, -29);
    const since60 = addDays(today, -59);
    const last30 = totals(data.filter((d) => d.day >= since30));
    const prevDays = data.filter((d) => d.day >= since60 && d.day < since30);
    const prevCovered = coverage.historyComplete || (coverage.coveredFrom !== null && coverage.coveredFrom <= since60);
    const months = new Map<string, { txnCount: number; volume: bigint }>();
    for (const d of data) {
      const key = d.day.slice(0, 7);
      const m = months.get(key) ?? { txnCount: 0, volume: 0n };
      m.txnCount += d.txnCount;
      m.volume += parseMoney(d.inflow) + parseMoney(d.outflow);
      months.set(key, m);
    }
    return {
      coverage,
      last30,
      prev30: prevCovered ? totals(prevDays) : null,
      months: [...months.entries()].slice(-12).map(([month, m]) => ({ month, txnCount: m.txnCount, volume: toDbString(m.volume) })),
      days: data.filter((d) => d.txnCount > 0).map((d) => ({ date: d.day, count: d.txnCount })),
    };
  });
}

/** Relative change, or null when there is no previous figure to compare with. */
export function change(current: string | number, previous: string | number | null | undefined): number | null {
  if (previous === null || previous === undefined) return null;
  const c = typeof current === 'number' ? BigInt(current) * 100n : parseMoney(current);
  const p = typeof previous === 'number' ? BigInt(previous) * 100n : parseMoney(previous);
  if (p === 0n) return null;
  return Number(((c - p) * 10_000n) / (p < 0n ? -p : p)) / 10_000;
}

// ── History for a range (behind the gate) ─────────────────────────────────

export interface AccountRangeHistory {
  balance: { data: BalancePoint[]; coverage: RangeCoverage };
  days: { data: { day: string; inflow: string; outflow: string; txnCount: number }[]; coverage: RangeCoverage };
  categories: { data: CategoryTotal[]; coverage: RangeCoverage };
  counterparties: { data: CounterpartyTotal[]; coverage: RangeCoverage };
}

/** Charts, category breakdown and counterparties for a range. Cached per account and range. */
export function getRangeHistory(accountIds: readonly number[], range: DayRange, opts: { excludeInternal?: boolean; counterpartyLimit?: number } = {}): Promise<AccountRangeHistory> {
  const ids = [...accountIds].sort((a, b) => a - b);
  const limit = opts.counterpartyLimit ?? 10;
  return memo(`account-range:v1:${ids.join(',')}:${range.from}:${range.to}:${opts.excludeInternal ? 1 : 0}:${limit}`, FIVE_MINUTES, async () => {
    const [balance, days, cats, cps] = await Promise.all([
      balanceSeries(ids, range),
      accountDaySeries(ids, range, opts),
      categoryTotals(ids, range),
      counterparties(ids, range, limit),
    ]);
    return { balance, days, categories: cats, counterparties: cps };
  });
}

// ── Transactions page ─────────────────────────────────────────────────────

/** Postings summed to place a later page's running balance. Further back, the column is left out. */
export const RUNNING_BALANCE_CAP = 20_000;

export interface Counterparty {
  accountId: number;
  name: string;
  accountType: string;
  ownerUuid: string | null;
  ownerName: string | null;
}

export interface TxnView {
  txnId: number;
  at: Date;
  amount: string;
  /** Balance right after this transaction, when cheap to know. */
  balanceAfter: string | null;
  category: Category;
  categoryLabel: string;
  message: string | null;
  memo: string | null;
  initiator: { uuid: string; name: string | null } | null;
  counterparty: Counterparty | null;
  /** Other legs on the counterparty side beyond the one shown. */
  otherLegs: number;
}

export interface TxnPage {
  rows: TxnView[];
  /** Older rows exist: the cursor to fetch them. */
  next: KeysetCursor | null;
  /** Link target for "Newer": a cursor, 'first' for the newest page, or null on the newest page. */
  newer: KeysetCursor | 'first' | null;
  /** The filtered window ended before a full page matched: how far back it looked. */
  searchedBackTo: Date | null;
  /** Text search with no Date filter only looks this many days back. */
  implicitDays: number | null;
  runningBalance: boolean;
  /** Chip names for counterparty ids on the URL, keyed `counterparty:<id>`. */
  entityLabels: Record<string, string>;
}

const TEXT_FIELDS = new Set(['memo']);

/** The state as queried: text search without a Date filter is limited to recent days. */
export function effectiveState(state: AccountTxnsState): { state: AccountTxnsState; implicitDays: number | null } {
  const hasText = state.q !== '' || state.filters.some((f) => TEXT_FIELDS.has(f.field));
  const hasDate = state.filters.some((f) => f.field === 'date');
  if (!hasText || hasDate) return { state, implicitDays: null };
  const date: FilterState<'date'> = { field: 'date', op: 'gte', values: [`-${TEXT_SEARCH_DEFAULT_DAYS}d`] };
  return { state: { ...state, filters: [...state.filters, date] }, implicitDays: TEXT_SEARCH_DEFAULT_DAYS };
}

function legName(leg: Pick<HistoryLeg, 'account_id' | 'display_name' | 'owner_name' | 'owner_uuid'>): string {
  return accountLabel({ account_id: leg.account_id, display_name: leg.display_name, owner_name: leg.owner_name, owner_uuid: leg.owner_uuid });
}

/** The rollups' "internal" rule for one pair of accounts (V43 is_internal). */
function isInternal(subject: ExplorerAccountRow, subjectFirmId: number | null, other: HistoryLeg): boolean {
  if (other.account_id === subject.account_id) return true;
  if (subject.account_type === 'BUSINESS' && other.account_type === 'BUSINESS') return subjectFirmId !== null && subjectFirmId === other.firm_id;
  if (subject.account_type === other.account_type && (subject.account_type === 'PERSONAL' || subject.account_type === 'GOVERNMENT')) {
    return subject.owner_uuid !== null && subject.owner_uuid === other.owner_uuid;
  }
  return false;
}

/**
 * One page of the history table. Reads (at most): a txn-bounds probe when a
 * date applies, the page, its legs, a capped sum for the running balance on
 * later pages, the newer-page cursor, and chip labels when a counterparty
 * filter is set. Not cached: the cursor makes every page its own key.
 */
export async function getTransactionsPage(
  account: ExplorerAccountRow, firmId: number | null, input: AccountTxnsState, now: Date = new Date(),
): Promise<TxnPage> {
  const { state, implicitDays } = effectiveState(input);
  const filtered = historyIsFiltered(state);
  const cursor = decodeCursor(state.cursor);
  const limit = state.limit;
  const empty: TxnPage = { rows: [], next: null, newer: cursor ? 'first' : null, searchedBackTo: null, implicitDays, runningBalance: false, entityLabels: {} };

  const counterpartyIds = state.filters.filter((f) => f.field === 'counterparty').flatMap((f) => f.values.map(Number));
  const labelsPromise = findAccountLabels(counterpartyIds).then((rows) => Object.fromEntries(rows.map((r) => [`counterparty:${r.account_id}`, accountLabel(r)])));

  // Date filter → txn bounds, so the scan starts and ends inside the range.
  let minTxnId: number | null = null;
  let upper: number | null = null;
  const date = state.filters.find((f) => f.field === 'date');
  if (date) {
    const { from, to } = dateBounds(date, now);
    const bounds = await findTxnBounds(from, to);
    if (from && bounds.first === null) return { ...empty, entityLabels: await labelsPromise };
    minTxnId = from ? bounds.first : null;
    upper = to ? bounds.after : null;
  }
  const beforeTxnId = cursor && upper !== null ? Math.min(cursor.id, upper) : cursor?.id ?? upper;
  const window: HistoryWindow = { accountId: account.account_id, beforeTxnId, minTxnId, limit: limit + 1, scan: SCAN_WINDOW };

  const rows = await listAccountHistory(window, state, now);
  const shown = rows.slice(0, limit);
  const [legs, base, newerId, edge, entityLabels] = await Promise.all([
    listTransactionLegs(shown.map((r) => r.txn_id)),
    // Running balance: the balance after the newest transaction the window can see.
    beforeTxnId === null ? Promise.resolve(account.balance) : sumPostingsSince(account.account_id, beforeTxnId, RUNNING_BALANCE_CAP)
      .then((since) => (since === null ? null : toDbString(parseMoney(account.balance) - parseMoney(since)))),
    cursor && !filtered ? findNewerCursor(account.account_id, cursor.id, limit) : Promise.resolve(null),
    filtered && rows.length === 0 ? findScanEdge(window) : Promise.resolve(null),
    labelsPromise,
  ]);

  const byTxn = new Map<number, HistoryLeg[]>();
  for (const leg of legs) byTxn.set(leg.txn_id, [...(byTxn.get(leg.txn_id) ?? []), leg]);

  const views: TxnView[] = shown.map((row) => {
    const txnLegs = byTxn.get(row.txn_id) ?? [];
    const amount = parseMoney(row.amount);
    const own = txnLegs.filter((l) => l.account_id === account.account_id);
    const opposite = txnLegs
      .filter((l) => l.account_id !== account.account_id && (amount >= 0n ? parseMoney(l.amount) < 0n : parseMoney(l.amount) > 0n))
      .sort((a, b) => {
        const x = parseMoney(a.amount); const y = parseMoney(b.amount);
        const ax = x < 0n ? -x : x; const ay = y < 0n ? -y : y;
        return ax === ay ? a.posting_id - b.posting_id : ax > ay ? -1 : 1;
      });
    const top = opposite[0] ?? null;
    const direction = amount >= 0n ? 'in' : 'out';
    const category = categorize({
      pluginKey: row.plugin_system ?? '',
      taxKind: row.tax_kind ?? (row.is_fine ? 'fine' : null),
      counterpartyType: (top?.account_type ?? null) as AccountType | null,
      direction,
      internal: top !== null && isInternal(account, firmId, top),
    });
    return {
      txnId: row.txn_id,
      at: row.settlement_time,
      amount: row.amount,
      balanceAfter: base === null ? null : toDbString(parseMoney(base) - parseMoney(row.newer)),
      category,
      categoryLabel: CATEGORY_LABELS[category],
      message: row.message,
      memo: own.map((l) => l.memo).find((m) => m) ?? null,
      initiator: row.initiator_uuid ? { uuid: row.initiator_uuid, name: row.initiator_name } : null,
      counterparty: top ? { accountId: top.account_id, name: legName(top), accountType: top.account_type, ownerUuid: top.owner_uuid, ownerName: top.owner_name } : null,
      otherLegs: Math.max(0, opposite.length - 1),
    };
  });

  // Next page: after the last row shown, or past the window when it ran out first.
  const last = shown[shown.length - 1];
  const scanned = rows[0]?.scanned ?? edge?.scanned ?? 0;
  const windowFull = filtered && scanned >= SCAN_WINDOW;
  let next: KeysetCursor | null = null;
  let searchedBackTo: Date | null = null;
  if (rows.length > limit && last) {
    next = { at: last.settlement_time, id: last.txn_id };
  } else if (windowFull) {
    const oldest = rows[0]?.oldest_scanned ?? edge?.oldest ?? null;
    const oldestAt = rows[0]?.oldest_scanned_at ?? edge?.oldestAt ?? null;
    if (oldest !== null) {
      next = { at: oldestAt ?? new Date(0), id: oldest };
      searchedBackTo = oldestAt;
    }
  }

  let newer: TxnPage['newer'] = null;
  if (cursor) newer = newerId === null ? 'first' : { at: cursor.at, id: newerId };

  return { rows: views, next, newer, searchedBackTo, implicitDays, runningBalance: base !== null, entityLabels };
}

// ── Exports ───────────────────────────────────────────────────────────────

/** Scan windows one export batch may walk through before yielding its continuation cursor. */
export const EXPORT_MAX_WINDOWS = 20;

/**
 * One batch of an account's history for the export route (lib/export): the
 * same pages as the table, but larger, continuing from `cursor`. Filtered
 * exports keep scanning windows until rows turn up or the history ends, up to
 * EXPORT_MAX_WINDOWS windows (100k transactions) per batch. An empty batch
 * retains its cursor so the stream can continue looking for older matches.
 */
export async function exportAccountBatch(
  account: ExplorerAccountRow, firmId: number | null, state: AccountTxnsState, cursor: KeysetCursor | null, limit: number,
): Promise<{ rows: TxnView[]; next: KeysetCursor | null }> {
  let at = cursor;
  for (let i = 0; i < EXPORT_MAX_WINDOWS; i++) {
    const page = await getTransactionsPage(account, firmId, { ...state, cursor: at ? encodeCursor(at) : null, limit });
    if (page.rows.length > 0 || !page.next) return { rows: page.rows, next: page.next };
    at = page.next;
  }
  return { rows: [], next: at };
}

// ── Counterparty typeahead ────────────────────────────────────────────────

/**
 * Suggestions for the Counterparty filter: the account's own counterparties
 * over the last year (from the rollups, cached), matched on name, plus an exact
 * `#id`. The caller has already checked the viewer can read this account.
 */
export async function suggestCounterparties(accountId: number, text: string): Promise<EntityOption[]> {
  const term = text.trim().toLowerCase().slice(0, 64);
  const today = utcDay();
  const { counterparties: cps } = await getRangeHistory([accountId], { from: addDays(today, -364), to: today }, { counterpartyLimit: 100 });
  const options: EntityOption[] = cps.data
    .map((c) => ({
      value: String(c.counterpartyId),
      label: accountLabel({ account_id: c.counterpartyId, display_name: c.displayName, owner_name: c.ownerName, owner_uuid: c.ownerUuid }),
      detail: `${c.accountType.charAt(0)}${c.accountType.slice(1).toLowerCase()} · #${c.counterpartyId}`,
    }))
    .filter((o) => !term || o.label.toLowerCase().includes(term) || o.value === term.replace(/^#/, ''));
  const id = /^#?(\d{1,10})$/.exec(term)?.[1];
  if (id && !options.some((o) => o.value === id)) {
    const [row] = await findAccountLabels([Number(id)]);
    if (row) options.unshift({ value: id, label: accountLabel(row), detail: `#${id}` });
  }
  return options.slice(0, 8);
}
