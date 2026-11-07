// Pure logic behind the /gov workspace (spec §10): unit visibility, the unit
// selector, revenue/spending classification, payroll run detection and the
// compliance balance baseline. No I/O, so every rule here is unit-tested
// (test/unit/government-model.test.ts). Money is exact: DECIMAL strings in,
// bigint cents inside.

import { categorize, CATEGORY_LABELS, type AccountType, type Category } from '@/lib/categories';
import { parseMoney, toDbString, type Cents } from '@/lib/money';

// ── units ─────────────────────────────────────────────────────────────────

export interface UnitRecord {
  unit_id: number;
  slug: string;
  name: string;
  parent_unit_id: number | null;
  description: string | null;
}

export interface UnitView {
  unitId: number;
  slug: string;
  name: string;
  description: string | null;
  parentUnitId: number | null;
  /** 0 for a top-level unit. */
  depth: number;
  /** In-scope accounts of the unit and its descendants. Never empty for a visible unit. */
  readableIds: number[];
  /** Every active account of the unit and its descendants, readable or not. */
  totalAccounts: number;
  /** The viewer can read only some of the unit's accounts. */
  partial: boolean;
}

/**
 * The units a viewer may see and the accounts they may see in each (§10.2).
 * A unit is visible when at least one of its accounts (or a descendant's) is
 * in scope; its figures cover only those. A unit's own account list never
 * leaks: callers get counts of what they cannot read, not ids.
 */
export function visibleUnits(units: readonly UnitRecord[], memberships: readonly { unit_id: number; account_id: number }[], scopeIds: ReadonlySet<number>): UnitView[] {
  const byId = new Map(units.map((u) => [u.unit_id, u]));
  const children = new Map<number | null, UnitRecord[]>();
  for (const u of units) {
    // A dangling parent (deleted concurrently) or a cycle-free orphan is top level.
    const parent = u.parent_unit_id !== null && byId.has(u.parent_unit_id) ? u.parent_unit_id : null;
    const list = children.get(parent) ?? [];
    list.push(u);
    children.set(parent, list);
  }
  const own = new Map<number, number[]>();
  for (const m of memberships) {
    const list = own.get(m.unit_id) ?? [];
    list.push(m.account_id);
    own.set(m.unit_id, list);
  }
  const out: UnitView[] = [];
  const seen = new Set<number>();
  // Depth-first, children after their parent, so the selector reads as a tree.
  const walk = (parent: number | null, depth: number): number[] => {
    const all: number[] = [];
    for (const u of [...(children.get(parent) ?? [])].sort((a, b) => a.name.localeCompare(b.name) || a.unit_id - b.unit_id)) {
      if (seen.has(u.unit_id)) continue; // defensive against a parent cycle
      seen.add(u.unit_id);
      const index = out.length;
      out.push(null as unknown as UnitView); // placeholder keeps pre-order
      const ids = [...(own.get(u.unit_id) ?? []), ...walk(u.unit_id, depth + 1)];
      const readable = ids.filter((id) => scopeIds.has(id));
      out[index] = {
        unitId: u.unit_id, slug: u.slug, name: u.name, description: u.description, parentUnitId: u.parent_unit_id,
        depth, readableIds: readable, totalAccounts: ids.length, partial: readable.length < ids.length,
      };
      all.push(...ids);
    }
    return all;
  };
  walk(null, 0);
  return out.filter((u) => u.readableIds.length > 0);
}

export interface UnitFormInput { slug: string; name: string; parentUnitId: number | null; description: string | null }

const SLUG_RE = /^[a-z0-9][a-z0-9-]{0,39}$/;
/** Selector values that a slug would shadow. */
const RESERVED_SLUGS = new Set(['all', 'unassigned']);

/**
 * Checks a unit form. Slugs go in URLs (`?unit=`) and filter values, so they
 * are short lower-case words; a parent must exist and may not be the unit
 * itself or one of its descendants (no cycles).
 */
