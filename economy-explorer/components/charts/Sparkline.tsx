'use client';
import { useId } from 'react';
import { useHydrated } from '@/components/useHydrated';
import { Area, AreaChart, ResponsiveContainer } from 'recharts';

interface SparklineProps {
  data: { value: number }[] | number[];
  stroke?: string;
}

/**
 * Tiny inline sparkline rendered inside the absolutely-positioned `.kpi-spark`
 * slot of a KPI card. Always fills its container — the parent (`.kpi-spark`
 * in explorer.css) owns the 100×32px box.
 *
 * The gradient id uses React's useId() (stable across server + client) — a
 * Math.random() id mismatches on hydration and throws React #418, which on
 * the dashboard regenerated the tree and wiped <html data-mode>.
 */
export function Sparkline({ data, stroke = 'var(--accent)' }: SparklineProps) {
  const rawId = useId();
  const id = `spark-${rawId.replace(/:/g, '')}`;
  const mounted = useHydrated();
  const series = (typeof data[0] === 'number' ? (data as number[]).map((v) => ({ value: v })) : (data as { value: number }[]));
  if (series.length < 2) return null;
  // recharts measures the DOM → render nothing until mounted to avoid the
  // SSR/client hydration mismatch.
  if (!mounted) return null;
  return (
    <ResponsiveContainer width="100%" height="100%">
      <AreaChart data={series} margin={{ top: 1, right: 0, bottom: 1, left: 0 }}>
        <defs>
          <linearGradient id={id} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor={stroke} stopOpacity={0.35} />
            <stop offset="100%" stopColor={stroke} stopOpacity={0} />
          </linearGradient>
        </defs>
        <Area type="monotone" dataKey="value" stroke={stroke} strokeWidth={1.5} fill={`url(#${id})`} dot={false} isAnimationActive={false} />
      </AreaChart>
    </ResponsiveContainer>
  );
}
