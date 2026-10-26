import 'server-only';
import { cache } from 'react';
import { memo } from '@/lib/cache';
import { gini, topShare } from '@/lib/derived';
import { parseMoney, toDbString } from '@/lib/money';
import { addDays, rangeDays, type DayRange } from '@/lib/range';
import { getBalanceByType, getPersonalSupply, getTopAccounts } from '@/lib/sql/ledger';
import { selectTypeFlows } from '@/lib/sql/moneyFlow';
import { getBalanceDistribution, getPersonalBalances } from '@/lib/sql/stats';
import {
  economyDays, heatmap, rollupCoverage, typeDays, wealthSnapshots,
  type CoverageMap, type EconomyDay, type RangeCoverage, type RollupJob, type WealthSnapshot,
} from '@/lib/services/rollup';

// Economy overview, wealth and money flow (PAR-541). Activity over time comes
// only from the rollups (lib/services/rollup.ts), which the in-process worker
// keeps about one tick (5 minutes) behind the ledger. Nothing here scans the
// ledger: a range the rollups have not reached yet comes back with
// `coverage.covered === false` and only the days that are ready, and the page
// says "Preparing history". Current balances come from account_balances_mat.
//
// Caching: every rollup-backed entry is keyed by its job's coverage.updatedAt,
// read once per request from the 7-row coverage table. A rollup write changes
// the key, so pages show new rollup data on the next request and never serve a
// stale "preparing" state after the backfill catches up.

/** Rollup coverage for every job, read once per request. */
export const coverageNow = cache((): Promise<CoverageMap> => rollupCoverage());

