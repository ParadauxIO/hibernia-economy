// Server-rendered candlestick chart. The plot is an SVG stretched to the frame
// width; axis labels are HTML positioned by percentage, so text stays legible
// at any width instead of shrinking with a viewBox.

import type { Candle } from '@/lib/derived';
import { CURRENCY_SYMBOL } from '@/lib/currency';
import { ChartDataTable, ChartEmpty, ChartFrame } from './ChartFrame';
import { axisWidth, formatTick, formatValue, niceTicks } from './kit';

interface CandlestickProps {
  candles: Candle[];
  height?: number | undefined;
  currency?: string | undefined;
}

const fmtBucket = (d: Date) => d.toISOString().slice(0, 16).replace('T', ' ');

export function Candlestick({ candles, height = 240, currency = CURRENCY_SYMBOL }: CandlestickProps) {
  if (candles.length === 0) return <ChartFrame><ChartEmpty height={height}>No trades in this window.</ChartEmpty></ChartFrame>;

  let min = Math.min(...candles.map((c) => c.low));
  let max = Math.max(...candles.map((c) => c.high));
  // Flat window: pad so the candles sit mid-chart instead of on the baseline.
  if (max - min < 1e-9) { const p = Math.max(0.5, Math.abs(max) * 0.04); min -= p; max += p; }
  const ticks = niceTicks(min, max, 5);
  const lo = ticks[0]!;
  const hi = ticks[ticks.length - 1]!;
  const labels = ticks.map((t) => formatTick(t, 'money', currency));
  const labelWidth = axisWidth(labels);
  const plotH = height - 24; // room for date labels
  const W = 1000; // viewBox width; stretched to the frame
  const y = (price: number) => plotH - ((price - lo) / (hi - lo)) * plotH;
  const slot = W / candles.length;
  const bodyW = Math.max(2, slot * 0.6);
  const every = Math.max(1, Math.ceil(candles.length / 6));
  const first = candles[0]!;
  const last = candles[candles.length - 1]!;
  const summary = `Price candles from ${fmtBucket(first.bucketStart)} to ${fmtBucket(last.bucketStart)} UTC: opened at ${formatValue(first.open, 'money', currency)}, closed at ${formatValue(last.close, 'money', currency)}, range ${formatValue(Math.min(...candles.map((c) => c.low)), 'money', currency)} to ${formatValue(Math.max(...candles.map((c) => c.high)), 'money', currency)}.`;

  return <ChartFrame height={height} note="Buckets in UTC. Filled candles closed lower than they opened."
    data={<ChartDataTable label="Price candles" columns={[{ label: 'Bucket (UTC)' }, { label: 'Open', numeric: true }, { label: 'High', numeric: true }, { label: 'Low', numeric: true }, { label: 'Close', numeric: true }, { label: 'Volume', numeric: true }]}
      rows={candles.map((c) => ({ key: c.bucketStart.toISOString(), cells: [fmtBucket(c.bucketStart), ...[c.open, c.high, c.low, c.close].map((v) => formatValue(v, 'money', currency)), formatValue(c.volume, 'count', currency)] }))} />}>
    <div className="candles" role="img" aria-label={summary} style={{ gridTemplateColumns: `${labelWidth}px minmax(0, 1fr)` }}>
      <div className="candles-y" style={{ height: plotH }}>
        {ticks.map((t, i) => <span key={t} style={{ top: `${(y(t) / plotH) * 100}%` }}>{labels[i]}</span>)}
      </div>
      <div className="candles-plot">
        <svg viewBox={`0 0 ${W} ${plotH}`} width="100%" height={plotH} preserveAspectRatio="none" aria-hidden="true" focusable="false">
          {ticks.map((t) => <line key={t} x1={0} x2={W} y1={y(t)} y2={y(t)} stroke="var(--border-soft)" vectorEffect="non-scaling-stroke" />)}
          {candles.map((c, i) => {
            const cx = slot * (i + 0.5);
            const up = c.close >= c.open;
            const color = up ? 'var(--pos)' : 'var(--neg)';
            const top = Math.min(y(c.open), y(c.close));
            return <g key={i}>
              <line x1={cx} x2={cx} y1={y(c.high)} y2={y(c.low)} stroke={color} vectorEffect="non-scaling-stroke" />
              <rect x={cx - bodyW / 2} y={top} width={bodyW} height={Math.max(2, Math.abs(y(c.open) - y(c.close)))} fill={up ? 'transparent' : color} stroke={color} vectorEffect="non-scaling-stroke">
                <title>{`${fmtBucket(c.bucketStart)}  O ${c.open.toFixed(2)} · H ${c.high.toFixed(2)} · L ${c.low.toFixed(2)} · C ${c.close.toFixed(2)} · Vol ${c.volume}`}</title>
              </rect>
            </g>;
          })}
        </svg>
        <div className="candles-x">
          {candles.map((c, i) => i % every === 0 ? <span key={i} style={{ left: `${((i + 0.5) / candles.length) * 100}%` }}>{c.bucketStart.toLocaleDateString('en-US', { month: 'short', day: 'numeric', timeZone: 'UTC' })}</span> : null)}
        </div>
      </div>
    </div>
  </ChartFrame>;
}
