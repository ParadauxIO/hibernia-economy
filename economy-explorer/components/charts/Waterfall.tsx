import type { ReactNode } from 'react';
import { CURRENCY_SYMBOL } from '@/lib/currency';
import { sumBy, toDbString, tryParseMoney, type Cents } from '@/lib/money';
import { BarChart } from './BarChart';
import { coordToCents, formatValue, toNumber } from './kit';

export interface WaterfallStep {
  key: string;
  label: string;
  /** Signed change, DECIMAL string. */
  value: string | number;
}

export interface WaterfallProps {
  opening: string | number;
  steps: WaterfallStep[];
  openingLabel?: string | undefined;
  closingLabel?: string | undefined;
  /** Exact closing balance if the host has it; otherwise opening + steps in integer cents. */
  closing?: string | number | undefined;
  height?: number | undefined;
  label: string;
  title?: ReactNode | undefined;
  description?: ReactNode | undefined;
  note?: ReactNode | undefined;
  currency?: string | undefined;
}

const cents = (value: string | number): Cents => (typeof value === 'string' ? tryParseMoney(value) ?? 0n : coordToCents(value));

/** Opening balance → each category's change → closing balance (monthly statements). */
export function Waterfall({ opening, steps, openingLabel = 'Opening', closingLabel = 'Closing', closing, height = 260, label, title, description, note, currency = CURRENCY_SYMBOL }: WaterfallProps) {
  const closingRaw = closing ?? toDbString(cents(opening) + sumBy(steps, (s) => cents(s.value)));
  // Bar ranges are chart coordinates; the labels and table use the exact raw values.
  const start = toNumber(opening) ?? 0;
  const ends = steps.reduce<number[]>((acc, s) => [...acc, (acc[acc.length - 1] ?? start) + (toNumber(s.value) ?? 0)], []);
  const rows = [
    { label: openingLabel, range: [0, start], raw: String(opening) },
    ...steps.map((s, i) => {
      const from = i === 0 ? start : ends[i - 1]!;
      const to = ends[i]!;
      return { label: s.label, range: [Math.min(from, to), Math.max(from, to)], raw: String(s.value) };
    }),
    { label: closingLabel, range: [0, toNumber(closingRaw) ?? 0], raw: String(closingRaw) },
  ];
  const colors = rows.map((r, i) => (i === 0 || i === rows.length - 1 ? 'var(--cat-8)' : (toNumber(r.raw) ?? 0) >= 0 ? 'var(--pos)' : 'var(--neg)'));
  return <BarChart data={rows} x="label" series={[{ key: 'range', label: 'Amount', format: 'money', rawKey: 'raw' }]} rowColors={colors} height={height}
    label={label} title={title} description={description} note={note} currency={currency}
    tableColumns={[{ label: 'Step' }, { label: 'Amount', numeric: true }]}
    tableRows={rows.map((r, i) => ({ key: `${r.label}-${i}`, cells: [r.label, formatValue(r.raw, 'money', currency)] }))} />;
}
