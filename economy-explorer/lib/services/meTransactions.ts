import 'server-only';
import type { Viewer } from '@/lib/auth/viewer';
import type { Category } from '@/lib/categories';
import { accountLabel } from '@/lib/format';
import { encodeCursor } from '@/lib/pagination/cursor';
import { selectAccountLabels, selectFirstTxnIdsAtOrAfter, selectPostings, meTransactionsColumnMap } from '@/lib/sql/me';
import { compileTableState } from '@/lib/sql/tableState';
import { dateBounds, stateCursor, type EntityOption } from '@/lib/tables/core';
import type { MeTransactionsState } from '@/lib/tables/me';
import { isMine, type MyAccount } from './meGroups';
import { toFeedRow, type FeedRow } from './meFlows';
import { myAccounts, subjectOf } from './me';

// /me/transactions (spec §8.2): the full history of the accounts the player can
// read, newest first, keyset-paged by txn_id.
//
// Bounded plans. Each page is one UNION ALL of per-account ranges on the
// (account_id, txn_id, amount) index, LIMITed per account, so it reads at most
// `limit` postings per account when only index filters are set (account, date,
// direction, amount). Category, counterparty and memo text are checked row by
// row, so with any of them the search covers one 31-day window per page (spec
// §11): if the window holds fewer matches than a page, the page ends early and
// "Older" continues from the window's start.

export const SEARCH_WINDOW_DAYS = 31;
const DAY_MS = 86_400_000;
/** Accounts searched at once (one UNION ALL branch each). */
export const MAX_ACCOUNTS = 25;

export interface MyTransactionsPage {
  rows: FeedRow[];
  /** Opaque cursor for "Older", or null at the end. */
  nextCursor: string | null;
  isFirstPage: boolean;
  /** Accounts searched. */
  selected: MyAccount[];
  /** True when no account chip is set and the player's personal accounts are searched. */
  usingDefault: boolean;
  /** Account chip ids the player cannot read; never searched. */
  ignoredAccounts: number[];
  /** The 31-day window this page searched row by row, when such filters are set. */
  window: { from: Date; to: Date } | null;
  /** Chip names for account and counterparty ids, keyed `field:value`. */
  entityLabels: Record<string, string>;
  /** Readable accounts, for the account filter's suggestions. */
  accounts: MyAccount[];
}

function valuesOf(state: MeTransactionsState, field: string): string[] {
  return state.filters.filter((f) => f.field === field).flatMap((f) => [...f.values]);
}

