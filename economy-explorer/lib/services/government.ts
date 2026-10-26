import 'server-only';
import { memo } from '@/lib/cache';
import { PLUGIN_CATEGORIES } from '@/lib/categories';
import { parseMoney, toDbString, type Cents } from '@/lib/money';
import { addDays, rangeDays, type DayRange } from '@/lib/range';
import { eachDay, monthEnd, monthStart, utcDay } from '@/lib/rollup/days';
import type { RollupJob } from '@/lib/rollup/steps';
import { balanceSeries, rollupCoverage, type BalancePoint, type CoverageMap, type RangeCoverage } from '@/lib/services/rollup';
import {
  findPlayerNames, listFines, selectGovAccountDayNets, selectGovCounterparties, selectGovFlowGroups, selectGovLedger,
  selectGovMonthlyFlows, selectGovPluginOutflowDays, selectGovViews, selectLargestMovements, selectMoversByOfficial,
  selectSalaryBuckets, selectSalaryRecipients, selectTopFinePayers, selectTopTaxPayers, summariseFines,
  summariseFinesForPeriod, topFineIssuers, txnIdAt,
  type CounterpartyRow, type FineRow, type FineSummaryRow, type IssuerRow, type MovementRow, type OfficialRow, type PayerRow,
  type SalaryRecipientRow, type TxnWindow, type ViewRow,
} from '@/lib/sql/government';
import { stateCursor, type TableState } from '@/lib/tables/core';
import type { FinesTableState } from '@/lib/tables/fines';
import type { GovLedgerState, GovViewsState } from '@/lib/tables/gov';
import { analysePayroll, classifyFlows, type FlowBreakdown, type PayrollAnalysis } from './governmentModel';
import { unitKeyOf, type GovScope } from './governmentScope';

// Data for the /gov pages. Every function takes the resolved scope and a
// selection of account ids that came from it (lib/services/governmentScope),
// so nothing here can widen what a viewer sees.
//
// Aggregates (totals, categories, monthly bars, balances, counterparties) come
// from the rollups and say "Preparing history" until they are covered; they
// never fall back to the ledger. Top-N lists read the ledger inside a window
// of at most RAW_MAX_DAYS, cached briefly because a busy treasury's window is
// large (§11: no request-path read beyond 31 days of raw ledger).

export const RAW_MAX_DAYS = 31;
const RAW_CACHE_MS = 5 * 60_000;

export interface Coverage {
  covered: boolean;
  coveredFrom: string | null;
  updatedAt: string | null;
}

export function jobCoverage(map: CoverageMap, job: RollupJob, range: DayRange): Coverage {
  const record = map[job];
  if (!record) return { covered: false, coveredFrom: null, updatedAt: null };
  const complete = record.history_complete === 1;
  return {
    covered: (complete || range.from >= record.from_day) && range.to <= record.to_day,
    coveredFrom: complete ? null : record.from_day,
    updatedAt: record.updated_at,
  };
}

function fromRange(c: RangeCoverage): Coverage {
  return { covered: c.covered, coveredFrom: c.historyComplete ? null : c.coveredFrom, updatedAt: c.updatedAt };
}

/** The part of `range` a raw ledger read may cover: its last RAW_MAX_DAYS days. */
export function rawRange(range: DayRange): DayRange & { capped: boolean } {
  if (rangeDays(range) <= RAW_MAX_DAYS) return { ...range, capped: false };
  return { from: addDays(range.to, -(RAW_MAX_DAYS - 1)), to: range.to, capped: true };
}

const ts = (day: string) => `${day} 00:00:00`;
/** [from 00:00, to+1 00:00) as SQL timestamps. */
export function bounds(range: DayRange): { fromTs: string; toTs: string } {
  return { fromTs: ts(range.from), toTs: ts(addDays(range.to, 1)) };
}

/** The txn_id window of a day range, or null when no transaction settled in it. */
export async function txnWindow(range: DayRange): Promise<TxnWindow | null> {
  const { fromTs, toTs } = bounds(range);
  const [lo, end] = await Promise.all([txnIdAt(fromTs), txnIdAt(toTs)]);
  if (lo === null) return null;
  if (end !== null && end <= lo) return null;
  return { lo, hi: end === null ? null : end - 1 };
}

