// Chart helpers shared by every chart: ticks, axis widths, outliers, value
// formats, colours and the sizes the server skeleton reserves. Pure functions
// (no React, no recharts) so the server wrapper and the client plot agree, and
// so each chart bug has a unit test here.

import { compactMagnitude, formatNum, formatPct, MINUS } from '@democracycraft/design-system/format';
import { formatMoney, tryParseMoney, type Cents } from '@/lib/money';

/** Chart coordinate (dollars, a double) to cents for display. Coordinates are already lossy; this only rounds. */
export function coordToCents(value: number): Cents {
  return BigInt(Math.round(value * 100));
}

/** Every Area and Line uses this curve. Monotone never overshoots its data (no dips below zero). */
export const CURVE = 'monotone' as const;

export type ValueFormat = 'money' | 'count' | 'number' | 'percent';

export interface Series {
  key: string;
  label: string;
  /** A CSS colour, normally a token: var(--cat-3). Defaults by position. */
  color?: string | undefined;
  axis?: 'left' | 'right' | undefined;
  format?: ValueFormat | undefined;
  /** Hidden by default on phones (the secondary series of a dual-axis chart). */
  hideOnNarrow?: boolean | undefined;
  /** Row key holding the value to show in tooltips when the plotted value is transformed (waterfall ranges, mirrored bars). */
  rawKey?: string | undefined;
}

/** One data point. Arrays are [low, high] ranges (waterfall steps). */
export type Row = Record<string, string | number | null | undefined | number[]>;

/** Categorical colour for the i-th series: --cat-1 … --cat-10. */
export function seriesColor(i: number): string {
  return `var(--cat-${(i % 10) + 1})`;
}

export function colorOf(series: Series, i: number): string {
  return series.color ?? seriesColor(i);
}

/** Chart coordinate from a row value. Money arrives as DECIMAL strings; coordinates may be doubles. */
export function toNumber(value: unknown): number | null {
  if (value === null || value === undefined || value === '') return null;
  const n = typeof value === 'number' ? value : Number(value);
  return Number.isFinite(n) ? n : null;
}

/* ───────────── formats ───────────── */

/**
 * Axis tick label: the compact Money format with redundant zeros dropped
 * ("$40K", "$1.25M", "$0"). Ticks sit on nice numbers, so this loses nothing.
 */
export function formatTick(value: number, format: ValueFormat, currency: string): string {
  switch (format) {
    case 'money': return formatMoney(coordToCents(value), { currency, compact: true }).replace(/\.0+(?=[KMBT]?$)/, '');
    case 'count': return formatNum(value, { compact: true });
    case 'number': return Math.abs(value) >= 1000 ? `${value < 0 ? MINUS : ''}${compactMagnitude(Math.abs(value))}` : formatNum(value, { decimals: Number.isInteger(value) ? 0 : 2 });
    case 'percent': return formatPct(value, { decimals: 0 });
  }
}

/** Tooltip and data-table value: full precision. Decimal strings stay exact. */
export function formatValue(value: Row[string], format: ValueFormat, currency: string): string {
  if (value === null || value === undefined || value === '' || Array.isArray(value)) return '—';
  switch (format) {
    case 'money': {
      // DECIMAL strings format exactly; numeric rows (already coordinates) round to the cent.
      const cents = typeof value === 'string' ? tryParseMoney(value) : coordToCents(value);
      return cents === null ? '—' : formatMoney(cents, { currency });
    }
    case 'count': return formatNum(Number(value));
    case 'number': return formatNum(Number(value), { decimals: Number.isInteger(Number(value)) ? 0 : 2 });
    case 'percent': return formatPct(Number(value));
  }
}

/** "Oct 25" for a YYYY-MM-DD bucket (UTC). */
export function formatDay(day: string, withYear = false): string {
  const d = new Date(day.length === 10 ? `${day}T00:00:00Z` : day);
  if (!Number.isFinite(d.getTime())) return day;
  return d.toLocaleDateString('en-US', { month: 'short', day: 'numeric', ...(withYear ? { year: 'numeric' } : {}), timeZone: 'UTC' });
}

/* ───────────── ticks ───────────── */

function niceNum(range: number, round: boolean): number {
  const exp = Math.floor(Math.log10(range));
  const f = range / 10 ** exp;
  const nice = round ? (f < 1.5 ? 1 : f < 3 ? 2 : f < 7 ? 5 : 10) : (f <= 1 ? 1 : f <= 2 ? 2 : f <= 5 ? 5 : 10);
  return nice * 10 ** exp;
}

/**
 * Ticks on nice numbers (1, 2, 5 × 10ⁿ) covering [min, max]. Always includes
 * zero when the range spans it, so the baseline is a real gridline.
 */
export function niceTicks(min: number, max: number, count = 5): number[] {
  if (!Number.isFinite(min) || !Number.isFinite(max)) return [0, 1];
  if (min === max) {
    if (min === 0) return [0, 1];
    const pad = Math.abs(min) * 0.1;
    min -= pad; max += pad;
  }
  const step = niceNum(niceNum(max - min, false) / Math.max(1, count - 1), true);
  const lo = Math.floor(min / step) * step;
  const hi = Math.ceil(max / step) * step;
  const ticks: number[] = [];
  for (let v = lo; v <= hi + step / 2; v += step) ticks.push(Number(v.toPrecision(12)) || 0);
  return ticks;
}