export function validateUnitInput(form: UnitFormInput, unitId: number | null, units: readonly UnitRecord[]): { ok: true; value: UnitFormInput } | { ok: false; error: string } {
  const slug = String(form.slug).trim().toLowerCase();
  const name = String(form.name).trim().replace(/\s+/g, ' ');
  const description = form.description === null ? null : String(form.description).trim().slice(0, 500) || null;
  if (!name || name.length > 128) return { ok: false, error: 'Give the unit a name of up to 128 characters.' };
  if (!SLUG_RE.test(slug)) return { ok: false, error: 'The slug is up to 40 lower-case letters, digits and hyphens, starting with a letter or digit.' };
  if (RESERVED_SLUGS.has(slug)) return { ok: false, error: `“${slug}” is reserved; choose another slug.` };
  const parent = form.parentUnitId;
  if (parent !== null) {
    if (!Number.isSafeInteger(parent) || !units.some((u) => u.unit_id === parent)) return { ok: false, error: 'The parent unit no longer exists.' };
    if (unitId !== null) {
      // Walk up from the proposed parent; reaching the unit itself means a cycle.
      const byId = new Map(units.map((u) => [u.unit_id, u]));
      const seen = new Set<number>();
      for (let at: number | null = parent; at !== null && !seen.has(at); at = byId.get(at)?.parent_unit_id ?? null) {
        if (at === unitId) return { ok: false, error: 'A unit can’t sit under itself or one of its own sub-units.' };
        seen.add(at);
      }
    }
  }
  return { ok: true, value: { slug, name, parentUnitId: parent, description } };
}

/** In-scope accounts that belong to no unit. */
export function unassignedIds(scopeIds: readonly number[], memberships: readonly { account_id: number }[]): number[] {
  const assigned = new Set(memberships.map((m) => m.account_id));
  return scopeIds.filter((id) => !assigned.has(id));
}

export const ALL_UNITS = 'all';
export const UNASSIGNED = 'unassigned';

export interface Selection {
  /** URL value: `all`, `unassigned` or a unit slug. */
  key: string;
  label: string;
  ids: number[];
  unit: UnitView | null;
}

/**
 * The accounts the page computes over for `?unit=`. Unknown or invisible
 * slugs fall back to All units: a viewer can never select their way out of
 * scope, because every option is a subset of the scope.
 */
export function selectUnit(param: string | undefined, scopeIds: readonly number[], units: readonly UnitView[], unassigned: readonly number[]): Selection {
  if (param === UNASSIGNED) return { key: UNASSIGNED, label: 'Unassigned', ids: [...unassigned], unit: null };
  const unit = param ? units.find((u) => u.slug === param) : undefined;
  if (unit) return { key: unit.slug, label: unit.name, ids: [...unit.readableIds], unit };
  return { key: ALL_UNITS, label: 'All units', ids: [...scopeIds], unit: null };
}

/** The unit that holds each account (its own unit, not an ancestor). */
export function unitOfAccount(units: readonly UnitRecord[], memberships: readonly { unit_id: number; account_id: number }[]): Map<number, UnitRecord> {
  const byId = new Map(units.map((u) => [u.unit_id, u]));
  const out = new Map<number, UnitRecord>();
  for (const m of memberships) {
    const u = byId.get(m.unit_id);
    if (u) out.set(m.account_id, u);
  }
  return out;
}

// ── /government → /gov ────────────────────────────────────────────────────

const LEGACY_DAYS: Record<string, string> = { '7': '7d', '30': '30d', '90': '90d', '365': '1y' };

/**
 * Where an old /government link goes. `view=fines` opens the fines table with
 * its search and status carried over as the new query and chip; `days` becomes
 * the matching range preset. Anything else is dropped.
 */
export function governmentRedirect(params: URLSearchParams): string {
  const out = new URLSearchParams();
  const fines = params.get('view') === 'fines';
  const days = params.get('days');
  if (days && LEGACY_DAYS[days]) out.set('range', LEGACY_DAYS[days]);
  for (const key of ['range', 'from', 'to', 'unit']) {
    const v = params.get(key);
    if (v && !out.has(key)) out.set(key, v);
  }
  if (fines) {
    const q = params.get('q')?.trim();
    if (q) out.set('q', q.slice(0, 200));
    const status = params.get('status');
    if (status === 'active' || status === 'revoked') out.append('f', `status:in:${status}`);
  }
  const qs = out.toString();
  return `${fines ? '/gov/fines' : '/gov'}${qs ? `?${qs}` : ''}`;
}

// ── revenue and spending ──────────────────────────────────────────────────

/** One explorer_rollup_account_day group over the selection. */
export interface FlowGroup {
  account_id: number;
  plugin_key: string;
  tax_kind: string;
  counterparty_type: string;
  is_internal: number;
  inflow: string;
  outflow: string;
}

