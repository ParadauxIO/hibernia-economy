import 'server-only';
import { binToUuid } from '@/lib/db';
import { CATEGORIES, CATEGORY_LABELS, categorize, type AccountType, type Category } from '@/lib/categories';
import { addDays, assertRange, eachDay, monthStart, utcDay, type DayRange } from '@/lib/rollup/days';
import { parseMoney, toDbString } from '@/lib/money';
import type { RollupJob } from '@/lib/rollup/steps';
import {
  selectAccountDayTotals, selectAccountFlowGroups, selectAccountNetSince, selectCounterpartyMonths, selectCoverage,
  selectCurrentBalance, selectEconomyDays, selectHeatmap, selectItemDays, selectRunSummary, selectTypeDays,
  selectWealthSnapshots, type CoverageRecord,
} from '@/lib/sql/rollup';

// Read helpers over the rollup tables (spec §4.2, §13.2): the contract Wave 1
// pages build on. They never fall back to live ledger queries.
//
// Ranges are inclusive UTC days, `{ from: 'YYYY-MM-DD', to: 'YYYY-MM-DD' }`
// (compatible with lib/range.ts). Every helper returns `{ data, coverage }`:
//
//   * Freshness: the worker recomputes today every tick (default 5 minutes),
//     so today lags the ledger by about one tick. Yesterday is final after the
//     first hour past 00:00 UTC; later settlement_time changes reach older days
//     with the nightly pass. Wealth snapshots refresh every 15 minutes.
//     `coverage.updatedAt` is when the job last wrote ("updated hh:mm").
//   * Coverage: history is backfilled newest-first after a deploy. `data` only
//     contains days inside the job's covered range; when `coverage.covered` is
//     false the page shows "Preparing history: covered back to
//     <coverage.coveredFrom>" for the part of the range that is missing.
//
// Money is a DECIMAL string ('1234.50'); counts are numbers.

export type { DayRange } from '@/lib/rollup/days';
export type { RollupJob } from '@/lib/rollup/steps';

export interface RangeCoverage {
  job: RollupJob;
  /** The whole requested range is computed. */
  covered: boolean;
  /** Oldest covered day, or null before the job's first run. */
  coveredFrom: string | null;
  /** Newest covered day (normally today). */
  coveredTo: string | null;
  /** Backfill reached the first day of source data, so earlier days are empty, not missing. */
  historyComplete: boolean;
  /** ISO time the job last wrote rows. */
  updatedAt: string | null;
}

export interface Covered<T> {
  data: T;
  coverage: RangeCoverage;
}

// ── coverage ──────────────────────────────────────────────────────────────

export type CoverageMap = Partial<Record<RollupJob, CoverageRecord>>;

/** Every job's covered day range and last write. One small-table read. */
export async function rollupCoverage(): Promise<CoverageMap> {
  const rows = await selectCoverage();
  return Object.fromEntries(rows.map((row) => [row.job, row])) as CoverageMap;
}

function rangeCoverage(job: RollupJob, record: CoverageRecord | undefined, range: DayRange): RangeCoverage {
  if (!record) {
    return { job, covered: false, coveredFrom: null, coveredTo: null, historyComplete: false, updatedAt: null };
  }
  const historyComplete = record.history_complete === 1;
  return {
    job,
    covered: (historyComplete || range.from >= record.from_day) && range.to <= record.to_day,
    coveredFrom: record.from_day,
    coveredTo: record.to_day,
    historyComplete,
    updatedAt: record.updated_at,
  };
}

/** The part of `range` the job has computed, or null if none of it is. */
function coveredPart(coverage: RangeCoverage, range: DayRange): DayRange | null {
  if (!coverage.coveredFrom || !coverage.coveredTo) return null;
  const from = coverage.historyComplete || range.from > coverage.coveredFrom ? range.from : coverage.coveredFrom;
  const to = range.to < coverage.coveredTo ? range.to : coverage.coveredTo;
  return from <= to ? { from, to } : null;
}

async function coverageFor(job: RollupJob, range: DayRange): Promise<RangeCoverage> {
  assertRange(range);
  return rangeCoverage(job, (await rollupCoverage())[job], range);
}

/** Whether every day of `range` has been computed for `job`. */
export async function isCovered(job: RollupJob, range: DayRange): Promise<boolean> {
  return (await coverageFor(job, range)).covered;
}

// ── accounts ──────────────────────────────────────────────────────────────

export interface AccountDayPoint {
  day: string;
  inflow: string;
  outflow: string;
  net: string;
  /** Transactions per account-day, summed over the set (a transfer between two
   * accounts of the set counts once for each). */
  txnCount: number;
}