const idsKey = (accountIds: readonly number[]) => [...new Set(accountIds)].sort((a, b) => a - b).join(',');

function cachedRaw<T>(prefix: string, accountIds: readonly number[], range: DayRange, extra: string, fn: () => Promise<T>, windowMs = RAW_CACHE_MS): Promise<T> {
  return memo(`${prefix}:${idsKey(accountIds)}:${range.from}:${range.to}:${extra}`, windowMs, fn);
}

/**
 * Top tax payers is the heaviest read: on the main treasury a 31-day window
 * holds a few hundred thousand sales-tax transactions. It changes slowly, so
 * it is cached longer than the other top-N lists.
 */
const TAX_PAYERS_CACHE_MS = 15 * 60_000;

function balanceOf(scope: GovScope, accountIds: readonly number[]): string {
  const want = new Set(accountIds);
  let total = 0n;
  for (const a of scope.accounts) if (want.has(a.account_id)) total += parseMoney(a.balance);
  return toDbString(total);
}

// ── shared flows ──────────────────────────────────────────────────────────

export interface Flows {
  coverage: Coverage;
  breakdown: FlowBreakdown;
  /** Every inflow minus every outflow, as the ledger has it. */
  net: string;
  /** Plugin keys (as stored) the selection paid wages with. */
  wagePlugins: string[];
  /** Tax kinds the selection received. */
  taxKinds: string[];
}

/** Revenue, spending and government transfers over the range, from explorer_rollup_account_day. */
export async function govFlows(scope: GovScope, accountIds: readonly number[], range: DayRange, coverageMap?: CoverageMap): Promise<Flows> {
  const [map, groups] = await Promise.all([coverageMap ?? rollupCoverage(), selectGovFlowGroups(accountIds, range.from, range.to)]);
  let net = 0n;
  const wage = new Set<string>();
  const kinds = new Set<string>();
  for (const g of groups) {
    net += parseMoney(g.inflow) - parseMoney(g.outflow);
    const key = g.plugin_key.trim().toLowerCase();
    if (PLUGIN_CATEGORIES[key] === 'wages' && parseMoney(g.outflow) > 0n) wage.add(g.plugin_key);
    if (g.tax_kind && g.tax_kind !== 'fine' && parseMoney(g.inflow) > 0n) kinds.add(g.tax_kind);
  }
  return {
    coverage: jobCoverage(map, 'account_day', range),
    breakdown: classifyFlows(groups, unitKeyOf(scope)),
    net: toDbString(net),
    wagePlugins: [...wage].sort(),
    taxKinds: [...kinds].sort(),
  };
}

// ── overview ──────────────────────────────────────────────────────────────

export interface MonthBar { month: string; revenue: string; spending: string; government: string }

export interface UnitCard {
  key: string;
  name: string;
  href: string;
  balance: string;
  net: string;
  readable: number;
  total: number;
  partial: boolean;
}

export interface Overview {
  treasury: string;
  accountCount: number;
  net: string;
  flows: Flows;
  months: MonthBar[];
  balance: { data: BalancePoint[]; coverage: Coverage };
  movements: { rows: MovementRow[]; range: DayRange & { capped: boolean } };
  units: UnitCard[];
}

function monthBars(rows: Awaited<ReturnType<typeof selectGovMonthlyFlows>>): MonthBar[] {
  const months = new Map<string, { revenue: Cents; spending: Cents; government: Cents }>();
  for (const r of rows) {
    const m = months.get(r.month) ?? { revenue: 0n, spending: 0n, government: 0n };
    if (r.government) m.government += parseMoney(r.inflow) - parseMoney(r.outflow);
    else { m.revenue += parseMoney(r.inflow); m.spending += parseMoney(r.outflow); }
    months.set(r.month, m);
  }
  return [...months].sort(([a], [b]) => a.localeCompare(b)).map(([month, m]) => ({
    month, revenue: toDbString(m.revenue), spending: toDbString(m.spending), government: toDbString(m.government),
  }));
}

/**
 * The largest postings of the selection in the range (raw, capped window).
 * A transfer between two selected accounts appears once, on its debit leg.
 */
