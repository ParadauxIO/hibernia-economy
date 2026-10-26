import type { ReactNode } from 'react';
import { CURRENCY_SYMBOL } from '@/lib/currency';
import { ChartDataTable, ChartEmpty, ChartFrame } from './ChartFrame';
import { colorOf, formatDay, formatValue, TOOLBAR_HEIGHT, type Row, type Series } from './kit';
import { BarPlot } from './lazy';

export interface BarChartProps {
  data: Row[];
  /** Key of the category or YYYY-MM-DD bucket. */
  x: string;
  series: Series[];
  stacked?: boolean | undefined;
  /** day: x holds days (formatted "Oct 25"); category: x holds labels. */
  xFormat?: 'day' | 'category' | undefined;
  /** Horizontal bars for long category names. */
  horizontal?: boolean | undefined;
  /** Angle the category labels so each one shows. */
  angleLabels?: boolean | undefined;
  height?: number | undefined;
  label: string;
  title?: ReactNode | undefined;
  description?: ReactNode | undefined;
  note?: ReactNode | undefined;
  currency?: string | undefined;
  empty?: ReactNode | undefined;
  /** Per-row colours (waterfall); overrides series colour. */
  rowColors?: string[] | undefined;
  /** Data-table rows if the plotted rows aren't readable as-is (waterfall ranges). */
  tableRows?: { key: string; cells: ReactNode[] }[] | undefined;
  tableColumns?: { label: string; numeric?: boolean }[] | undefined;
}

/** Grouped or stacked bars over categories or days. */
export function BarChart({ data, x, series, stacked = false, xFormat = 'category', horizontal = false, angleLabels = false, height = 240, label, title, description, note, currency = CURRENCY_SYMBOL, empty, rowColors, tableRows, tableColumns }: BarChartProps) {
  const total = height + (series.length > 1 ? TOOLBAR_HEIGHT : 0);
  const fmtX = (v: unknown) => (xFormat === 'day' ? formatDay(String(v), true) : String(v ?? ''));
  const table = <ChartDataTable label={label}
    columns={tableColumns ?? [{ label: xFormat === 'day' ? 'Date (UTC)' : 'Category' }, ...series.map((s) => ({ label: s.label, numeric: true }))]}
    rows={tableRows ?? data.map((r, i) => ({ key: `${String(r[x])}-${i}`, cells: [fmtX(r[x]), ...series.map((s) => formatValue(r[s.rawKey ?? s.key], s.format ?? 'money', currency))] }))} />;
  const resolved = series.map((s, i) => ({ ...s, color: colorOf(s, i) }));
  const summary = `${label}: ${data.length} ${xFormat === 'day' ? 'days' : 'categories'}${series.length > 1 ? `, ${series.map((s) => s.label).join(' and ')}` : ''}.`;
  return <ChartFrame title={title} description={description} height={data.length ? total : height} note={data.length ? note : undefined} data={data.length ? table : undefined}>
    {data.length === 0
      ? <ChartEmpty height={height}>{empty}</ChartEmpty>
      : <BarPlot data={data} x={x} series={resolved} stacked={stacked} height={height} currency={currency} summary={summary} xFormat={xFormat} horizontal={horizontal} angleLabels={angleLabels} rowColors={rowColors} />}
  </ChartFrame>;
}