/**
 * Daily gross inflow, outflow and net over a set of accounts.
 * Grain: one point per covered day of the range, zeros on quiet days.
 * Source: explorer_rollup_account_day (job `account_day`).
 * `excludeInternal` drops transfers between the set's own accounts (see
 * is_internal in V43), as /me income and spending require.
 */
export async function accountDaySeries(
  accountIds: readonly number[], range: DayRange, opts: { excludeInternal?: boolean } = {},
): Promise<Covered<AccountDayPoint[]>> {
  const coverage = await coverageFor('account_day', range);
  const part = coveredPart(coverage, range);
  if (!part) return { data: [], coverage };
  const rows = await selectAccountDayTotals(accountIds, part.from, part.to, opts.excludeInternal ?? false);
  const byDay = new Map(rows.map((row) => [row.day, row]));
  const data = eachDay(part).map((day) => {
    const row = byDay.get(day);
    const inflow = parseMoney(row?.inflow ?? '0.00');
    const outflow = parseMoney(row?.outflow ?? '0.00');
    return { day, inflow: toDbString(inflow), outflow: toDbString(outflow), net: toDbString(inflow - outflow), txnCount: row?.txn_count ?? 0 };
  });
  return { data, coverage };
}

export interface BalancePoint {
  day: string;
  /** Closing balance at the end of the day (UTC). */
  balance: string;
}

/**
 * Closing balance per day over a set of accounts, derived without a snapshot
 * table: balance(end of d) = current balance − Σ net of every later day.
 * Grain: one point per day of the range.
 * Source: account_balances_mat + explorer_rollup_account_day.
 * Coverage: needs account_day from range.from through today, because every
 * later day's net is subtracted; otherwise `covered` is false and `data` is
 * empty. Within the one-tick rollup lag, transactions not yet rolled up shift
 * every point by their net.
 */
export async function balanceSeries(accountIds: readonly number[], range: DayRange): Promise<Covered<BalancePoint[]>> {
  assertRange(range);
  const today = utcDay();
  const coverage = rangeCoverage('account_day', (await rollupCoverage()).account_day,
    { from: range.from, to: range.to > today ? range.to : today });
  if (!coverage.covered || accountIds.length === 0) return { data: [], coverage };
  const [current, nets] = await Promise.all([
    selectCurrentBalance(accountIds),
    selectAccountNetSince(accountIds, addDays(range.from, 1)),
  ]);
  const netByDay = new Map(nets.map((row) => [row.day, parseMoney(row.net)]));
  // Walk backwards from now: closing(d) = closing(d + 1) − net(d + 1).
  let balance = parseMoney(current);
  for (const [day, net] of netByDay) if (day > range.to) balance -= net;
  const data: BalancePoint[] = [];
  for (let day = range.to; day >= range.from; day = addDays(day, -1)) {
    data.push({ day, balance: toDbString(balance) });
    balance -= netByDay.get(day) ?? 0n;
  }
  return { data: data.reverse(), coverage };
}

export interface CategoryTotal {
  category: Category;
  label: string;
  /** Set for `taxes` (personal, corporate, income, sales); null otherwise. */
  taxKind: string | null;
  inflow: string;
  outflow: string;
  net: string;
}

/**
 * Inflow and outflow by category (lib/categories.ts) over a set of accounts.
 * Grain: the range's covered days, summed. Taxes are split by tax kind.
 * Internal transfers come back as the `internal` category so callers can show
 * or exclude them (income and spending exclude them).
 * Source: explorer_rollup_account_day (job `account_day`).
 */
export async function categoryTotals(accountIds: readonly number[], range: DayRange): Promise<Covered<CategoryTotal[]>> {
  const coverage = await coverageFor('account_day', range);
  const part = coveredPart(coverage, range);
  if (!part) return { data: [], coverage };
  const totals = new Map<string, { category: Category; taxKind: string | null; inflow: bigint; outflow: bigint }>();
  const add = (category: Category, taxKind: string | null, inflow: bigint, outflow: bigint) => {
    const kind = category === 'taxes' ? taxKind : null;
    const key = `${category}:${kind ?? ''}`;
    const entry = totals.get(key) ?? { category, taxKind: kind, inflow: 0n, outflow: 0n };
    entry.inflow += inflow;
    entry.outflow += outflow;
    totals.set(key, entry);
  };
  for (const row of await selectAccountFlowGroups(accountIds, part.from, part.to)) {
    const input = {
      pluginKey: row.plugin_key,
      taxKind: row.tax_kind || null,
      counterpartyType: (row.counterparty_type || null) as AccountType | null,
      internal: row.is_internal === 1,
    };
    const inflow = parseMoney(row.inflow);
    const outflow = parseMoney(row.outflow);
    if (inflow !== 0n) add(categorize({ ...input, direction: 'in' }), input.taxKind, inflow, 0n);
    if (outflow !== 0n) add(categorize({ ...input, direction: 'out' }), input.taxKind, 0n, outflow);
  }
  const data = [...totals.values()]
    .sort((a, b) => CATEGORIES.indexOf(a.category) - CATEGORIES.indexOf(b.category)
      || (a.taxKind ?? '').localeCompare(b.taxKind ?? ''))
    .map((t) => ({
      category: t.category, label: CATEGORY_LABELS[t.category], taxKind: t.taxKind,
      inflow: toDbString(t.inflow), outflow: toDbString(t.outflow), net: toDbString(t.inflow - t.outflow),
    }));
  return { data, coverage };
}