export async function largestMovements(accountIds: readonly number[], range: DayRange, limit = 10): Promise<{ rows: MovementRow[]; range: DayRange & { capped: boolean } }> {
  const raw = rawRange(range);
  const rows = await cachedRaw('gov-movements', accountIds, raw, String(limit), async () => {
    const window = await txnWindow(raw);
    return window ? selectLargestMovements(accountIds, window, limit * 2) : [];
  });
  const set = new Set(accountIds);
  const deduped = rows.filter((r) => !(r.counterparty_id !== null && set.has(r.counterparty_id) && parseMoney(r.amount) > 0n));
  return { rows: deduped.slice(0, limit), range: raw };
}

function unitCards(scope: GovScope, selectionKey: string, nets: Map<number, Cents>, query: string): UnitCard[] {
  const parent = scope.units.find((u) => u.slug === selectionKey)?.unitId ?? null;
  const shown = selectionKey === 'all'
    ? scope.units.filter((u) => u.depth === 0)
    : scope.units.filter((u) => u.parentUnitId === parent && parent !== null);
  const netOf = (ids: readonly number[]) => toDbString(ids.reduce((s, id) => s + (nets.get(id) ?? 0n), 0n));
  const cards = shown.map((u) => ({
    key: u.slug, name: u.name, href: `/gov?unit=${encodeURIComponent(u.slug)}${query}`, balance: balanceOf(scope, u.readableIds),
    net: netOf(u.readableIds), readable: u.readableIds.length, total: u.totalAccounts, partial: u.partial,
  }));
  if (selectionKey === 'all' && scope.unassigned.length > 0) {
    cards.push({
      key: 'unassigned', name: 'Unassigned', href: `/gov?unit=unassigned${query}`, balance: balanceOf(scope, scope.unassigned),
      net: netOf(scope.unassigned), readable: scope.unassigned.length, total: scope.unassigned.length, partial: false,
    });
  }
  return cards;
}

export async function getOverview(scope: GovScope, selection: { key: string; ids: number[] }, range: DayRange, query = ''): Promise<Overview> {
  const map = await rollupCoverage();
  const monthsRange = { from: monthStart(range.from), to: range.to };
  const [flows, monthly, balance, movements, nets] = await Promise.all([
    govFlows(scope, selection.ids, range, map),
    selectGovMonthlyFlows(selection.ids, monthsRange.from, monthsRange.to),
    balanceSeries(selection.ids, range),
    largestMovements(selection.ids, range),
    selectGovAccountDayNets(scope.ids, range.from, range.to),
  ]);
  const netByAccount = new Map<number, Cents>();
  for (const r of nets) netByAccount.set(r.account_id, (netByAccount.get(r.account_id) ?? 0n) + parseMoney(r.net));
  return {
    treasury: balanceOf(scope, selection.ids),
    accountCount: selection.ids.length,
    net: flows.net,
    flows,
    months: jobCoverage(map, 'account_day', monthsRange).covered ? monthBars(monthly) : [],
    balance: { data: balance.data, coverage: fromRange(balance.coverage) },
    movements,
    units: unitCards(scope, selection.key, netByAccount, query),
  };
}

// ── accounts ──────────────────────────────────────────────────────────────

export interface GovAccountView {
  accountId: number;
  displayName: string | null;
  ownerName: string | null;
  ownerUuid: string | null;
  balance: string;
  net30: string;
  /** Closing balance per day over the last 30 days, as numbers for the sparkline only. */
  spark: number[];
  unit: { slug: string; name: string } | null;
}

