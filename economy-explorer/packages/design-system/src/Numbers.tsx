// Money is the one place the package reaches into the host: lib/money.ts owns
// money parsing and formatting for the whole explorer (spec §3 rule 8), so the
// component formats through it rather than keeping a second implementation.
import { formatMoney, sign as centsSign, tryParseMoney, type Cents, type FormatMoneyOptions } from '@/lib/money';
import { cx } from './cx';
import { formatNum, formatPct, type SignDisplay } from './format';

/** auto: positive in --pos, negative in --neg. Use only where direction carries meaning. */
export type Tone = 'none' | 'auto' | 'inverse';

function toneClass(sign: -1 | 0 | 1, tone: Tone): string | undefined {
  if (tone === 'none' || sign === 0) return undefined;
  const good = tone === 'auto' ? sign > 0 : sign < 0;
  return good ? 'is-pos' : 'is-neg';
}

export interface MoneyProps {
  /** Integer cents (lib/money `Cents`) or a DB DECIMAL string. Never a float. */
  value: Cents | string | null | undefined;
  /**
   * Symbol override. Default: the tenant's CURRENCY_SYMBOL, read on the server.
   * In client components pass it explicitly (the env isn't in the browser bundle).
   */
  currency?: string | undefined;
  /** $40.0K instead of $40,000.00. The exact value is always in the tooltip. */
  compact?: boolean | undefined;
  /** auto: minus for negatives. always / exceptZero: + on gains. never: magnitude only. */
  sign?: FormatMoneyOptions['sign'] | undefined;
  /** Colour by sign. Default none: most amounts aren't gains or losses. */
  tone?: Tone | undefined;
  className?: string | undefined;
}

/** An amount of money in tabular figures, formatted exactly by lib/money. */
export function Money({ value, currency, compact = false, sign = 'auto', tone = 'none', className }: MoneyProps) {
  const cents = typeof value === 'string' ? tryParseMoney(value) : value ?? null;
  if (cents === null) return <span className={cx('num', 'is-empty', className)}>—</span>;
  const options: FormatMoneyOptions = { sign, ...(currency !== undefined ? { currency } : {}) };
  const text = formatMoney(cents, { ...options, compact });
  const exact = formatMoney(cents, options);
  return <span className={cx('num', toneClass(centsSign(cents), tone), className)} title={compact && exact !== text ? exact : undefined}>{text}</span>;
}

export interface NumProps {
  value: number | bigint | null | undefined;
  compact?: boolean | undefined;
  decimals?: number | undefined;
  sign?: SignDisplay | undefined;
  tone?: Tone | undefined;
  /** Unit after the number, e.g. "accounts". Rendered muted. */
  unit?: string | undefined;
  className?: string | undefined;
}

export function Num({ value, compact = false, decimals = 0, sign = 'auto', tone = 'none', unit, className }: NumProps) {
  if (value === null || value === undefined) return <span className={cx('num', 'is-empty', className)}>—</span>;
  const n = Number(value);
  const text = formatNum(value, { compact, decimals, sign });
  const full = formatNum(value, { decimals, sign });
  return <span className={cx('num', toneClass(Math.sign(n) as -1 | 0 | 1, tone), className)} title={compact && full !== text ? full : undefined}>
    {text}{unit && <span className="num-unit"> {unit}</span>}
  </span>;
}

export interface PctProps {
  /** A fraction: 0.082 renders as 8.2%. */
  value: number | null | undefined;
  decimals?: number | undefined;
  sign?: SignDisplay | undefined;
  tone?: Tone | undefined;
  className?: string | undefined;
}

export function Pct({ value, decimals = 1, sign = 'auto', tone = 'none', className }: PctProps) {
  if (value === null || value === undefined || !Number.isFinite(value)) return <span className={cx('num', 'is-empty', className)}>—</span>;
  return <span className={cx('num', toneClass(Math.sign(value) as -1 | 0 | 1, tone), className)}>{formatPct(value, { decimals, sign })}</span>;
}

export interface DeltaProps {
  /** Change as a fraction of the previous value (0.082 = +8.2%). null renders "—" (no previous period). */
  value: number | null | undefined;
  /** Context after the figure, e.g. "vs previous 30 days". */
  label?: string | undefined;
  /** inverse: a fall is good (costs, fines). neutral: never coloured. */
  tone?: 'auto' | 'inverse' | 'neutral' | undefined;
  decimals?: number | undefined;
}

/** "▲ 8.2% vs previous 30 days": the change against the previous period. */
export function Delta({ value, label, tone = 'auto', decimals = 1 }: DeltaProps) {
  const known = value !== null && value !== undefined && Number.isFinite(value);
  const dir = !known || Math.abs(value) < 0.0005 ? 0 : value > 0 ? 1 : -1;
  const cls = tone === 'neutral' ? undefined : toneClass(dir, tone === 'inverse' ? 'inverse' : 'auto');
  return <span className="delta">
    <span className={cx('delta-figure', cls)}>
      {known ? <>
        <span aria-hidden="true" className="delta-arrow">{dir > 0 ? '▲' : dir < 0 ? '▼' : '–'}</span>
        <span className="sr-only">{dir > 0 ? 'up' : dir < 0 ? 'down' : 'unchanged'} </span>
        {formatPct(Math.abs(value), { decimals, sign: 'never' })}
      </> : '—'}
    </span>
    {label && <span className="delta-label"> {label}</span>}
  </span>;
}