export interface CounterpartyTotal {
  counterpartyId: number;
  displayName: string | null;
  accountType: string;
  ownerUuid: string | null;
  ownerName: string | null;
  /** Received from the counterparty. */
  inflow: string;
  /** Paid to the counterparty. */
  outflow: string;
  volume: string;
  txnCount: number;
}

/**
 * Top counterparties of a set of accounts by volume (inflow + outflow).
 * Grain: calendar months. The range is widened to whole months (from the first
 * of range.from's month through the end of range.to's month), and coverage is
 * checked for that widened range. Accounts inside the set are not listed.
 * Amounts are matched per transaction leg pair, exact for two-leg transfers.
 * Source: explorer_rollup_pair_month (job `pair_month`).
 */
export async function counterparties(
  accountIds: readonly number[], range: DayRange, limit = 25,
): Promise<Covered<CounterpartyTotal[]>> {
  const widened = { from: monthStart(range.from), to: range.to };
  const coverage = await coverageFor('pair_month', widened);
  const part = coveredPart(coverage, widened);
  if (!part) return { data: [], coverage };
  const rows = await selectCounterpartyMonths(accountIds, monthStart(part.from), monthStart(part.to), limit);
  const data = rows.map((row) => ({
    counterpartyId: row.counterparty_id,
    displayName: row.display_name,
    accountType: row.account_type,
    ownerUuid: row.owner_uuid_bin ? binToUuid(row.owner_uuid_bin) : null,
    ownerName: row.owner_name,
    inflow: row.inflow,
    outflow: row.outflow,
    volume: toDbString(parseMoney(row.inflow) + parseMoney(row.outflow)),
    txnCount: row.txn_count,
  }));
  return { data, coverage };
}

// ── economy ───────────────────────────────────────────────────────────────

export interface EconomyDay {
  day: string;
  /** Bridge-adjusted, as getDailyVolume: an adjacent SYSTEM bridge pair counts once. */
  txnCount: number;
  volume: string;
  rawTxnCount: number;
  rawVolume: string;
  bridgePairs: number;
  /** Distinct owners of PERSONAL accounts with a posting that day. */
  activePlayers: number;
  /** Players first seen that day. */
  newPlayers: number;
}

/**
 * Economy-wide totals per day. Grain: one row per covered day (quiet days are
 * zero rows, not gaps). Source: explorer_rollup_economy_day (job `economy_day`).
 */
export async function economyDays(range: DayRange): Promise<Covered<EconomyDay[]>> {
  const coverage = await coverageFor('economy_day', range);
  const part = coveredPart(coverage, range);
  if (!part) return { data: [], coverage };
  const data = (await selectEconomyDays(part.from, part.to)).map((row) => ({
    day: row.day, txnCount: row.txn_count, volume: row.volume, rawTxnCount: row.raw_txn_count,
    rawVolume: row.raw_volume, bridgePairs: row.bridge_pairs, activePlayers: row.active_players,
    newPlayers: row.new_players,
  }));
  return { data, coverage };
}

export interface TypeDay { day: string; accountType: AccountType; pluginKey: string; inflow: string; outflow: string }

/**
 * Gross flows per day, account type and plugin; inflow − outflow is that
 * type's supply change. Grain: (day, account type, plugin), active rows only.
 * Source: explorer_rollup_type_day (job `type_day`).
 */
export async function typeDays(range: DayRange): Promise<Covered<TypeDay[]>> {
  const coverage = await coverageFor('type_day', range);
  const part = coveredPart(coverage, range);
  if (!part) return { data: [], coverage };
  const data = (await selectTypeDays(part.from, part.to)).map((row) => ({
    day: row.day, accountType: row.account_type as AccountType, pluginKey: row.plugin_key,
    inflow: row.inflow, outflow: row.outflow,
  }));
  return { data, coverage };
}

export interface HeatmapCell {
  /** 0 = Monday .. 6 = Sunday (the existing HourHeatmapCell.dow0). */
  weekday: number;
  /** UTC hour, 0..23. */
  hour: number;
  txnCount: number;
}

/**
 * Transactions per weekday and UTC hour over the range's covered days.
 * Grain: summed (weekday, hour) cells, only non-empty ones.
 * Source: explorer_rollup_hour_heatmap (job `hour_heatmap`).
 */