export type RecipientType = 'PERSONAL' | 'BUSINESS' | 'GOVERNMENT' | 'SYSTEM' | 'NONE';

export const RECIPIENT_LABELS: Record<RecipientType, string> = {
  PERSONAL: 'Players',
  BUSINESS: 'Firms',
  GOVERNMENT: 'Other government accounts',
  SYSTEM: 'System',
  NONE: 'No counterparty',
};

export const TAX_KIND_LABELS: Record<string, string> = {
  personal: 'Personal balance tax',
  corporate: 'Corporate balance tax',
  income: 'Income tax',
  sales: 'Sales tax',
  property: 'Property tax',
};

/** A revenue or spending line: a tax kind, fines, a category, or government transfers. */
export interface FlowLine {
  key: string;
  label: string;
  /** 'taxes' lines carry their kind. */
  category: Category | 'government';
  taxKind: string | null;
  amount: string;
  /** By unit key (unit slug or `unassigned`). */
  byUnit: Record<string, string>;
}

export interface FlowBreakdown {
  /** Inflows from outside government: taxes by kind, fines, then other categories. */
  revenue: FlowLine[];
  /** Outflows to outside government, by category. */
  spending: FlowLine[];
  /** Outflows by who received them. GOVERNMENT is shown separately as transfers. */
  recipients: { type: RecipientType; label: string; amount: string }[];
  /** Money moved to and from other government accounts (any owner). Not revenue or spending. */
  governmentIn: string;
  governmentOut: string;
  totalRevenue: string;
  totalSpending: string;
  taxes: string;
  fines: string;
}

function lineKey(category: Category, taxKind: string | null): string {
  return category === 'taxes' ? `tax:${taxKind ?? ''}` : category;
}

function lineLabel(category: Category, taxKind: string | null): string {
  if (category === 'taxes') return taxKind ? TAX_KIND_LABELS[taxKind] ?? `Tax (${taxKind})` : 'Taxes';
  return CATEGORY_LABELS[category];
}

const LINE_ORDER: (Category | 'government')[] = ['taxes', 'fines', 'wages', 'shop_sales', 'shop_purchases', 'property', 'business', 'crime', 'jobs', 'transfers', 'api', 'other', 'internal', 'government'];

/**
 * Splits rollup flows into revenue, spending and government transfers.
 *
 * Revenue and spending count only money exchanged with players, firms and the
 * system. Transfers to and from any GOVERNMENT account (another department, or
 * an account of the same unit) are reported separately, so moving money
 * between treasuries never looks like income or spending. Net flow
 * (inflow − outflow over everything) is computed elsewhere from the same rows,
 * as the ledger has it.
 */
