import type { ReactNode } from 'react';
import { CURRENCY_SYMBOL } from '@/lib/currency';
import { sumBy, toDbString, tryParseMoney, type Cents } from '@/lib/money';
import { ChartDataTable, ChartEmpty, ChartFrame } from './ChartFrame';
import { coordToCents, formatTick, formatValue } from './kit';
import { waterfallLayout } from './waterfallLayout';
import { WaterfallPlot } from './WaterfallLazy';

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
  empty?: ReactNode | undefined;
}

const cents = (value: string | number): Cents => (typeof value === 'string' ? tryParseMoney(value) ?? 0n : coordToCents(value));

/**
 * Opening balance → each category's change → closing balance (monthly
 * statements). The value axis is fitted to the range the running total covers
 * (see waterfallLayout), so category bars stay legible next to large balances;
 * when it doesn't start at zero, the opening and closing bars are drawn cut
 * and the note says where the axis starts.
 */
export function Waterfall({ opening, steps, openingLabel = 'Opening', closingLabel = 'Closing', closing, height = 260, label, title, description, note, currency = CURRENCY_SYMBOL, empty }: WaterfallProps) {
  const open = cents(opening);
  const close = closing !== undefined ? cents(closing) : open + sumBy(steps, (s) => cents(s.value));
  const layout = waterfallLayout(open, steps.map((s) => ({ key: s.key, label: s.label, cents: cents(s.value) })), close, { opening: openingLabel, closing: closingLabel });
  const rows = layout.bars.map((b) => ({
    label: b.label,
    range: b.range,
    raw: toDbString(b.cents),
    color: b.kind === 'step' ? (b.cents >= 0n ? 'var(--pos)' : 'var(--neg)') : 'var(--cat-8)',
    cut: b.cut,
    cutAt: (b.range[0] === layout.floor ? 'bottom' : 'top') as 'bottom' | 'top',
  }));
  const axisNote = layout.broken
    ? <>The axis starts at {formatTick(layout.floor, 'money', currency)}, not {formatTick(0, 'money', currency)}, so the changes stay visible. {openingLabel} and {closingLabel.toLowerCase()} bars are cut (zigzag); their full amounts are in the tooltip and the data table.</>
    : null;
  const summary = `${label}: ${openingLabel} ${formatValue(toDbString(open), 'money', currency)}, ${steps.length} changes, ${closingLabel} ${formatValue(toDbString(close), 'money', currency)}.`;
  const table = <ChartDataTable label={label} columns={[{ label: 'Step' }, { label: 'Amount', numeric: true }]}
    rows={rows.map((r, i) => ({ key: `${r.label}-${i}`, cells: [r.label, formatValue(r.raw, 'money', currency)] }))} />;
  const fullNote = axisNote && note ? <>{axisNote} {note}</> : axisNote ?? note;
  return <ChartFrame title={title} description={description} height={height} note={steps.length ? fullNote : undefined} data={steps.length ? table : undefined}>
    {steps.length === 0
      ? <ChartEmpty height={height}>{empty ?? 'No money moved in this period.'}</ChartEmpty>
      : <WaterfallPlot data={rows} domain={layout.domain} ticks={layout.ticks} height={height} currency={currency} summary={summary} />}
  </ChartFrame>;
}
