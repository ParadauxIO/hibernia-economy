// Tiny pure-SVG sparkline: no recharts, deterministic (SSR-safe), cheap in bulk
// (one per table row or metric). Renders a dash for fewer than two points.
// Decorative: put the meaning in nearby text or a delta.

interface MiniSparkProps {
  values: number[];
  width?: number | undefined;
  height?: number | undefined;
  stroke?: string | undefined;
  fill?: boolean | undefined;
  className?: string | undefined;
}

export function MiniSpark({ values, width = 120, height = 36, stroke = 'var(--accent)', fill = true, className }: MiniSparkProps) {
  if (values.length < 2) {
    return <span className="spark-empty muted small" aria-hidden="true">—</span>;
  }
  const min = Math.min(...values);
  const max = Math.max(...values);
  const span = max - min || 1;
  const pad = 2;
  const stepX = (width - pad * 2) / (values.length - 1);
  const pts = values.map((v, i) => [pad + i * stepX, pad + (height - pad * 2) * (1 - (v - min) / span)] as const);
  const line = pts.map(([x, y], i) => `${i === 0 ? 'M' : 'L'}${x.toFixed(1)},${y.toFixed(1)}`).join(' ');
  const area = `${line} L${(pad + (values.length - 1) * stepX).toFixed(1)},${height - pad} L${pad.toFixed(1)},${height - pad} Z`;
  return (
    <svg className={className ? `spark ${className}` : 'spark'} width={width} height={height} viewBox={`0 0 ${width} ${height}`} preserveAspectRatio="none" aria-hidden="true" focusable="false">
      {fill && <path d={area} fill={stroke} fillOpacity={0.1} />}
      <path d={line} fill="none" stroke={stroke} strokeWidth={1.5} strokeLinejoin="round" strokeLinecap="round" vectorEffect="non-scaling-stroke" />
    </svg>
  );
}