export function classifyFlows(groups: readonly FlowGroup[], unitKeyOf: (accountId: number) => string): FlowBreakdown {
  const revenue = new Map<string, { category: Category; taxKind: string | null; total: Cents; byUnit: Map<string, Cents> }>();
  const spending = new Map<string, { category: Category; taxKind: string | null; total: Cents; byUnit: Map<string, Cents> }>();
  const recipients = new Map<RecipientType, Cents>();
  let govIn = 0n;
  let govOut = 0n;
  const bump = (map: typeof revenue, category: Category, taxKind: string | null, unit: string, amount: Cents) => {
    const key = lineKey(category, taxKind);
    const entry = map.get(key) ?? { category, taxKind: category === 'taxes' ? taxKind : null, total: 0n, byUnit: new Map() };
    entry.total += amount;
    entry.byUnit.set(unit, (entry.byUnit.get(unit) ?? 0n) + amount);
    map.set(key, entry);
  };
  for (const g of groups) {
    const inflow = parseMoney(g.inflow);
    const outflow = parseMoney(g.outflow);
    const cpType = (g.counterparty_type || null) as AccountType | null;
    const unit = unitKeyOf(g.account_id);
    if (cpType === 'GOVERNMENT' || g.is_internal === 1) {
      govIn += inflow;
      govOut += outflow;
      if (outflow !== 0n) recipients.set('GOVERNMENT', (recipients.get('GOVERNMENT') ?? 0n) + outflow);
      continue;
    }
    const taxKind = g.tax_kind ? g.tax_kind.toLowerCase() : null;
    const base = { pluginKey: g.plugin_key, taxKind, counterpartyType: cpType, internal: false };
    if (inflow !== 0n) bump(revenue, categorize({ ...base, direction: 'in' }), taxKind, unit, inflow);
    if (outflow !== 0n) {
      bump(spending, categorize({ ...base, direction: 'out' }), taxKind, unit, outflow);
      const type: RecipientType = cpType ?? 'NONE';
      recipients.set(type, (recipients.get(type) ?? 0n) + outflow);
    }
  }
  const lines = (map: typeof revenue): FlowLine[] => [...map.values()]
    .sort((a, b) => LINE_ORDER.indexOf(a.category) - LINE_ORDER.indexOf(b.category) || (a.total > b.total ? -1 : a.total < b.total ? 1 : 0))
    .map((e) => ({
      key: lineKey(e.category, e.taxKind), label: lineLabel(e.category, e.taxKind), category: e.category, taxKind: e.taxKind,
      amount: toDbString(e.total), byUnit: Object.fromEntries([...e.byUnit].map(([k, v]) => [k, toDbString(v)])),
    }));
  const rev = lines(revenue);
  const spend = lines(spending);
  const sumOf = (ls: FlowLine[], pick: (l: FlowLine) => boolean) => toDbString(ls.filter(pick).reduce((s, l) => s + parseMoney(l.amount), 0n));
  const ORDER: RecipientType[] = ['PERSONAL', 'BUSINESS', 'GOVERNMENT', 'SYSTEM', 'NONE'];
  return {
    revenue: rev,
    spending: spend,
    recipients: ORDER.filter((t) => recipients.has(t)).map((t) => ({ type: t, label: RECIPIENT_LABELS[t], amount: toDbString(recipients.get(t) ?? 0n) })),
    governmentIn: toDbString(govIn),
    governmentOut: toDbString(govOut),
    totalRevenue: sumOf(rev, () => true),
    totalSpending: sumOf(spend, () => true),
    taxes: sumOf(rev, (l) => l.category === 'taxes'),
    fines: sumOf(rev, (l) => l.category === 'fines'),
  };
}

// ── payroll ───────────────────────────────────────────────────────────────

/** Salary payouts settled in one minute (the SQL groups by minute). */
export interface PayoutBucket {
  /** ISO minute, UTC: '2026-10-25T07:14:00.000Z'. */
  at: string;
  payments: number;
  recipients: number;
  total: string;
}

export interface PayrollRun {
  start: string;
  end: string;
  payments: number;
  /** Distinct recipients summed over the run's minutes (a recipient paid in two minutes counts twice). */
  recipients: number;
  total: string;
  /** Minutes since the previous run started; null for the first. */
  gapMinutes: number | null;
  status: 'ok' | 'late' | 'missed' | 'duplicate' | 'resumed';
  /** Runs the gap before this one is long enough to have skipped. */
  missedBefore: number;
}

export interface PayrollAnalysis {
  runs: PayrollRun[];
  /** Median minutes between runs, or null with fewer than three runs. */
  cadenceMinutes: number | null;
  missed: number;
  duplicates: number;
  /** Gaps too long to be missed runs: payroll was paused (server down, nobody to pay). */
  pauses: number;
}

const MINUTE_MS = 60_000;
/** A gap longer than this many cadences is a pause, not a run of missed runs. */
export const PAUSE_CADENCES = 12;

/**
 * Groups salary payouts into runs and checks the cadence. Payouts in minutes
 * closer together than `joinMinutes` belong to one run (a run that straddles a
 * minute boundary). The cadence is the median gap between run starts; a gap
 * over 1.5× the cadence means runs were missed (⌊gap / cadence + 0.5⌋ − 1 of
 * them), up to PAUSE_CADENCES: beyond that payroll was paused, and the run
 * that ends the gap is "resumed" rather than counting hundreds of misses. A
 * run starting less than half a cadence after the previous one is a
 * duplicate. Fewer than three runs give no cadence and no verdicts.
 */
