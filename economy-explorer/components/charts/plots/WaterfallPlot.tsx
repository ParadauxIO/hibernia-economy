'use client';

import { Bar, BarChart, CartesianGrid, Cell, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { AXIS_TICK, GRID_STROKE, measuredAxisWidth, TooltipCard } from '../client-kit';
import { formatTick, formatValue } from '../kit';

export interface WaterfallPlotRow {
  label: string;
  range: [number, number];
  /** Exact amount, DECIMAL string, for the tooltip. */
  raw: string;
  color: string;
  /** Drawn from the axis floor: show a break mark at its base. */
  cut: boolean;
  /** Which end of the bar is the cut one. */
  cutAt: 'bottom' | 'top';
}

export interface WaterfallPlotProps {
  data: WaterfallPlotRow[];
  domain: [number, number];
  ticks: number[];
  height: number;
  currency: string;
  summary: string;
}

interface ShapeProps { x?: number; y?: number; width?: number; height?: number; payload?: WaterfallPlotRow }

/** A bar; a cut one gets a zigzag across its axis end so its height doesn't read as its value. */
function WaterfallBarShape({ x = 0, y = 0, width = 0, height = 0, payload }: ShapeProps) {
  const fill = payload?.color ?? 'var(--cat-8)';
  if (!payload?.cut || height < 12) return <rect x={x} y={y} width={width} height={Math.max(height, 1)} fill={fill} rx={2} />;
  const zig = 5;
  const edge = payload.cutAt === 'bottom' ? y + height - 8 : y + 8;
  const steps = Math.max(2, Math.round(width / 8));
  const points: string[] = [];
  for (let i = 0; i <= steps; i++) points.push(`${x + (width * i) / steps},${edge + (i % 2 === 0 ? -zig / 2 : zig / 2)}`);
  return <g>
    <rect x={x} y={y} width={width} height={height} fill={fill} rx={2} />
    <polyline points={points.join(' ')} fill="none" stroke="var(--bg)" strokeWidth={3} />
  </g>;
}

export default function WaterfallPlot({ data, domain, ticks, height, currency, summary }: WaterfallPlotProps) {
  const labels = ticks.map((t) => formatTick(t, 'money', currency));
  const width = measuredAxisWidth(labels);
  return <div className="chart-body">
    <div className="chart-svg" role="img" aria-label={summary} style={{ height }}>
      <ResponsiveContainer width="100%" height={height}>
        <BarChart data={data} margin={{ top: 8, right: 4, bottom: 0, left: 0 }} barCategoryGap="22%" accessibilityLayer={false}>
          <CartesianGrid stroke={GRID_STROKE} vertical={false} />
          {/* Category names wrap onto two lines (Text wraps at `width`), so neighbours never overlap. */}
          <XAxis dataKey="label" tick={{ ...AXIS_TICK, width: 64 }} height={40} tickLine={false} axisLine={{ stroke: 'var(--border)' }} interval={0} />
          <YAxis width={width} ticks={ticks} domain={domain} allowDataOverflow interval={0} tick={AXIS_TICK} tickLine={false} axisLine={false}
            tickFormatter={(_v: number, i: number) => labels[i] ?? ''} />
          {domain[0] < 0 && domain[1] > 0 && <ReferenceLine y={0} stroke="var(--fg-faint)" />}
          <Tooltip cursor={{ fill: 'var(--bg-soft)' }} isAnimationActive={false} content={({ active, payload }) => {
            const row = payload[0]?.payload as WaterfallPlotRow | undefined;
            if (!active || !row) return null;
            return <TooltipCard title={row.label} rows={[{ key: 'v', label: 'Amount', color: row.color, value: formatValue(row.raw, 'money', currency) }]} />;
          }} />
          <Bar dataKey="range" isAnimationActive={false} maxBarSize={36} shape={(props: unknown) => <WaterfallBarShape {...(props as ShapeProps)} />}>
            {data.map((r, i) => <Cell key={i} fill={r.color} />)}
          </Bar>
        </BarChart>
      </ResponsiveContainer>
    </div>
  </div>;
}