/** Domain and ticks for a value axis that includes zero. */
export function valueAxis(values: number[], count = 5): { domain: [number, number]; ticks: number[] } {
  const finite = values.filter(Number.isFinite);
  const min = Math.min(0, ...finite);
  const max = Math.max(0, ...finite);
  const ticks = niceTicks(min, max, count);
  return { domain: [ticks[0]!, ticks[ticks.length - 1]!], ticks };
}

/* ───────────── outliers ───────────── */

export function percentile(sorted: number[], p: number): number {
  if (sorted.length === 0) return 0;
  const i = (sorted.length - 1) * p;
  const lo = Math.floor(i);
  const hi = Math.ceil(i);
  return sorted[lo]! + (sorted[hi]! - sorted[lo]!) * (i - lo);
}

export interface OutlierClamp {
  /** Axis maximum: values above it are drawn at the top with a flag. */
  cap: number;
  /** Row indexes whose value was clipped. */
  clipped: number[];
}

/**
 * When the maximum is more than 10× the 95th percentile, one bad day would
 * flatten everything else; clamp the axis just above the typical range instead.
 * Returns null when the data has no such outlier.
 */
export function detectOutliers(values: (number | null)[], ratio = 10): OutlierClamp | null {
  const present = values.filter((v): v is number => v !== null && Number.isFinite(v) && v > 0);
  if (present.length < 5) return null;
  const sorted = [...present].sort((a, b) => a - b);
  const p95 = percentile(sorted, 0.95);
  const max = sorted[sorted.length - 1]!;
  if (p95 <= 0 || max <= p95 * ratio) return null;
  // Cap on a nice number just above the typical range, from the values that stay.
  const kept = sorted.filter((v) => v <= p95 * ratio);
  const ticks = niceTicks(0, Math.max(...kept) * 1.1, 5);
  const cap = ticks[ticks.length - 1]!;
  const clipped = values.flatMap((v, i) => (v !== null && v > cap ? [i] : []));
  return clipped.length ? { cap, clipped } : null;
}

/* ───────────── log scale ───────────── */

/** Symmetric log: defined at zero and for negatives, unlike log10. */
export function symlog(v: number): number {
  return Math.sign(v) * Math.log10(1 + Math.abs(v));
}
export function symlogInverse(t: number): number {
  return Math.sign(t) * (10 ** Math.abs(t) - 1);
}

/** Ticks for a symlog axis: 0 and powers of ten up to the maximum. Values are in transformed units. */
export function logTicks(maxValue: number): { ticks: number[]; values: number[] } {
  const top = Math.max(1, Math.ceil(Math.log10(Math.max(1, maxValue))));
  const step = top > 8 ? 2 : 1;
  const values = [0];
  for (let k = step; k <= top; k += step) values.push(10 ** k);
  if (values[values.length - 1]! < maxValue) values.push(10 ** (top + (step - 1)));
  return { values, ticks: values.map(symlog) };
}

/* ───────────── sizes ───────────── */

const CHAR_EM: Record<string, number> = { ',': 0.3, '.': 0.3, ' ': 0.3, '−': 0.62, '-': 0.4, '+': 0.62, '%': 0.85 };

/** Width estimate for a label in tabular figures (server side, and the canvas fallback). */
export function estimateTextWidth(text: string, fontPx = 12): number {
  let em = 0;
  for (const ch of text) em += CHAR_EM[ch] ?? (/[0-9]/.test(ch) ? 0.62 : /[A-Z$£€]/.test(ch) ? 0.68 : 0.56);
  return em * fontPx;
}

/**
 * Y-axis width that fits the longest formatted tick label, so labels are never
 * clipped ("$40,000" was showing as "0,000"). `measure` is the canvas
 * measurement in the browser; the estimate is a little generous on purpose.
 */
export function axisWidth(labels: string[], fontPx = 12, measure: (s: string) => number = (s) => estimateTextWidth(s, fontPx)): number {
  const widest = Math.max(0, ...labels.map(measure));
  return Math.ceil(widest + 12); // tick gap + breathing room
}

export const AXIS_FONT_PX = 12;
/** Fixed rows above the plot, reserved by the server skeleton too. */
export const TOOLBAR_HEIGHT = 40;
export const AXIS_TITLES_HEIGHT = 20;

export interface ChromeLayout { toolbar: boolean; axisTitles: boolean }

export function totalHeight(plotHeight: number, layout: ChromeLayout): number {
  return plotHeight + (layout.toolbar ? TOOLBAR_HEIGHT : 0) + (layout.axisTitles ? AXIS_TITLES_HEIGHT : 0);
}

/** Which chrome rows a time-series chart needs, computed identically on server and client. */
export function timeSeriesLayout(data: Row[], series: Series[], outliers: boolean): ChromeLayout & { clamp: OutlierClamp | null } {
  const axes = new Set(series.map((s) => s.axis ?? 'left'));
  const first = series.find((s) => (s.axis ?? 'left') === 'left') ?? series[0];
  // Clamp only single-axis-per-side charts on the main series: the common case (a volume spike).
  const clamp = outliers && first ? detectOutliers(data.map((r) => toNumber(r[first.key]))) : null;
  return { toolbar: series.length > 1 || clamp !== null, axisTitles: axes.size > 1, clamp };
}
