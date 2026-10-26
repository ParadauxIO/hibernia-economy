import type { ReactNode } from 'react';
import { CURRENCY_SYMBOL } from '@/lib/currency';
import { ChartDataTable, ChartEmpty, ChartFrame } from './ChartFrame';
import { colorOf, formatDay, formatValue, timeSeriesLayout, totalHeight, type Row, type Series } from './kit';
import { TimeSeriesPlot } from './lazy';

export interface TimeSeriesChartProps {
  data: Row[];
  /** Key of the YYYY-MM-DD bucket. Default "date". */
  x?: string | undefined;
  series: Series[];
  kind?: 'area' | 'line' | undefined;
  stacked?: boolean | undefined;
  /** Plot height in px, excluding legend and axis-title rows. */
  height?: number | undefined;
  /** What the chart shows ("Volume by day"). Names the data table and starts the summary. */
  label: string;
  title?: ReactNode | undefined;
  description?: ReactNode | undefined;
  note?: ReactNode | undefined;
  /** Clamp a single outlier day and offer a log scale. Default true (ignored when stacked). */
  outliers?: boolean | undefined;
  currency?: string | undefined;
  empty?: ReactNode | undefined;
}

/** Plain-language summary for screen readers: span and latest values. */
export function timeSeriesSummary(label: string, data: Row[], x: string, series: Series[], currency: string): string {
  const first = data[0]?.[x];
  const last = data[data.length - 1];
  if (!first || !last) return `${label}: no data.`;
  const latest = series.map((s) => `${s.label} ${formatValue(last[s.key], s.format ?? 'money', currency)}`).join(', ');
  return `${label}, daily from ${formatDay(String(first), true)} to ${formatDay(String(last[x]), true)}. Latest: ${latest}.`;
}

/** Area or line chart over days: one or more series, optional second axis. */
export function TimeSeriesChart({ data, x = 'date', series, kind = 'area', stacked = false, height = 240, label, title, description, note = 'Daily buckets in UTC.', outliers = true, currency = CURRENCY_SYMBOL, empty }: TimeSeriesChartProps) {
  const layout = timeSeriesLayout(data, series, outliers && !stacked);
  const total = totalHeight(height, layout);
  const table = <ChartDataTable label={label}
    columns={[{ label: 'Date (UTC)' }, ...series.map((s) => ({ label: s.label, numeric: true }))]}
    rows={data.map((r) => ({ key: String(r[x]), cells: [formatDay(String(r[x]), true), ...series.map((s) => formatValue(r[s.key], s.format ?? 'money', currency))] }))} />;
  // Colours are resolved here so server-rendered legends match the plot.
  const resolved = series.map((s, i) => ({ ...s, color: colorOf(s, i) }));
  return <ChartFrame title={title} description={description} height={data.length ? total : height} note={data.length ? note : undefined} data={data.length ? table : undefined}>
    {data.length === 0
      ? <ChartEmpty height={height}>{empty}</ChartEmpty>
      : <TimeSeriesPlot data={data} x={x} series={resolved} kind={kind} stacked={stacked} height={height} currency={currency} outliers={outliers}
        summary={timeSeriesSummary(label, data, x, series, currency)} />}
  </ChartFrame>;
}