/** One page of the player's transactions. 2–4 queries (relationships are cached; txn bounds; the page; chip names). */
export async function getMyTransactions(viewer: Viewer, uuid: string, state: MeTransactionsState, now: Date = new Date()): Promise<MyTransactionsPage> {
  const accounts = await myAccounts(viewer, uuid);
  const byId = new Map(accounts.map((a) => [a.id, a]));
  const chosen = valuesOf(state, 'account').map(Number);
  const usingDefault = chosen.length === 0;
  const ignoredAccounts = chosen.filter((id) => !byId.has(id));
  const selected = (usingDefault ? accounts.filter((a) => isMine(a, uuid)) : chosen.flatMap((id) => byId.get(id) ?? [])).slice(0, MAX_ACCOUNTS);

  const counterpartyIds = valuesOf(state, 'counterparty').map(Number);
  const categories = valuesOf(state, 'category') as Category[];
  const texts = [state.q, ...valuesOf(state, 'memo')].filter((t) => t.length > 0);
  const rowByRow = categories.length > 0 || counterpartyIds.length > 0 || texts.length > 0;

  const dateFilter = state.filters.find((f) => f.field === 'date');
  const { from: dateFrom, to: dateTo } = dateFilter ? dateBounds(dateFilter, now) : { from: null, to: null };
  const cursor = stateCursor(state);
  // Row-by-row filters search [windowFrom, windowTo).
  const windowTo = cursor?.at ?? dateTo ?? now;
  const oldest = selected.reduce<Date | null>((min, a) => (min === null || a.createdAt < min ? a.createdAt : min), null);
  const floor = dateFrom ?? oldest ?? new Date(0);
  const windowFrom = rowByRow ? new Date(Math.max(floor.getTime(), windowTo.getTime() - SEARCH_WINDOW_DAYS * DAY_MS)) : null;

  const labelIds = [...new Set([...counterpartyIds, ...ignoredAccounts])];
  const times = [dateFrom, dateTo, windowFrom];
  const [bounds, labels] = await Promise.all([
    times.some((t) => t !== null) ? selectFirstTxnIdsAtOrAfter(times.filter((t): t is Date => t !== null)) : Promise.resolve([]),
    selectAccountLabels(labelIds),
  ]);
  const boundOf = (() => { let i = 0; return (t: Date | null) => (t === null ? undefined : bounds[i++] ?? null); })();
  const fromBound = boundOf(dateFrom);
  const toBound = boundOf(dateTo);
  const windowBound = boundOf(windowFrom);

  const entityLabels: Record<string, string> = {};
  for (const a of accounts) entityLabels[`account:${a.id}`] = a.name;
  for (const l of labels) {
    const name = accountLabel(l);
    entityLabels[`counterparty:${l.account_id}`] = name;
    entityLabels[`account:${l.account_id}`] ??= name;
  }

  const base = { selected, usingDefault, ignoredAccounts, entityLabels, accounts, isFirstPage: cursor === null };
  // A lower date bound with nothing settled since: no rows.
  if (fromBound === null || selected.length === 0) return { ...base, rows: [], nextCursor: null, window: null };

  let beforeTxnId: number | null = cursor?.id ?? null;
  if (typeof toBound === 'number') beforeTxnId = beforeTxnId === null ? toBound : Math.min(beforeTxnId, toBound);
  let fromTxnId: number | null = typeof fromBound === 'number' ? fromBound : null;
  if (rowByRow) {
    if (windowBound === null) {
      // Nothing settled since the window opened: this window is empty, so go straight on.
      return { ...base, rows: [], window: { from: windowFrom!, to: windowTo }, nextCursor: windowFrom! > floor ? encodeCursor({ at: windowFrom!, id: beforeTxnId ?? Number.MAX_SAFE_INTEGER }) : null };
    }
    fromTxnId = Math.max(fromTxnId ?? 0, windowBound ?? 0);
  }

  // A few extra rows so a transaction touching two selected accounts never splits across pages.
  const fetched = await selectPostings({
    accounts: selected.map(subjectOf), beforeTxnId, fromTxnId, settled: { from: dateFrom, before: dateTo },
    conditions: compileTableState(state, meTransactionsColumnMap, now).conditions,
    categories: categories.length ? categories : null,
    counterpartyIds: counterpartyIds.length ? counterpartyIds : null,
    texts, order: 'recent', limit: state.limit + 5,
  });
  let rows = fetched.slice(0, state.limit);
  const last = rows.at(-1);
  if (last) rows = fetched.filter((r, i) => i < state.limit || r.txn_id === last.txn_id);
  const more = fetched.length > rows.length;

  let nextCursor: string | null = null;
  const tail = rows.at(-1);
  if (more && tail) nextCursor = encodeCursor({ at: tail.settlement_time, id: tail.txn_id });
  else if (rowByRow && windowFrom && windowFrom > floor) nextCursor = encodeCursor({ at: windowFrom, id: fromTxnId ?? beforeTxnId ?? Number.MAX_SAFE_INTEGER });

  return { ...base, rows: rows.map(toFeedRow), nextCursor, window: rowByRow ? { from: windowFrom!, to: windowTo } : null };
}

/**
 * One export batch (lib/export/datasets/me.ts): up to `limit` rows after the
 * opaque `cursor`. A row-by-row search can find nothing in one 31-day window
 * and still have older windows to search, so this steps back until it has
 * rows or the history ends: the export route stops at the first empty batch.
 */
export async function exportMyTransactionsBatch(
  viewer: Viewer, uuid: string, state: MeTransactionsState, cursor: string | null, limit: number,
): Promise<{ rows: FeedRow[]; next: string | null }> {
  let at = cursor;
  for (;;) {
    const page = await getMyTransactions(viewer, uuid, { ...state, cursor: at, limit });
    if (page.rows.length > 0 || page.nextCursor === null) return { rows: page.rows, next: page.nextCursor };
    at = page.nextCursor;
  }
}

/** Account suggestions for the Account and Counterparty chips: the player's readable accounts. */
export function suggestAccounts(accounts: readonly MyAccount[], text: string): EntityOption[] {
  const t = text.trim().toLowerCase().replace(/^#/, '');
  return accounts
    .filter((a) => !t || a.name.toLowerCase().includes(t) || String(a.id) === t)
    .slice(0, 8)
    .map((a) => ({ value: String(a.id), label: a.name, detail: `#${a.id}` }));
}
