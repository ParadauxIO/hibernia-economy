'use client';

import { useMemo, useState } from 'react';
import { Area, AreaChart, CartesianGrid, Line, LineChart, ReferenceDot, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { AXIS_TICK, GRID_STROKE, Legend, measuredAxisWidth, TooltipCard, useSeriesToggle } from '../client-kit';
import {
  AXIS_TITLES_HEIGHT, colorOf, CURVE, formatDay, formatTick, formatValue, logTicks, niceTicks, symlog, timeSeriesLayout, toNumber, TOOLBAR_HEIGHT, valueAxis,
  type Row, type Series, type ValueFormat,
} from '../kit';

export interface TimeSeriesPlotProps {
  data: Row[];
  /** Key of the YYYY-MM-DD bucket in each row. */
  x: string;
  series: Series[];
  kind: 'area' | 'line';
  stacked: boolean;
  /** Height of the plot itself; chrome rows are added on top (kit.totalHeight). */
  height: number;
  currency: string;
  /** Accessible summary of what the chart shows. */
  summary: string;
  outliers: boolean;
}

type Side = 'left' | 'right';
const RAW = '__raw_';

export default function TimeSeriesPlot({ data, x, series, kind, stacked, height, currency, summary, outliers }: TimeSeriesPlotProps) {
  const { hidden, toggle } = useSeriesToggle(series);
  const [log, setLog] = useState(false);
  const layout = useMemo(() => timeSeriesLayout(data, series, outliers && !stacked), [data, series, outliers, stacked]);
  const clamp = log ? null : layout.clamp;
  const primary = series.find((s) => (s.axis ?? 'left') === 'left') ?? series[0];

  const rows = useMemo(() => data.map((row, i) => {
    const out: Record<string, number | string | number[] | null> ={ [x]: String(row[x] ?? '') };
    for (const s of series) {
      const v = toNumber(row[s.key]);
      out[RAW + s.key] = row[s.key] ?? null;
      let plotted = v;
      if (v !== null && log) plotted = symlog(v);
      else if (v !== null && clamp && s.key === primary?.key && clamp.clipped.includes(i)) plotted = clamp.cap;
      out[s.key] = plotted;
    }
    return out;
  }), [data, series, x, log, clamp, primary]);

  const axes = (['left', 'right'] as Side[]).map((side) => {
    const onSide = series.filter((s) => (s.axis ?? 'left') === side);
    const visible = onSide.filter((s) => !hidden.has(s.key));
    if (onSide.length === 0) return null;
    const format: ValueFormat = onSide[0]!.format ?? 'money';
    const raw = visible.flatMap((s) => data.map((r) => toNumber(r[s.key])).filter((v): v is number => v !== null));
    const stackedRaw = stacked ? data.map((r) => visible.reduce((sum, s) => sum + Math.max(0, toNumber(r[s.key]) ?? 0), 0)) : raw;
    let ticks: number[];
    let labels: string[];
    if (log) {
      const lt = logTicks(Math.max(1, ...stackedRaw.map(Math.abs)));
      ticks = lt.ticks;
      labels = lt.values.map((v) => formatTick(v, format, currency));
    } else if (clamp && side === (primary?.axis ?? 'left')) {
      ticks = niceTicks(Math.min(0, ...stackedRaw), clamp.cap, 5);
      labels = ticks.map((t) => formatTick(t, format, currency));
    } else {
      ticks = valueAxis(stackedRaw).ticks;
      labels = ticks.map((t) => formatTick(t, format, currency));
    }
    const color = onSide.length === 1 && series.length > 1 ? colorOf(onSide[0]!, series.indexOf(onSide[0]!)) : 'var(--fg-muted)';
    return { side, format, ticks, labels, color, width: measuredAxisWidth(labels), hide: visible.length === 0, title: onSide.map((s) => s.label).join(', ') };
  }).filter((a): a is NonNullable<typeof a> => a !== null);

  const Chart = kind === 'area' ? AreaChart : LineChart;
  const dual = layout.axisTitles;

  return <div className="chart-body">
    {layout.toolbar && <div className="chart-toolbar" style={{ height: TOOLBAR_HEIGHT }}>
      <Legend series={series} hidden={hidden} onToggle={toggle} />
      {layout.clamp && <div className="chart-scale">
        {!log && <span className="chart-scale-note">{layout.clamp.clipped.length === 1 ? '1 day is' : `${layout.clamp.clipped.length} days are`} above the scale <span aria-hidden="true">▲</span></span>}
        <button type="button" className="btn btn-quiet chart-toggle" aria-pressed={log} onClick={() => setLog(!log)}>Log scale</button>
      </div>}
    </div>}
    {dual && <div className="chart-axis-titles" style={{ height: AXIS_TITLES_HEIGHT }} aria-hidden="true">
      {axes.map((a) => <span key={a.side} className={`chart-axis-title is-${a.side}`} style={{ color: a.color }}>{a.hide ? '' : a.title}</span>)}
    </div>}
    <div className="chart-svg" role="img" aria-label={summary} style={{ height }}>
      <ResponsiveContainer width="100%" height={height}>
        <Chart data={rows} margin={{ top: 8, right: 4, bottom: 0, left: 0 }} accessibilityLayer={false}>
          <CartesianGrid stroke={GRID_STROKE} vertical={false} />
          <XAxis dataKey={x} tickFormatter={(d: string) => formatDay(d)} tick={AXIS_TICK} tickLine={false} axisLine={{ stroke: 'var(--border)' }} minTickGap={28} />
          {axes.map((a) => <YAxis key={a.side} yAxisId={a.side} orientation={a.side} hide={a.hide} width={a.width} ticks={a.ticks} domain={[a.ticks[0]!, a.ticks[a.ticks.length - 1]!]}
            allowDataOverflow tickFormatter={(_v: number, i: number) => a.labels[i] ?? ''} interval={0} tick={{ ...AXIS_TICK, fill: a.color }} tickLine={false} axisLine={false} />)}
          <Tooltip cursor={{ stroke: 'var(--border)' }} isAnimationActive={false} content={({ active, label, payload }) => {
            if (!active || payload.length === 0) return null;
            const row = payload[0]?.payload as Record<string, unknown> | undefined;
            const index = rows.indexOf(row as (typeof rows)[number]);
            return <TooltipCard title={formatDay(String(label), true)} rows={series.filter((s) => !hidden.has(s.key)).map((s) => ({
              key: s.key,
              label: s.label,
              color: colorOf(s, series.indexOf(s)),
              value: formatValue(row?.[RAW + s.key] as string | number | null, s.format ?? 'money', currency),
              note: clamp && s.key === primary?.key && clamp.clipped.includes(index) ? 'above scale' : undefined,
            }))} />;
          }} />
          {series.map((s, i) => {
            const common = {
              yAxisId: s.axis ?? 'left', dataKey: s.key, name: s.label, type: CURVE, stroke: colorOf(s, i),
              // One or two days can't draw a line or area; show the points instead.
              strokeWidth: 1.75, dot: data.length <= 2 ? { r: 3, strokeWidth: 0, fill: colorOf(s, i) } : false, activeDot: { r: 3, strokeWidth: 0 }, isAnimationActive: false, hide: hidden.has(s.key),
              ...(stacked ? { stackId: 'stack' } : {}),
            };
            return kind === 'area'
              ? <Area key={s.key} {...common} fill={colorOf(s, i)} fillOpacity={stacked ? 0.35 : 0.08} />
              : <Line key={s.key} {...common} />;
          })}
          {clamp && primary && !hidden.has(primary.key) && clamp.clipped.map((i) => <ReferenceDot key={i} yAxisId={primary.axis ?? 'left'} x={String(data[i]?.[x])} y={clamp.cap} ifOverflow="visible"
            shape={(p: { cx?: number; cy?: number }) => <g className="chart-outlier" data-outlier="true">
              <path d={`M${(p.cx ?? 0) - 5},${(p.cy ?? 0) + 4} L${p.cx ?? 0},${(p.cy ?? 0) - 4} L${(p.cx ?? 0) + 5},${(p.cy ?? 0) + 4} Z`} fill={colorOf(primary, series.indexOf(primary))} />
              <text x={p.cx} y={(p.cy ?? 0) + 18} textAnchor="middle" fontSize={11} fill="var(--fg-soft)">{formatTick(toNumber(data[i]?.[primary.key]) ?? 0, primary.format ?? 'money', currency)}</text>
            </g>} />)}
        </Chart>
      </ResponsiveContainer>
    </div>
  </div>;
}