/** Coverage of `range` for one job, as lib/services/rollup.ts computes it. */
export function jobCoverage(map: CoverageMap, job: RollupJob, range: DayRange): RangeCoverage {
  const record = map[job];
  if (!record) return { job, covered: false, coveredFrom: null, coveredTo: null, historyComplete: false, updatedAt: null };
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

/** The part of `range` a job has computed, or null if none of it is. */
export function coveredPart(coverage: RangeCoverage, range: DayRange): DayRange | null {
  if (!coverage.coveredFrom || !coverage.coveredTo) return null;
  const from = coverage.historyComplete || range.from > coverage.coveredFrom ? range.from : coverage.coveredFrom;
  const to = range.to < coverage.coveredTo ? range.to : coverage.coveredTo;
  return from <= to ? { from, to } : null;
}

const version = (map: CoverageMap, ...jobs: RollupJob[]) => jobs.map((job) => map[job]?.updated_at ?? 'none').join(',');
const key = (range: DayRange) => `${range.from}:${range.to}`;

/** Rollup reads are a few hundred rows; five minutes matches the worker's tick. */
const ROLLUP_TTL_MS = 5 * 60_000;

/**
 * Longest range the money-flow matrix reads. It sums account_day rows (about
 * 3k a day on production; 11k a day in the scale fixture took ~1 s for 30
 * days), so a quarter keeps a cold read near a second on production.
 */
export const MAX_FLOW_DAYS = 92;

/** `range` shortened to its last `maxDays` days. */
export function clampRange(range: DayRange, maxDays: number): DayRange {
  return rangeDays(range) > maxDays ? { from: addDays(range.to, -(maxDays - 1)), to: range.to } : range;
}

/** (current − previous) / previous, or null when there is nothing to compare against. */
export function change(current: number, previous: number | null): number | null {
  if (previous === null || previous === 0) return null;
  return (current - previous) / previous;
}

// ── overview ──────────────────────────────────────────────────────────────

export interface ActivityTotals {
  /** Bridge-adjusted transactions and volume (an adjacent Vault bridge pair counts once). */
  txnCount: number;
  volume: string;
  /** Mean of the daily distinct active players over the covered days; null with no covered days. */
  avgActivePlayers: number | null;
  newPlayers: number;
  days: number;
}

export interface OverviewActivity {
  coverage: RangeCoverage;
  /** Covered days of the range, oldest first. */
  days: EconomyDay[];
  totals: ActivityTotals;
  /** Totals for the previous period of the same length, or null if it is not fully covered. */
  previous: ActivityTotals | null;
}

function totalsOf(days: EconomyDay[]): ActivityTotals {
  let volume = 0n;
  let txnCount = 0;
  let active = 0;
  let newPlayers = 0;
  for (const d of days) {
    volume += parseMoney(d.volume);
    txnCount += d.txnCount;
    active += d.activePlayers;
    newPlayers += d.newPlayers;
  }
  return { txnCount, volume: toDbString(volume), avgActivePlayers: days.length ? active / days.length : null, newPlayers, days: days.length };
}

/** Daily activity for `range` and totals for it and the period before, in one rollup read. */
export async function overviewActivity(range: DayRange, previous: DayRange): Promise<OverviewActivity> {
  const map = await coverageNow();
  return memo(`econ:activity:v1:${key(range)}:${version(map, 'economy_day')}`, ROLLUP_TTL_MS, async () => {
    const { data } = await economyDays({ from: previous.from, to: range.to });
    const current = data.filter((d) => d.day >= range.from);
    const before = data.filter((d) => d.day < range.from);
    return {
      coverage: jobCoverage(map, 'economy_day', range),
      days: current,
      totals: totalsOf(current),
      previous: jobCoverage(map, 'economy_day', previous).covered ? totalsOf(before) : null,
    };
  });
}

/** Player-held money right now: the sum of active PERSONAL balances. */
export const personalSupply = () => memo('econ:personal-supply:v1', 60_000, getPersonalSupply);

export interface HeatmapData {
  coverage: RangeCoverage;
  /** In the shape components/charts/Heatmap takes: dow0 0 = Monday, hr UTC. */
  cells: { dow0: number; hr: number; txn_count: number }[];
}

export async function overviewHeatmap(range: DayRange): Promise<HeatmapData> {
  const map = await coverageNow();
  return memo(`econ:heatmap:v1:${key(range)}:${version(map, 'hour_heatmap')}`, ROLLUP_TTL_MS, async () => {
    const { data, coverage } = await heatmap(range);
    return { coverage, cells: data.map((c) => ({ dow0: c.weekday, hr: c.hour, txn_count: c.txnCount })) };
  });
}

export interface SourceVolume {
  /** COALESCE(plugin_system, '') as recorded by the plugin that wrote the transaction. */
  pluginKey: string;
  /** Money credited to accounts by this source's transactions (DECIMAL string). */
  volume: string;
}

/** Credits per plugin over the covered days, largest first; the tail beyond `top` is summed as "other". */
export function summarizeSources(rows: { pluginKey: string; inflow: string }[], top: number): { sources: SourceVolume[]; other: string; total: string } {
  const byPlugin = new Map<string, bigint>();
  for (const row of rows) byPlugin.set(row.pluginKey, (byPlugin.get(row.pluginKey) ?? 0n) + parseMoney(row.inflow));
  const sorted = [...byPlugin].filter(([, v]) => v > 0n)
    .sort((a, b) => (b[1] > a[1] ? 1 : b[1] < a[1] ? -1 : a[0].localeCompare(b[0])));
  const total = sorted.reduce((sum, [, v]) => sum + v, 0n);
  const shown = sorted.slice(0, top);
  const other = sorted.slice(top).reduce((sum, [, v]) => sum + v, 0n);
  return { sources: shown.map(([pluginKey, v]) => ({ pluginKey, volume: toDbString(v) })), other: toDbString(other), total: toDbString(total) };
}

export interface SourceData { coverage: RangeCoverage; sources: SourceVolume[]; other: string; total: string }

/** Which plugins move the most money (raw ledger credits, so bridge legs count twice). */
export async function overviewSources(range: DayRange, top = 8): Promise<SourceData> {
  const map = await coverageNow();
  return memo(`econ:sources:v1:${key(range)}:${top}:${version(map, 'type_day')}`, ROLLUP_TTL_MS, async () => {
    const { data, coverage } = await typeDays(range);
    return { coverage, ...summarizeSources(data, top) };
  });
}

// ── wealth ────────────────────────────────────────────────────────────────

/**
 * Current balances: supply by account type (archived accounts excluded, minting
 * accounts included with their negative balances), the top accounts, the
 * personal balance distribution and its concentration. Five cheap reads of
 * account_balances_mat, shared for five minutes.
 */
export const wealthNow = () => memo('econ:wealth-now:v2', 5 * 60_000, async () => {
  const [byType, topAccounts, distribution, balances] = await Promise.all([
    getBalanceByType(), getTopAccounts(10), getBalanceDistribution(), getPersonalBalances(),
  ]);
  return {
    byType,
    topAccounts,
    distribution,
    holders: balances.length,
    gini: balances.length ? gini(balances) : null,
    top1: balances.length ? topShare(balances, 0.01) : null,
    top10: balances.length ? topShare(balances, 0.1) : null,
    takenAt: new Date().toISOString(),
  };
});

export async function wealthHistory(range: DayRange): Promise<{ coverage: RangeCoverage; snapshots: WealthSnapshot[] }> {
  const map = await coverageNow();
  return memo(`econ:wealth-history:v1:${key(range)}:${version(map, 'wealth')}`, ROLLUP_TTL_MS, async () => {
    const { data, coverage } = await wealthSnapshots(range);
    return { coverage, snapshots: data };
  });
}

// ── money flow ────────────────────────────────────────────────────────────

export const ACCOUNT_TYPES = ['PERSONAL', 'BUSINESS', 'GOVERNMENT', 'SYSTEM'] as const;
export type AccountTypeKey = (typeof ACCOUNT_TYPES)[number];

export interface TypeFlow { from: AccountTypeKey; to: AccountTypeKey; amount: string }

export interface FlowMatrix {
  coverage: RangeCoverage;
  /** Every non-zero (payer type, payee type) pair, largest first. */
  flows: TypeFlow[];
  /** Total paid between different parties over the covered days. */
  total: string;
}

const isType = (t: string): t is AccountTypeKey => (ACCOUNT_TYPES as readonly string[]).includes(t);

export async function moneyFlowMatrix(range: DayRange): Promise<FlowMatrix> {
  const map = await coverageNow();
  return memo(`econ:flow-matrix:v1:${key(range)}:${version(map, 'account_day')}`, ROLLUP_TTL_MS, async () => {
    const coverage = jobCoverage(map, 'account_day', range);
    const part = coveredPart(coverage, range);
    const rows = part ? await selectTypeFlows(part.from, part.to) : [];
    const flows = rows.filter((r) => isType(r.from_type) && isType(r.to_type))
      .map((r) => ({ from: r.from_type as AccountTypeKey, to: r.to_type as AccountTypeKey, amount: r.amount }));
    return { coverage, flows, total: toDbString(flows.reduce((sum, f) => sum + parseMoney(f.amount), 0n)) };
  }, { lockMs: 30_000 });
}

export interface TypeNet { type: AccountTypeKey; inflow: string; outflow: string; net: string }

export interface SupplyChange {
  coverage: RangeCoverage;
  /** Gross credits, debits and net change per account type over the covered days. */
  byType: TypeNet[];
  /** Net change per type per day (chart coordinates; exact figures are in byType). */
  daily: ({ date: string } & Record<AccountTypeKey, string>)[];
  /** Net change per source (plugin) and type, largest gross first. */
  bySource: ({ pluginKey: string; gross: string } & Record<AccountTypeKey, string>)[];
}

/** Gross and net flows per account type from type_day; the nets of all four types sum to zero. */
export function summarizeSupplyChange(rows: { day: string; accountType: string; pluginKey: string; inflow: string; outflow: string }[], days: DayRange | null) {
  const zero = () => Object.fromEntries(ACCOUNT_TYPES.map((t) => [t, 0n])) as Record<AccountTypeKey, bigint>;
  const inflow = zero();
  const outflow = zero();
  const daily = new Map<string, Record<AccountTypeKey, bigint>>();
  for (let day = days?.from; days && day && day <= days.to; day = addDays(day, 1)) daily.set(day, zero());
  const sources = new Map<string, { net: Record<AccountTypeKey, bigint>; gross: bigint }>();
  for (const row of rows) {
    if (!isType(row.accountType)) continue;
    const i = parseMoney(row.inflow);
    const o = parseMoney(row.outflow);
    inflow[row.accountType] += i;
    outflow[row.accountType] += o;
    const day = daily.get(row.day);
    if (day) day[row.accountType] += i - o;
    const source = sources.get(row.pluginKey) ?? { net: zero(), gross: 0n };
    source.net[row.accountType] += i - o;
    source.gross += i;
    sources.set(row.pluginKey, source);
  }
  const str = (r: Record<AccountTypeKey, bigint>) => Object.fromEntries(ACCOUNT_TYPES.map((t) => [t, toDbString(r[t])])) as Record<AccountTypeKey, string>;
  return {
    byType: ACCOUNT_TYPES.map((type) => ({ type, inflow: toDbString(inflow[type]), outflow: toDbString(outflow[type]), net: toDbString(inflow[type] - outflow[type]) })),
    daily: [...daily].map(([date, nets]) => ({ date, ...str(nets) })),
    bySource: [...sources].filter(([, s]) => s.gross > 0n)
      .sort((a, b) => (b[1].gross > a[1].gross ? 1 : b[1].gross < a[1].gross ? -1 : a[0].localeCompare(b[0])))
      .map(([pluginKey, s]) => ({ pluginKey, gross: toDbString(s.gross), ...str(s.net) })),
  };
}

export async function supplyChange(range: DayRange): Promise<SupplyChange> {
  const map = await coverageNow();
  return memo(`econ:supply-change:v1:${key(range)}:${version(map, 'type_day')}`, ROLLUP_TTL_MS, async () => {
    const { data, coverage } = await typeDays(range);
    return { coverage, ...summarizeSupplyChange(data, coveredPart(coverage, range)) };
  });
}