/** Every selected account with balance, 30-day net and a balance sparkline (rollups). */
export async function getGovAccounts(scope: GovScope, accountIds: readonly number[]): Promise<{ rows: GovAccountView[]; coverage: Coverage }> {
  const today = utcDay();
  const range = { from: addDays(today, -29), to: today };
  const [map, nets] = await Promise.all([rollupCoverage(), selectGovAccountDayNets(accountIds, range.from, range.to)]);
  const coverage = jobCoverage(map, 'account_day', range);
  const byAccount = new Map<number, Map<string, Cents>>();
  for (const r of nets) {
    const m = byAccount.get(r.account_id) ?? new Map<string, Cents>();
    m.set(r.day, parseMoney(r.net));
    byAccount.set(r.account_id, m);
  }
  const days = eachDay(range);
  const want = new Set(accountIds);
  const rows = scope.accounts.filter((a) => want.has(a.account_id)).map((a) => {
    const m = byAccount.get(a.account_id) ?? new Map<string, Cents>();
    const net = [...m.values()].reduce((s, v) => s + v, 0n);
    // Walk back from today's balance: closing(d) = closing(d+1) − net(d+1).
    let bal = parseMoney(a.balance);
    const spark: number[] = [];
    for (let i = days.length - 1; i >= 0; i--) {
      spark.unshift(Number(bal) / 100);
      bal -= m.get(days[i] as string) ?? 0n;
    }
    const unit = scope.unitOf.get(a.account_id);
    return {
      accountId: a.account_id, displayName: a.display_name, ownerName: a.owner_name, ownerUuid: a.owner_uuid, balance: a.balance,
      net30: toDbString(net), spark: coverage.covered ? spark : [], unit: unit ? { slug: unit.slug, name: unit.name } : null,
    };
  });
  return { rows, coverage };
}

// ── revenue ───────────────────────────────────────────────────────────────

export interface Revenue {
  flows: Flows;
  taxPayers: PayerRow[];
  finePayers: (PayerRow & { player_uuid: string | null; player_name: string | null })[];
  topRange: DayRange & { capped: boolean };
}

export async function getRevenue(scope: GovScope, accountIds: readonly number[], range: DayRange): Promise<Revenue> {
  const flows = await govFlows(scope, accountIds, range);
  const raw = rawRange(range);
  const { fromTs, toTs } = bounds(raw);
  const [taxPayers, finePayers] = await Promise.all([
    flows.taxKinds.length
      ? cachedRaw('gov-tax-payers', accountIds, raw, flows.taxKinds.join(','), () => selectTopTaxPayers(accountIds, flows.taxKinds, fromTs, toTs, 15), TAX_PAYERS_CACHE_MS)
      : Promise.resolve([]),
    selectTopFinePayers(accountIds, ts(range.from), bounds(range).toTs, 15),
  ]);
  return { flows, taxPayers, finePayers, topRange: raw };
}

// ── spending ──────────────────────────────────────────────────────────────

export interface Spending {
  flows: Flows;
  recipients: { rows: CounterpartyRow[]; coverage: Coverage; months: DayRange };
  officials: { rows: OfficialRow[]; range: DayRange & { capped: boolean } };
}

export async function getSpending(scope: GovScope, accountIds: readonly number[], range: DayRange): Promise<Spending> {
  const map = await rollupCoverage();
  const months = { from: monthStart(range.from), to: monthEnd(range.to) };
  const raw = rawRange(range);
  const [flows, recipients, officials] = await Promise.all([
    govFlows(scope, accountIds, range, map),
    selectGovCounterparties(accountIds, monthStart(range.from), monthStart(range.to), 'out', 25),
    officialsFor(accountIds, raw, 'out'),
  ]);
  return {
    flows,
    recipients: { rows: recipients, coverage: jobCoverage(map, 'pair_month', { from: months.from, to: range.to }), months },
    officials: { rows: officials, range: raw },
  };
}

function officialsFor(accountIds: readonly number[], raw: DayRange, direction: 'in' | 'out'): Promise<OfficialRow[]> {
  return cachedRaw(`gov-officials-${direction}`, accountIds, raw, '', async () => {
    const window = await txnWindow(raw);
    return window ? selectMoversByOfficial(accountIds, window, direction, 25) : [];
  });
}

// ── payroll ───────────────────────────────────────────────────────────────

export interface Payroll {
  coverage: Coverage;
  wagePlugins: string[];
  /** Daily wages paid over the whole range (rollups). */
  days: { day: string; total: string; payments: number }[];
  total: string;
  payments: number;
  analysis: PayrollAnalysis;
  recipients: SalaryRecipientRow[];
  rawRange: DayRange & { capped: boolean };
}

