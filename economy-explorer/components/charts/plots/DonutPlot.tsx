'use client';

import { Cell, Pie, PieChart, ResponsiveContainer, Tooltip } from 'recharts';
import { TooltipCard } from '../client-kit';
import { formatValue, type ValueFormat } from '../kit';

export interface DonutSlice {
  key: string;
  label: string;
  /** Plotted magnitude (positive). */
  value: number;
  /** Exact value for the tooltip (DECIMAL string for money). */
  raw: string | number;
  color: string;
  /** Accessible name of the slice, e.g. "BUSINESS: $61,022,781.54, 1,212 accounts". */
  description: string;
}

export interface DonutPlotProps {
  slices: DonutSlice[];
  height: number;
  format: ValueFormat;
  currency: string;
  summary: string;
}

export default function DonutPlot({ slices, height, format, currency, summary }: DonutPlotProps) {
  const outer = Math.min(88, height / 2 - 8);
  return <div className="chart-body">
    <div className="chart-svg" role="group" aria-label={summary} style={{ height }}>
      <ResponsiveContainer width="100%" height={height}>
        <PieChart accessibilityLayer={false}>
          <Pie data={slices} dataKey="value" nameKey="label" cx="50%" cy="50%" innerRadius={outer * 0.68} outerRadius={outer} paddingAngle={1.5} stroke="var(--bg-card)" strokeWidth={2} isAnimationActive={false}>
            {slices.map((s) => <Cell key={s.key} fill={s.color} role="img" aria-label={s.description} />)}
          </Pie>
          <Tooltip isAnimationActive={false} content={({ active, payload }) => {
            const slice = active ? (payload[0]?.payload as DonutSlice | undefined) : undefined;
            return slice ? <TooltipCard title={slice.label} rows={[{ key: slice.key, label: 'Total', color: slice.color, value: formatValue(slice.raw, format, currency) }]} /> : null;
          }} />
        </PieChart>
      </ResponsiveContainer>
    </div>
  </div>;
}
