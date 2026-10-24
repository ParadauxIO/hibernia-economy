// Tiny pure-SVG sparkline — no recharts, deterministic (SSR-safe), cheap to
// render in bulk (e.g. one per market card). Draws a polyline + soft area fill
// across the value range. Renders nothing for <2 points.

interface MiniSparkProps {
  values: number[];
  width?: number;
  height?: number;
  stroke?: string;
  fill?: boolean;
}

export function MiniSpark({ values, width = 120, height = 36, stroke = 'var(--accent)', fill = true }: MiniSparkProps) {
  if (values.length < 2) {
    return <div style={{ height, display: 'flex', alignItems: 'center' }}><span className="muted small">—</span></div>;
  }
  const min = Math.min(...values);
  const max = Math.max(...values);
  const span = max - min || 1;
  const pad = 2;
  const stepX = (width - pad * 2) / (values.length - 1);
  const pts = values.map((v, i) => {
    const x = pad + i * stepX;
    const y = pad + (height - pad * 2) * (1 - (v - min) / span);
    return [x, y] as const;
  });
  const line = pts.map(([x, y], i) => `${i === 0 ? 'M' : 'L'}${x.toFixed(1)},${y.toFixed(1)}`).join(' ');
  // values.length >= 2 above, so the first and last x are the padded edges.
  const area = `${line} L${(pad + (values.length - 1) * stepX).toFixed(1)},${height - pad} L${pad.toFixed(1)},${height - pad} Z`;

  return (
    <svg width={width} height={height} viewBox={`0 0 ${width} ${height}`} preserveAspectRatio="none" aria-hidden style={{ display: 'block' }}>
      {fill && <path d={area} fill={stroke} fillOpacity={0.12} />}
      <path d={line} fill="none" stroke={stroke} strokeWidth={1.5} strokeLinejoin="round" strokeLinecap="round" />
    </svg>
  );
}