export async function getPayroll(scope: GovScope, accountIds: readonly number[], range: DayRange): Promise<Payroll> {
  const flows = await govFlows(scope, accountIds, range);
  const plugins = flows.wagePlugins;
  const raw = rawRange(range);
  const { fromTs, toTs } = bounds(raw);
  const [days, buckets, recipients] = await Promise.all([
    selectGovPluginOutflowDays(accountIds, plugins, range.from, range.to),
    plugins.length ? cachedRaw('gov-payroll-runs', accountIds, raw, plugins.join(','), () => selectSalaryBuckets(accountIds, plugins, fromTs, toTs)) : Promise.resolve([]),
    plugins.length ? cachedRaw('gov-payroll-recipients', accountIds, raw, plugins.join(','), () => selectSalaryRecipients(accountIds, plugins, fromTs, toTs, 50)) : Promise.resolve([]),
  ]);
  const total = days.reduce((s, d) => s + parseMoney(d.outflow), 0n);
  return {
    coverage: flows.coverage,
    wagePlugins: plugins,
    days: days.map((d) => ({ day: d.day, total: d.outflow, payments: d.txn_count })),
    total: toDbString(total),
    payments: days.reduce((s, d) => s + d.txn_count, 0),
    analysis: analysePayroll(buckets),
    recipients,
    rawRange: raw,
  };
}

// ── fines ─────────────────────────────────────────────────────────────────

export interface Fines {
  rows: FineRow[];
  summary: FineSummaryRow;
  issuers: IssuerRow[];
  entityLabels: Record<string, string>;
}

export async function getFines(scope: GovScope, accountIds: readonly number[], range: DayRange, state: FinesTableState): Promise<Fines> {
  const q = { accountIds, ...bounds(range), state };
  const offset = (state.page - 1) * state.limit;
  const players = state.filters.filter((f) => f.field === 'issuer' || f.field === 'player').flatMap((f) => f.values);
  const [rows, summary, issuers, names] = await Promise.all([
    listFines(q, state.limit, offset), summariseFines(q), topFineIssuers(q, 5), findPlayerNames(players),
  ]);
  return { rows, summary, issuers, entityLabels: entityLabels(scope, state, names) };
}

/** Chip text for entity filters: player names and in-scope account names. Out-of-scope ids get no label. */
export function entityLabels(scope: GovScope, state: TableState, names: Map<string, string>): Record<string, string> {
  const out: Record<string, string> = {};
  const accountName = new Map(scope.accounts.map((a) => [String(a.account_id), a.display_name ?? `#${a.account_id}`]));
  for (const f of state.filters) {
    for (const v of f.values) {
      const player = names.get(v);
      if (player) out[`${f.field}:${v}`] = player;
      const account = accountName.get(v);
      if (account && f.field === 'account') out[`${f.field}:${v}`] = account;
    }
  }
  return out;
}

/** Every fine the filters select, for the CSV export. Capped so one export can't drain the pool. */
export async function exportFines(accountIds: readonly number[], range: DayRange, state: FinesTableState, cap = 10_000): Promise<FineRow[]> {
  return listFines({ accountIds, ...bounds(range), state }, cap, 0);
}

// ── reports ───────────────────────────────────────────────────────────────

export interface MonthlyReport {
  month: string;
  range: DayRange;
  coverage: Coverage;
  opening: string | null;
  closing: string | null;
  flows: Flows;
  movements: MovementRow[];
  fines: Awaited<ReturnType<typeof summariseFinesForPeriod>>;
}

/** Last N calendar months, newest first ('YYYY-MM-01'). */
export function reportMonths(n = 12, today = utcDay()): string[] {
  const out: string[] = [];
  let m = monthStart(today);
  for (let i = 0; i < n; i++) {
    out.push(m);
    m = monthStart(addDays(m, -1));
  }
  return out;
}

export async function getMonthlyReport(scope: GovScope, accountIds: readonly number[], month: string): Promise<MonthlyReport> {
  const today = utcDay();
  const end = monthEnd(month);
  const range = { from: month, to: end > today ? today : end };
  const opening = { from: addDays(month, -1), to: addDays(month, -1) };
  const [flows, closingSeries, openingSeries, movements, fines] = await Promise.all([
    govFlows(scope, accountIds, range),
    balanceSeries(accountIds, { from: range.to, to: range.to }),
    balanceSeries(accountIds, opening),
    largestMovements(accountIds, range, 10),
    summariseFinesForPeriod(accountIds, bounds(range).fromTs, bounds(range).toTs),
  ]);
  return {
    month,
    range,
    coverage: flows.coverage,
    opening: openingSeries.coverage.covered ? openingSeries.data[0]?.balance ?? null : null,
    closing: closingSeries.coverage.covered ? closingSeries.data[0]?.balance ?? null : null,
    flows,
    movements: movements.rows,
    fines,
  };
}