export async function heatmap(range: DayRange): Promise<Covered<HeatmapCell[]>> {
  const coverage = await coverageFor('hour_heatmap', range);
  const part = coveredPart(coverage, range);
  if (!part) return { data: [], coverage };
  const data = (await selectHeatmap(part.from, part.to)).map((row) => ({ weekday: row.weekday, hour: row.hour, txnCount: row.txn_count }));
  return { data, coverage };
}

export interface ItemDay {
  itemKey: string;
  day: string;
  direction: 'BUY' | 'SELL';
  qty: number;
  volume: string;
  trades: number;
  /** Unit prices are DECIMAL(19,4) strings, as chestshop_sale stores them. */
  minUnitPrice: string;
  maxUnitPrice: string;
  openUnitPrice: string;
  closeUnitPrice: string;
}

/**
 * ChestShop trades per item, day and direction (BUY / SELL as recorded),
 * with candle prices. Grain: active (item, day, direction) rows only.
 * Source: explorer_rollup_item_day (job `item_day`).
 */
export async function itemDays(itemKeys: readonly string[], range: DayRange): Promise<Covered<ItemDay[]>> {
  const coverage = await coverageFor('item_day', range);
  const part = coveredPart(coverage, range);
  if (!part) return { data: [], coverage };
  const data = (await selectItemDays(itemKeys, part.from, part.to)).map((row) => ({
    itemKey: row.item_key, day: row.day, direction: row.direction, qty: row.qty, volume: row.volume,
    trades: row.trades, minUnitPrice: row.min_unit_price, maxUnitPrice: row.max_unit_price,
    openUnitPrice: row.open_unit_price, closeUnitPrice: row.close_unit_price,
  }));
  return { data, coverage };
}

export interface WealthSnapshot {
  day: string;
  /** When the day's last snapshot was taken (ISO). */
  takenAt: string;
  supply: Record<'personal' | 'business' | 'government' | 'system', string>;
  /** Active PERSONAL accounts with a positive balance. */
  personalHolders: number;
  /** Over positive PERSONAL balances, as lib/derived.ts; decimal strings 0..1. */
  gini: string;
  top1Share: string;
  top10Share: string;
  /** getBalanceDistribution bucket key → account count. */
  holderBuckets: Record<string, number>;
}

/**
 * Wealth as last observed on each day. Grain: one row per day the worker ran.
 * Snapshots start on the worker's first run and are never reconstructed, so
 * coverage.coveredFrom is the first snapshot and earlier days stay uncovered.
 * Source: explorer_snapshot_wealth_day (job `wealth`).
 */
export async function wealthSnapshots(range: DayRange): Promise<Covered<WealthSnapshot[]>> {
  const coverage = await coverageFor('wealth', range);
  const part = coveredPart(coverage, range);
  if (!part) return { data: [], coverage };
  const data = (await selectWealthSnapshots(part.from, part.to)).map((row) => ({
    day: row.day,
    takenAt: row.taken_at,
    supply: { personal: row.supply_personal, business: row.supply_business, government: row.supply_government, system: row.supply_system },
    personalHolders: row.personal_holders,
    gini: row.gini,
    top1Share: row.top1_share,
    top10Share: row.top10_share,
    // MariaDB may send the JSON alias as text or (11.x) as JSON that mysql2 parses.
    holderBuckets: (typeof row.holder_buckets === 'string' ? JSON.parse(row.holder_buckets) : row.holder_buckets) as Record<string, number>,
  }));
  return { data, coverage };
}

// ── health ────────────────────────────────────────────────────────────────

export const STALE_AFTER_MS = 10 * 60_000;

export interface RollupHealth {
  /** Newest successful step, ISO. */
  lastSuccessAt: string | null;
  /** True when no step has succeeded in the last 10 minutes (the alert threshold). */
  stale: boolean;
  lastErrorAt: string | null;
  errorsLastHour: number;
  coverage: Record<string, { from: string; to: string; historyComplete: boolean; updatedAt: string }>;
}

/** Run-log staleness and per-job coverage, for /healthz?rollup=1. */
export async function rollupHealth(now: Date = new Date()): Promise<RollupHealth> {
  const [runs, coverage] = await Promise.all([selectRunSummary(), selectCoverage()]);
  const last = runs.last_success_at ? Date.parse(runs.last_success_at) : null;
  return {
    lastSuccessAt: runs.last_success_at,
    stale: last === null || now.getTime() - last > STALE_AFTER_MS,
    lastErrorAt: runs.last_error_at,
    errorsLastHour: runs.errors_last_hour,
    coverage: Object.fromEntries(coverage.map((c) => [c.job, {
      from: c.from_day, to: c.to_day, historyComplete: c.history_complete === 1, updatedAt: c.updated_at,
    }])),
  };
}
