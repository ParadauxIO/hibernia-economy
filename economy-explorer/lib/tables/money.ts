// Money as typed into a filter. `lib/money.ts` owns exact cents (parsing DB
// decimals, arithmetic, formatting); this file only accepts the shorthand people
// type into a search box (`$10k`, `1,500`, `1.5m`) and turns it into cents
// through it. Nothing here goes through a JS double.

import { abs, formatMoney, parseMoney, type Cents } from '@/lib/money';

/** DECIMAL(19,2) holds 17 integer digits, so |cents| stays below 10^19. */
const MAX_CENTS = 10n ** 19n - 1n;

const SHORTHAND_RE = /^([+-]?)(\d+)(?:\.(\d+))?([kmb]?)$/;
const SUFFIX_DIGITS: Record<string, number> = { '': 0, k: 3, m: 6, b: 9 };

/**
 * Parse a typed amount to cents, or `null`. Accepts `1000`, `1,000`,
 * `$1,000.50`, `£10k`, `1.5m`, `-250` and `−250` (U+2212). Unlike a DB decimal,
 * typed input is never rounded: `0.001` or `1.2345k` is rejected rather than
 * silently filtering on a different amount.
 */
export function parseMoneyInput(input: string): Cents | null {
  if (input.length > 40) return null;
  const compact = input
    .trim()
    .toLowerCase()
    .replace(/−/g, '-')
    .replace(/[\s,_]/g, '')
    .replace(/^([+-]?)[$£€]/, '$1');
  const m = SHORTHAND_RE.exec(compact);
  if (!m) return null;
  const [, sign = '', whole = '', frac = '', suffix = ''] = m;
  const shift = SUFFIX_DIGITS[suffix] ?? 0;
  // Move the decimal point `shift` places right by string, then demand at most
  // two fraction digits remain: the value must be whole cents as typed.
  const digits = whole + frac.padEnd(shift, '0');
  const point = whole.length + shift;
  const intPart = digits.slice(0, point).replace(/^0+(?=\d)/, '') || '0';
  const fracPart = digits.slice(point).replace(/0+$/, '');
  if (fracPart.length > 2) return null;
  const cents = parseMoney(`${sign === '-' ? '-' : ''}${intPart}${fracPart ? '.' + fracPart : ''}`);
  return abs(cents) > MAX_CENTS ? null : cents;
}

/** Canonical URL form, trailing zeros dropped: `1000`, `1000.5`, `-0.05`. */
export function centsToDecimal(cents: Cents): string {
  const a = abs(cents);
  const frac = a % 100n;
  const tail = frac === 0n ? '' : '.' + frac.toString().padStart(2, '0').replace(/0$/, '');
  return `${cents < 0n ? '-' : ''}${(a / 100n).toString()}${tail}`;
}

/**
 * Chip text: `$10k`, `$1.5M`, `−$250`, `$12.50`. Shortens only when the short
 * form is exact, so a chip never shows a different amount than it filters on;
 * otherwise it falls back to `formatMoney`'s full form.
 */
export function formatMoneyChip(cents: Cents, symbol: string): string {
  const a = abs(cents);
  const units: [bigint, string][] = [[100_000_000_000n, 'B'], [100_000_000n, 'M'], [100_000n, 'k']];
  for (const [unit, label] of units) {
    if (a < unit) continue;
    const hundredths = a * 100n;
    if (hundredths % unit !== 0n) break;
    const scaled = hundredths / unit; // the amount in hundredths of the unit
    const whole = scaled / 100n;
    const frac = (scaled % 100n).toString().padStart(2, '0').replace(/0+$/, '');
    return `${cents < 0n ? '−' : ''}${symbol}${whole.toString()}${frac ? '.' + frac : ''}${label}`;
  }
  const full = formatMoney(cents, { currency: symbol });
  return full.endsWith('.00') ? full.slice(0, -3) : full;
}