/** The report as CSV rows (header first). Exact decimals, UTC. */
export function reportCsv(report: MonthlyReport, unitLabel: string): string[][] {
  const rows: string[][] = [['Section', 'Item', 'Amount']];
  rows.push(['Report', 'Unit', unitLabel], ['Report', 'Month', report.month.slice(0, 7)]);
  rows.push(['Balance', 'Opening', report.opening ?? ''], ['Balance', 'Closing', report.closing ?? '']);
  for (const l of report.flows.breakdown.revenue) rows.push(['Revenue', l.label, l.amount]);
  rows.push(['Revenue', 'Total', report.flows.breakdown.totalRevenue]);
  for (const l of report.flows.breakdown.spending) rows.push(['Spending', l.label, l.amount]);
  rows.push(['Spending', 'Total', report.flows.breakdown.totalSpending]);
  rows.push(['Government transfers', 'In', report.flows.breakdown.governmentIn], ['Government transfers', 'Out', report.flows.breakdown.governmentOut]);
  rows.push(['Net', 'All flows', report.flows.net]);
  for (const m of report.movements) rows.push(['Top movement', `#${m.txn_id} ${m.settled_at.toISOString()} ${m.message}`, m.amount]);
  rows.push(['Fines', 'Issued', String(report.fines.count)], ['Fines', 'Revoked', String(report.fines.revoked)], ['Fines', 'Collected (not revoked)', report.fines.total]);
  return rows;
}

// ── audit ─────────────────────────────────────────────────────────────────

export interface LedgerAudit {
  rows: MovementRow[];
  next: { at: Date; id: number } | null;
  movers: { out: OfficialRow[]; in: OfficialRow[] };
  labels: Record<string, string>;
  range: DayRange & { capped: boolean };
}

/** Ledger side of the audit: who moved money in and out, and the movements themselves. */
export async function getLedgerAudit(scope: GovScope, accountIds: readonly number[], range: DayRange, state: GovLedgerState): Promise<LedgerAudit> {
  const raw = rawRange(range);
  const window = await txnWindow(raw);
  const players = state.filters.filter((f) => f.field === 'initiator').flatMap((f) => f.values);
  const [rows, out, inn, names] = await Promise.all([
    window ? selectGovLedger(accountIds, window, state, stateCursor(state), state.limit + 1) : Promise.resolve([]),
    officialsFor(accountIds, raw, 'out'),
    officialsFor(accountIds, raw, 'in'),
    findPlayerNames(players),
  ]);
  const shown = rows.slice(0, state.limit);
  const last = shown[shown.length - 1];
  return {
    rows: shown,
    next: rows.length > state.limit && last ? { at: last.settled_at, id: last.txn_id } : null,
    movers: { out, in: inn },
    labels: entityLabels(scope, state, names),
    range: raw,
  };
}

export interface ViewsAudit { rows: ViewRow[]; next: { at: Date; id: number } | null; labels: Record<string, string> }

/** Explorer side of the audit: who viewed or exported in-scope data. */
export async function getViewsAudit(scope: GovScope, accountIds: readonly number[], range: DayRange, state: GovViewsState): Promise<ViewsAudit> {
  const players = state.filters.filter((f) => f.field === 'actor').flatMap((f) => f.values);
  const { fromTs, toTs } = bounds(range);
  const [rows, names] = await Promise.all([
    selectGovViews(accountIds, fromTs, toTs, state, stateCursor(state), state.limit + 1),
    findPlayerNames(players),
  ]);
  const shown = rows.slice(0, state.limit);
  const last = shown[shown.length - 1];
  return { rows: shown, next: rows.length > state.limit && last ? { at: last.at, id: last.audit_id } : null, labels: entityLabels(scope, state, names) };
}

/** Postings the ledger filters select, for the gov-transactions CSV export. */
export async function exportLedger(accountIds: readonly number[], range: DayRange, state: GovLedgerState, cap = 5_000): Promise<MovementRow[]> {
  const window = await txnWindow(rawRange(range));
  return window ? selectGovLedger(accountIds, window, state, null, cap) : [];
}

