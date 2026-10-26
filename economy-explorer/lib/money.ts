import { CURRENCY_SYMBOL } from '@/lib/currency';

// Exact money (spec §3 rule 8). Every amount is integer cents in a `bigint`, so
// sums never drift the way `parseFloat` sums do (0.1 + 0.2 + 0.3 is not 0.6 in a
// double) and stay exact past 2^53 cents. The database hands DECIMAL columns to
// us as strings ("-1234.50"); parse them here, do arithmetic here, and format
// here. Nothing else should turn money into a JS number, except chart
// coordinates and ratios through `toNumber` / `ratio`, which are display-only.
//
// No `server-only`: client components format money too.

/** Integer cents. `1234n` is $12.34. */
export type Cents = bigint;

export const ZERO: Cents = 0n;

export class MoneyParseError extends Error {
  constructor(readonly input: unknown) {
    super(`Not a decimal money amount: ${typeof input === 'string' ? JSON.stringify(input) : typeof input}`);
    this.name = 'MoneyParseError';
  }
}

// Plain decimal only: optional sign, digits, optional fraction. No exponent, no
// grouping commas, no bare "." or ".5" — MariaDB never produces those, so seeing
// one means the caller passed something that is not a DB decimal.
const DECIMAL_RE = /^([+-])?(\d+)(?:\.(\d+))?$/;

/**
 * Parse a decimal string (a DB DECIMAL, or a typed amount) to cents.
 * More than two fraction digits round half away from zero, as MariaDB's
 * ROUND does for exact values ("0.005" → 1n, "-0.005" → -1n).
 * Throws {@link MoneyParseError} on anything else.
 */
export function parseMoney(value: string): Cents {
  if (typeof value !== 'string') throw new MoneyParseError(value);
  const m = DECIMAL_RE.exec(value.trim());
  if (!m) throw new MoneyParseError(value);
  const [, sign, whole = '0', fraction = ''] = m;
  let cents = BigInt(whole) * 100n + BigInt(fraction.slice(0, 2).padEnd(2, '0'));
  if (fraction.length > 2 && fraction.charCodeAt(2) >= 53 /* '5' */) cents += 1n;
  return sign === '-' ? -cents : cents;
}

/** Like {@link parseMoney}, but `null` for null, undefined or malformed input. */
export function tryParseMoney(value: string | null | undefined): Cents | null {
  if (value == null) return null;
  try {
    return parseMoney(value);
  } catch {
    return null;
  }
}

/** Cents as the DB decimal string: `-123450n` → `"-1234.50"`. Round-trips with {@link parseMoney}. */
export function toDbString(cents: Cents): string {
  const a = abs(cents);
  return `${cents < 0n ? '-' : ''}${a / 100n}.${String(a % 100n).padStart(2, '0')}`;
}

export const add = (a: Cents, b: Cents): Cents => a + b;
export const sub = (a: Cents, b: Cents): Cents => a - b;
export const negate = (a: Cents): Cents => -a;
export const abs = (a: Cents): Cents => (a < 0n ? -a : a);
export const sign = (a: Cents): -1 | 0 | 1 => (a < 0n ? -1 : a > 0n ? 1 : 0);
/** Sort comparator: negative when a < b. */
export const compare = (a: Cents, b: Cents): -1 | 0 | 1 => (a < b ? -1 : a > b ? 1 : 0);

/** Sum cents or DB decimal strings. Strings are parsed strictly (malformed throws). */
export function sum(values: Iterable<Cents | string>): Cents {
  let total = 0n;
  for (const v of values) total += typeof v === 'string' ? parseMoney(v) : v;
  return total;
}

/** Sum one money field across rows: `sumBy(edges, e => e.amount)`. */
export function sumBy<T>(rows: Iterable<T>, pick: (row: T) => Cents | string): Cents {
  let total = 0n;
  for (const row of rows) {
    const v = pick(row);
    total += typeof v === 'string' ? parseMoney(v) : v;
  }
  return total;
}

