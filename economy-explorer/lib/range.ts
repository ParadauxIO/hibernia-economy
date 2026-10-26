// Date ranges from URL params. Pure (no server-only), so pages, services and
// tests share one parser. URL: ?range=<preset|custom>&from=YYYY-MM-DD&to=YYYY-MM-DD&cmp=1
//
// All ranges are whole UTC days, inclusive at both ends. Malformed input never
// throws: it falls back to the default preset.

import { RANGE_PARAMS, RANGE_PRESETS, type RangePreset, type RangeValue } from '@democracycraft/design-system';

export { RANGE_PRESETS, RANGE_PARAMS, type RangePreset, type RangeValue };

/** Inclusive UTC day range, YYYY-MM-DD. The shape the rollup helpers (lib/sql/rollup.ts) take. */
export type DayRange = { from: string; to: string };

export interface ParsedRange {
  preset: RangePreset | 'custom';
  range: DayRange;
  /** The same number of days immediately before `range`, when cmp=1. */
  previous: DayRange | null;
  compare: boolean;
  /** For "24h": the exact instant 24 hours before the request. Day-grained queries can ignore it. */
  since?: Date | undefined;
  /** Human label: "Last 30 days", "Year to date", "Sep 1 – Sep 30, 2026". */
  label: string;
  /** Props for <RangePicker value={...} />. */
  value: RangeValue;
}

export interface ParseRangeOptions {
  defaultPreset?: RangePreset | undefined;
  /** "Today" in UTC; injectable for tests. */
  now?: Date | undefined;
  /** First day with data; "all" starts here and custom ranges are clamped to it. */
  earliest?: string | undefined;
}

const DAY_MS = 86_400_000;
const ISO_DAY = /^\d{4}-\d{2}-\d{2}$/;
const DEFAULT_EARLIEST = '2020-01-01';
const PRESET_KEYS = new Set<string>(RANGE_PRESETS.map((p) => p.key));
const LEGACY_DAYS: Record<string, RangePreset> = { '7': '7d', '30': '30d', '90': '90d', '365': '1y' };

export function toDay(date: Date): string {
  return date.toISOString().slice(0, 10);
}

/** Valid YYYY-MM-DD that round-trips through Date (rejects 2026-02-31). */
export function isDay(value: unknown): value is string {
  if (typeof value !== 'string' || !ISO_DAY.test(value)) return false;
  const d = new Date(`${value}T00:00:00Z`);
  return Number.isFinite(d.getTime()) && toDay(d) === value;
}

export function addDays(day: string, n: number): string {
  return toDay(new Date(Date.parse(`${day}T00:00:00Z`) + n * DAY_MS));
}

/** Number of days in an inclusive range (a single day is 1). */
export function rangeDays(range: DayRange): number {
  return Math.round((Date.parse(`${range.to}T00:00:00Z`) - Date.parse(`${range.from}T00:00:00Z`)) / DAY_MS) + 1;
}

/** The range of equal length ending the day before `range` starts. */
export function previousRange(range: DayRange): DayRange {
  const n = rangeDays(range);
  return { from: addDays(range.from, -n), to: addDays(range.from, -1) };
}

export function presetRange(preset: RangePreset, now: Date = new Date(), earliest = DEFAULT_EARLIEST): DayRange {
  const today = toDay(now);
  switch (preset) {
    // Day-grained: yesterday and today. `ParsedRange.since` carries the exact instant.
    case '24h': return { from: addDays(today, -1), to: today };
    case '7d': return { from: addDays(today, -6), to: today };
    case '30d': return { from: addDays(today, -29), to: today };
    case '90d': return { from: addDays(today, -89), to: today };
    case 'ytd': return { from: `${today.slice(0, 4)}-01-01`, to: today };
    case '1y': return { from: addDays(today, -364), to: today };
    case 'all': return { from: earliest, to: today };
  }
}

const fmtDay = (day: string, withYear: boolean) => new Date(`${day}T00:00:00Z`).toLocaleDateString('en-US', { month: 'short', day: 'numeric', ...(withYear ? { year: 'numeric' } : {}), timeZone: 'UTC' });

export function rangeLabel(preset: RangePreset | 'custom', range: DayRange): string {
  if (preset !== 'custom') return RANGE_PRESETS.find((p) => p.key === preset)?.title ?? preset;
  if (range.from === range.to) return fmtDay(range.from, true);
  const sameYear = range.from.slice(0, 4) === range.to.slice(0, 4);
  return `${fmtDay(range.from, !sameYear)} – ${fmtDay(range.to, true)}`;
}

type Params = URLSearchParams | Record<string, string | string[] | undefined>;

function read(params: Params, key: string): string | undefined {
  if (params instanceof URLSearchParams) return params.get(key) ?? undefined;
  const v = params[key];
  return Array.isArray(v) ? v[0] : v;
}

/** Parse range params. Also accepts the legacy `days=7|30|90|365`. */
export function parseRange(params: Params, { defaultPreset = '30d', now = new Date(), earliest = DEFAULT_EARLIEST }: ParseRangeOptions = {}): ParsedRange {
  const today = toDay(now);
  const raw = read(params, RANGE_PARAMS.range)?.toLowerCase().trim();
  const compare = read(params, RANGE_PARAMS.compare) === '1';
  let preset: RangePreset | 'custom' = defaultPreset;
  let range: DayRange | null = null;

  if (raw === 'custom') {
    let from = read(params, RANGE_PARAMS.from);
    let to = read(params, RANGE_PARAMS.to);
    if (isDay(from) && isDay(to)) {
      if (from > to) [from, to] = [to, from];
      if (to > today) to = today;
      if (from < earliest) from = earliest;
      if (from <= to) { preset = 'custom'; range = { from, to }; }
    }
  } else if (raw && PRESET_KEYS.has(raw)) {
    preset = raw as RangePreset;
  } else if (!raw) {
    const days = read(params, 'days');
    if (days && LEGACY_DAYS[days]) preset = LEGACY_DAYS[days];
  }

  if (!range) range = presetRange(preset as RangePreset, now, earliest);
  const result: ParsedRange = {
    preset,
    range,
    compare,
    previous: compare ? previousRange(range) : null,
    label: rangeLabel(preset, range),
    value: { preset, from: range.from, to: range.to, compare },
  };
  if (preset === '24h') result.since = new Date(now.getTime() - DAY_MS);
  return result;
}

/** URL params for a parsed range (to build links that keep the current range). */
export function rangeToParams(parsed: Pick<ParsedRange, 'preset' | 'range' | 'compare'>): Record<string, string> {
  const out: Record<string, string> = { [RANGE_PARAMS.range]: parsed.preset };
  if (parsed.preset === 'custom') { out[RANGE_PARAMS.from] = parsed.range.from; out[RANGE_PARAMS.to] = parsed.range.to; }
  if (parsed.compare) out[RANGE_PARAMS.compare] = '1';
  return out;
}
