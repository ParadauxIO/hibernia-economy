import type { ReactNode } from 'react';
import { CURRENCY_SYMBOL } from '@/lib/currency';
import { formatPct } from '@democracycraft/design-system/format';
import { ChartDataTable, ChartEmpty, ChartFrame } from './ChartFrame';
import { formatValue, seriesColor, toNumber, type ValueFormat } from './kit';
import { DonutPlot } from './lazy';

export interface DonutItem {
  key: string;
  label: string;
  /** DECIMAL string for money. Non-positive values are listed in the table but not drawn. */
  value: string | number;
  color?: string | undefined;
  /** Extra text for the slice's accessible name and the legend ("1,212 accounts"). */
  detail?: string | undefined;
}

export interface DonutProps {
  items: DonutItem[];
  format?: ValueFormat | undefined;
  height?: number | undefined;
  label: string;
  title?: ReactNode | undefined;
  description?: ReactNode | undefined;
  note?: ReactNode | undefined;
  currency?: string | undefined;
  /** Accessible name for each slice. Default "Label: value, detail". */
  sliceName?: ((item: DonutItem, formatted: string) => string) | undefined;
}

/** Share of a whole. Legend with values and shares sits under the ring, server-rendered. */
export function Donut({ items, format = 'money', height = 220, label, title, description, note, currency = CURRENCY_SYMBOL, sliceName }: DonutProps) {
  const drawn = items.map((it, i) => ({ ...it, color: it.color ?? seriesColor(i), n: toNumber(it.value) ?? 0 })).filter((it) => it.n > 0);
  const sum = drawn.reduce((s, it) => s + it.n, 0);
  const name = (it: DonutItem, formatted: string) => sliceName?.(it, formatted) ?? `${it.label}: ${formatted}${it.detail ? `, ${it.detail}` : ''}`;
  const legend = <ul className="chart-key">
    {drawn.map((it) => <li key={it.key}>
      <span className="chart-swatch" style={{ background: it.color }} aria-hidden="true" />
      <span className="chart-key-label">{it.label}</span>
      <span className="chart-key-value">{formatValue(it.value, format, currency)}</span>
      <span className="chart-key-share">{formatPct(it.n / sum, { decimals: 1 })}</span>
    </li>)}
  </ul>;
  const table = <ChartDataTable label={label} columns={[{ label: 'Category' }, { label: 'Value', numeric: true }, { label: 'Share of positive total', numeric: true }]}
    rows={items.map((it) => { const n = toNumber(it.value) ?? 0; return { key: it.key, cells: [it.label, formatValue(it.value, format, currency), n > 0 && sum > 0 ? formatPct(n / sum) : '—'] }; })} />;
  return <ChartFrame title={title} description={description} height={height} legend={drawn.length ? legend : undefined} note={note} data={table}>
    {drawn.length === 0
      ? <ChartEmpty height={height} />
      : <DonutPlot height={height} format={format} currency={currency} summary={`${label}: ${drawn.map((it) => `${it.label} ${formatPct(it.n / sum)}`).join(', ')}.`}
        slices={drawn.map((it) => ({ key: it.key, label: it.label, value: it.n, raw: it.value, color: it.color, description: name(it, formatValue(it.value, format, currency)) }))} />}
  </ChartFrame>;
}
