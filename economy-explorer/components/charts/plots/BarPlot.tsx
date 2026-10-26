'use client';

import { Bar, BarChart, CartesianGrid, Cell, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { AXIS_TICK, GRID_STROKE, Legend, measuredAxisWidth, TooltipCard, useSeriesToggle } from '../client-kit';
import { colorOf, formatDay, formatTick, formatValue, toNumber, TOOLBAR_HEIGHT, valueAxis, type Row, type Series } from '../kit';

export interface BarPlotProps {
  data: Row[];
  x: string;
  series: Series[];
  stacked: boolean;
  height: number;
  currency: string;
  summary: string;
  /** day: x holds YYYY-MM-DD buckets. category: x holds labels. */
  xFormat: 'day' | 'category';
  /** Horizontal bars (long category names). */
  horizontal: boolean;
  /** Angle category labels so every one fits (distribution buckets). */
  angleLabels: boolean;
  /** One colour per row (waterfall steps). Overrides the series colour. */
  rowColors?: string[] | undefined;
}

function plotted(value: unknown): number[] {
  if (Array.isArray(value)) return value.map(Number).filter(Number.isFinite);
  const n = toNumber(value);
  return n === null ? [] : [n];
}

export default function BarPlot({ data, x, series, stacked, height, currency, summary, xFormat, horizontal, angleLabels, rowColors }: BarPlotProps) {
  const { hidden, toggle } = useSeriesToggle(series);
  const visible = series.filter((s) => !hidden.has(s.key));
  const format = series[0]?.format ?? 'money';
  const values = stacked
    ? data.flatMap((r) => {
      const pos = visible.reduce((sum, s) => sum + Math.max(0, toNumber(r[s.key]) ?? 0), 0);
      const neg = visible.reduce((sum, s) => sum + Math.min(0, toNumber(r[s.key]) ?? 0), 0);
      return [pos, neg];
    })
    : data.flatMap((r) => visible.flatMap((s) => plotted(r[s.key])));
  const { ticks, domain } = valueAxis(values);
  const labels = ticks.map((t) => formatTick(t, format, currency));
  const width = measuredAxisWidth(labels);
  const fmtX = (v: string) => (xFormat === 'day' ? formatDay(v) : v);
  const categoryWidth = horizontal ? Math.min(180, measuredAxisWidth(data.map((r) => String(r[x] ?? '')))) : 0;
  const valueAxisProps = { ticks, domain, allowDataOverflow: true, tickFormatter: (_v: number, i: number) => labels[i] ?? '', interval: 0 as const, tick: AXIS_TICK, tickLine: false, axisLine: false };

  return <div className="chart-body">
    {series.length > 1 && <div className="chart-toolbar" style={{ height: TOOLBAR_HEIGHT }}><Legend series={series} hidden={hidden} onToggle={toggle} /></div>}
    <div className="chart-svg" role="img" aria-label={summary} style={{ height }}>
      <ResponsiveContainer width="100%" height={height}>
        <BarChart data={data} layout={horizontal ? 'vertical' : 'horizontal'} margin={{ top: 8, right: 4, bottom: 0, left: 0 }} barCategoryGap="22%" accessibilityLayer={false} {...(stacked ? { stackOffset: 'sign' as const } : {})}>
          <CartesianGrid stroke={GRID_STROKE} vertical={horizontal} horizontal={!horizontal} />
          {horizontal
            ? <>
              <XAxis type="number" {...valueAxisProps} />
              <YAxis type="category" dataKey={x} width={categoryWidth} tick={{ ...AXIS_TICK, fill: 'var(--fg-soft)' }} tickLine={false} axisLine={false} interval={0} />
            </>
            : <>
              <XAxis dataKey={x} tickFormatter={fmtX} tick={AXIS_TICK} tickLine={false} axisLine={{ stroke: 'var(--border)' }}
                {...(angleLabels ? { interval: 0, angle: -35, textAnchor: 'end', height: 58 } : xFormat === 'category' ? { interval: 0 } : { minTickGap: 20 })} />
              <YAxis width={width} {...valueAxisProps} />
            </>}
          {domain[0] < 0 && <ReferenceLine {...(horizontal ? { x: 0 } : { y: 0 })} stroke="var(--fg-faint)" />}
          <Tooltip cursor={{ fill: 'var(--bg-soft)' }} isAnimationActive={false} content={({ active, label, payload }) => {
            if (!active || payload.length === 0) return null;
            const row = payload[0]?.payload as Row | undefined;
            return <TooltipCard title={xFormat === 'day' ? formatDay(String(label), true) : String(label)} rows={visible.map((s) => ({
              key: s.key, label: s.label, color: colorOf(s, series.indexOf(s)),
              value: formatValue((row?.[s.rawKey ?? s.key] ?? null) as string | number | null, s.format ?? format, currency),
            }))} />;
          }} />
          {series.map((s, i) => <Bar key={s.key} dataKey={s.key} name={s.label} fill={colorOf(s, i)} hide={hidden.has(s.key)} isAnimationActive={false}
            maxBarSize={horizontal ? 18 : 36} radius={stacked ? 0 : horizontal ? [0, 3, 3, 0] : [3, 3, 0, 0]} {...(stacked ? { stackId: 'stack' } : {})}>
            {rowColors && data.map((_, r) => <Cell key={r} fill={rowColors[r] ?? colorOf(s, i)} />)}
          </Bar>)}
        </BarChart>
      </ResponsiveContainer>
    </div>
  </div>;
}
