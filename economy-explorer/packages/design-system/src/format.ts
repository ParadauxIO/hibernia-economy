// Display formatting for counts, percentages and time, shared by Num, Pct, When
// and the chart kit. Money is not here: lib/money.ts owns money parsing,
// arithmetic and formatting (spec §3 rule 8), and the Money component uses it.

/** U+2212, which lines up with digits in tabular figures; "-" does not. */
export const MINUS = '−';

export type SignDisplay = 'auto' | 'always' | 'never';

function signPrefix(negative: boolean, zero: boolean, sign: SignDisplay): string {
  if (sign === 'never' || zero) return '';
  if (negative) return MINUS;
  return sign === 'always' ? '+' : '';
}

function groupThousands(whole: string): string {
  return whole.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
}

const UNITS: [number, string][] = [[1e12, 'T'], [1e9, 'B'], [1e6, 'M'], [1e3, 'K']];

/** 3 significant digits, trailing zeros trimmed: 40 → "40", 1.25 → "1.25", 12.5 → "12.5". */
function threeSig(n: number): string {
  const decimals = n >= 100 ? 0 : n >= 10 ? 1 : 2;
  return n.toFixed(decimals).replace(/\.?0+$/, '');
}

/** Compact magnitude of a count: 40000 → "40K", 1250000 → "1.25M". */
export function compactMagnitude(abs: number): string {
  for (const [size, unit] of UNITS) {
    if (abs >= size * 0.9995) {
      const scaled = abs / size;
      // 999,950 would round to "1000K"; promote it to the next unit instead.
      if (scaled >= 999.5 && unit !== 'T') continue;
      return `${threeSig(scaled)}${unit}`;
    }
  }
  return abs % 1 === 0 ? String(abs) : abs.toFixed(2);
}

export interface NumFormat { compact?: boolean | undefined; decimals?: number | undefined; sign?: SignDisplay | undefined }

export function formatNum(value: number | bigint, { compact = false, decimals = 0, sign = 'auto' }: NumFormat = {}): string {
  if (typeof value === 'number' && !Number.isFinite(value)) return '—';
  const n = Number(value);
  const prefix = signPrefix(n < 0, n === 0, sign);
  const abs = Math.abs(n);
  if (compact) return `${prefix}${abs < 1000 ? abs.toFixed(decimals) : compactMagnitude(abs)}`;
  if (typeof value === 'bigint') return `${prefix}${groupThousands((value < 0n ? -value : value).toString())}`;
  return `${prefix}${abs.toLocaleString('en-US', { minimumFractionDigits: decimals, maximumFractionDigits: decimals })}`;
}

/** A fraction as a percentage: 0.082 → "8.2%". */
export function formatPct(fraction: number, { decimals = 1, sign = 'auto' }: { decimals?: number | undefined; sign?: SignDisplay | undefined } = {}): string {
  if (!Number.isFinite(fraction)) return '—';
  const pct = fraction * 100;
  const shown = Math.abs(pct).toFixed(decimals);
  const zero = Number(shown) === 0;
  return `${signPrefix(pct < 0, zero, sign)}${shown}%`;
}

const MONTH_DAY: Intl.DateTimeFormatOptions = { month: 'short', day: 'numeric' };
const MONTH_DAY_YEAR: Intl.DateTimeFormatOptions = { month: 'short', day: 'numeric', year: 'numeric' };
const TIME: Intl.DateTimeFormatOptions = { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' };

export function toDate(value: string | number | Date): Date {
  if (value instanceof Date) return value;
  if (typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value)) return new Date(`${value}T00:00:00Z`);
  return new Date(value);
}

/**
 * "5 min ago" under 24 hours, "Oct 25" within the current year, "Oct 25, 2025"
 * otherwise. `relative: false` skips the relative form (used for SSR output,
 * which must not depend on the render instant).
 */
export function formatWhen(value: string | number | Date, { now = Date.now(), timeZone = 'UTC', relative = true, time = false }: { now?: number | undefined; timeZone?: string | undefined; relative?: boolean | undefined; time?: boolean | undefined } = {}): string {
  const date = toDate(value);
  const ms = date.getTime();
  if (!Number.isFinite(ms)) return '—';
  const diff = now - ms;
  if (relative && Math.abs(diff) < 86_400_000) {
    const future = diff < 0;
    const abs = Math.abs(diff);
    if (abs < 45_000) return 'just now';
    const minutes = Math.round(abs / 60_000);
    const text = minutes < 60 ? `${minutes} min` : `${Math.round(abs / 3_600_000)} h`;
    return future ? `in ${text}` : `${text} ago`;
  }
  const year = (d: Date) => new Intl.DateTimeFormat('en-US', { year: 'numeric', timeZone }).format(d);
  const sameYear = year(date) === year(new Date(now));
  const day = date.toLocaleDateString('en-US', { ...(sameYear ? MONTH_DAY : MONTH_DAY_YEAR), timeZone });
  return time ? `${day}, ${date.toLocaleTimeString('en-US', { ...TIME, timeZone })}` : day;
}

/** "2026-10-25 14:03 UTC": the unambiguous form used in tooltips. */
export function formatUtc(value: string | number | Date): string {
  const date = toDate(value);
  if (!Number.isFinite(date.getTime())) return '—';
  return `${date.toISOString().slice(0, 16).replace('T', ' ')} UTC`;
}