/** Divide and round half away from zero, e.g. an average balance. Throws on a zero divisor. */
export function divRound(cents: Cents, divisor: bigint | number): Cents {
  const d = BigInt(divisor);
  if (d === 0n) throw new RangeError('divRound by zero');
  const q = cents / d;
  const r = cents % d;
  // |2r| >= |d| means the remainder is at least half the divisor.
  if (abs(r) * 2n >= abs(d)) return (cents < 0n) !== (d < 0n) ? q - 1n : q + 1n;
  return q;
}

/** `part / whole` as a plain number for percentages and shares; `null` when whole is zero. */
export function ratio(part: Cents, whole: Cents): number | null {
  if (whole === 0n) return null;
  // Scale first so the division keeps precision even when both are past 2^53.
  return Number((part * 1_000_000_000n) / whole) / 1_000_000_000;
}

/** Dollars as a JS number. For chart coordinates only: never sum or compare the result. */
export function toNumber(cents: Cents): number {
  return Number(cents) / 100;
}

export interface FormatMoneyOptions {
  /** `$1.50K` style instead of `$1,500.00`. */
  compact?: boolean;
  /**
   * `auto`: minus for negatives only (default). `always`: `+` on zero and positives.
   * `exceptZero`: `+` on positives, nothing on zero. `never`: magnitude only.
   */
  sign?: 'auto' | 'always' | 'exceptZero' | 'never';
  /** `false` omits the symbol; a string overrides the tenant symbol. */
  currency?: boolean | string;
}

const MINUS = '−'; // a real minus sign, matching lib/format

// Largest first. `cents` is the unit size in cents; `digits` the shown decimals.
const COMPACT_UNITS = [
  { suffix: 'T', cents: 10n ** 14n, digits: 2 },
  { suffix: 'B', cents: 10n ** 11n, digits: 2 },
  { suffix: 'M', cents: 10n ** 8n, digits: 2 },
  { suffix: 'K', cents: 10n ** 5n, digits: 1 },
] as const;

function group(digits: string): string {
  return digits.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
}

/** Non-negative `scaled / 10^digits` as a fixed-point string. */
function fixed(scaled: bigint, digits: number, grouped: boolean): string {
  const unit = 10n ** BigInt(digits);
  const whole = String(scaled / unit);
  const head = grouped ? group(whole) : whole;
  return digits === 0 ? head : `${head}.${String(scaled % unit).padStart(digits, '0')}`;
}

function compactMagnitude(a: Cents): string {
  for (let i = 0; i < COMPACT_UNITS.length; i++) {
    const u = COMPACT_UNITS[i]!;
    if (a < u.cents) continue;
    const scale = 10n ** BigInt(u.digits);
    const scaled = divRound(a * scale, u.cents);
    // 999,999.99 rounds to "1000.0K"; show it as "1.00M" instead.
    const bigger = COMPACT_UNITS[i - 1];
    if (bigger && scaled >= 1000n * scale) {
      const s2 = 10n ** BigInt(bigger.digits);
      return `${fixed(divRound(a * s2, bigger.cents), bigger.digits, true)}${bigger.suffix}`;
    }
    return `${fixed(scaled, u.digits, true)}${u.suffix}`;
  }
  return fixed(a, 2, false);
}

/**
 * Format cents (or a DB decimal string) for display: `−$1,234.50`, or `−$1.2K`
 * when compact. A malformed string formats as an em dash, like lib/format.
 */
export function formatMoney(value: Cents | string, options: FormatMoneyOptions = {}): string {
  const cents = typeof value === 'string' ? tryParseMoney(value) : value;
  if (cents === null) return '—';
  const a = abs(cents);
  const symbol = options.currency === false ? '' : typeof options.currency === 'string' ? options.currency : CURRENCY_SYMBOL;
  const body = options.compact ? compactMagnitude(a) : fixed(a, 2, true);
  const mode = options.sign ?? 'auto';
  let prefix = '';
  if (mode !== 'never') {
    if (cents < 0n) prefix = MINUS;
    else if (mode === 'always' || (mode === 'exceptZero' && cents > 0n)) prefix = '+';
  }
  return `${prefix}${symbol}${body}`;
}