export function analysePayroll(buckets: readonly PayoutBucket[], joinMinutes = 2): PayrollAnalysis {
  const sorted = [...buckets].sort((a, b) => a.at.localeCompare(b.at));
  const grouped: { start: number; end: number; payments: number; recipients: number; total: Cents }[] = [];
  for (const b of sorted) {
    const t = Date.parse(b.at);
    if (!Number.isFinite(t)) continue;
    const last = grouped[grouped.length - 1];
    if (last && t - last.end <= joinMinutes * MINUTE_MS) {
      last.end = t;
      last.payments += b.payments;
      last.recipients += b.recipients;
      last.total += parseMoney(b.total);
    } else {
      grouped.push({ start: t, end: t, payments: b.payments, recipients: b.recipients, total: parseMoney(b.total) });
    }
  }
  const gaps = grouped.slice(1).map((r, i) => (r.start - (grouped[i] as { start: number }).start) / MINUTE_MS);
  const cadence = gaps.length >= 2 ? median(gaps) : null;
  let missed = 0;
  let duplicates = 0;
  let pauses = 0;
  const runs = grouped.map((r, i): PayrollRun => {
    const gap = i === 0 ? null : (gaps[i - 1] as number);
    let status: PayrollRun['status'] = 'ok';
    let missedBefore = 0;
    if (cadence !== null && cadence > 0 && gap !== null) {
      if (gap < cadence * 0.5) {
        status = 'duplicate';
        duplicates++;
      } else if (gap > cadence * PAUSE_CADENCES) {
        status = 'resumed';
        pauses++;
      } else if (gap > cadence * 1.5) {
        missedBefore = Math.max(1, Math.floor(gap / cadence + 0.5) - 1);
        status = 'missed';
        missed += missedBefore;
      } else if (gap > cadence * 1.2) {
        status = 'late';
      }
    }
    return {
      start: new Date(r.start).toISOString(), end: new Date(r.end).toISOString(), payments: r.payments, recipients: r.recipients,
      total: toDbString(r.total), gapMinutes: gap === null ? null : Math.round(gap * 10) / 10, status, missedBefore,
    };
  });
  return { runs, cadenceMinutes: cadence === null ? null : Math.round(cadence * 10) / 10, missed, duplicates, pauses };
}

function median(values: readonly number[]): number {
  const s = [...values].sort((a, b) => a - b);
  const mid = Math.floor(s.length / 2);
  return s.length % 2 ? (s[mid] as number) : ((s[mid - 1] as number) + (s[mid] as number)) / 2;
}

// ── compliance ────────────────────────────────────────────────────────────

/**
 * A "round" amount: a whole multiple of `unit` (default 1,000) and at least
 * one unit. Round transfers are a classic structuring signal; this only flags
 * them for a human to look at.
 */
export function isRoundAmount(amount: string, unit: Cents = 100_000n): boolean {
  const cents = parseMoney(amount);
  const a = cents < 0n ? -cents : cents;
  return a >= unit && a % unit === 0n;
}

export interface DayNet { day: string; net: string }

export interface BalanceAnomaly {
  day: string;
  net: string;
  /** The baseline's mean daily net and standard deviation, exact-ish strings for display. */
  mean: string;
  stddev: string;
  /** How many standard deviations from the mean. */
  score: number;
}

/**
 * Days whose net change stands out against the baseline: |net − mean| greater
 * than `threshold` standard deviations of the baseline's daily nets, and at
 * least `floor` in absolute terms so quiet accounts don't flag pennies. The
 * baseline is every day in `baseline` (quiet days count as zero, so callers
 * pass one point per day). Returns the flagged days among `recent`, largest
 * first. Statistics use doubles; the amounts shown are the exact nets.
 */
export function balanceAnomalies(baseline: readonly DayNet[], recent: readonly DayNet[], threshold = 3, floor: Cents = 100_000n): BalanceAnomaly[] {
  if (baseline.length < 14) return [];
  const xs = baseline.map((d) => Number(parseMoney(d.net)) / 100);
  const mean = xs.reduce((s, x) => s + x, 0) / xs.length;
  const variance = xs.reduce((s, x) => s + (x - mean) ** 2, 0) / xs.length;
  const sd = Math.sqrt(variance);
  const out: BalanceAnomaly[] = [];
  for (const d of recent) {
    const cents = parseMoney(d.net);
    const abs = cents < 0n ? -cents : cents;
    if (abs < floor) continue;
    const x = Number(cents) / 100;
    const score = sd === 0 ? (x === mean ? 0 : Infinity) : Math.abs(x - mean) / sd;
    if (score > threshold) out.push({ day: d.day, net: d.net, mean: mean.toFixed(2), stddev: sd.toFixed(2), score: Number.isFinite(score) ? Math.round(score * 10) / 10 : 99 });
  }
  return out.sort((a, b) => b.score - a.score);
}
