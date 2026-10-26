// Simple force-laid-out money-flow diagram. Pure SVG fallback for a Sankey —
// type nodes laid out in a circle with arc edges weighted by amount. Renders
// in RSC without recharts.

import './charts.css';
import { CURRENCY_SYMBOL } from '@/lib/currency';

interface Edge { from_type: string; to_type: string; amount: string; amount_ex_seed?: string; txn_count: number }

// Magnitude used for the diagram: organic flow only (starting-balance seeding
// excluded). Falls back to the full amount when the seed-adjusted value is
// absent (e.g. callers that don't compute it).
function flowAmount(e: Edge): number {
  return parseFloat(e.amount_ex_seed ?? e.amount);
}

const TYPE_COLOR: Record<string, string> = {
  PERSONAL: 'var(--c-1)',
  BUSINESS: 'var(--c-3)',
  GOVERNMENT: 'var(--c-4)',
  SYSTEM: 'var(--c-2)',
};

export function MoneyFlowDiagram({ edges }: { edges: Edge[] }) {
  // Only draw edges that carry organic (non-seed) flow; a pair that is purely
  // account seeding would otherwise render as a zero-width, invisible arc.
  const drawn = edges.filter((e) => flowAmount(e) > 0);
  if (drawn.length === 0) {
    return <div className="state-empty" style={{ height: 220, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>No cross-type flows.</div>;
  }

  const types = Array.from(new Set(drawn.flatMap((e) => [e.from_type, e.to_type])));
  const max = Math.max(...drawn.map(flowAmount));

  const W = 600;
  const H = 320;
  const cx = W / 2;
  const cy = H / 2;
  const r = Math.min(W, H) / 2 - 60;

  const pos = new Map<string, { x: number; y: number }>();
  types.forEach((t, i) => {
    const angle = (i / types.length) * Math.PI * 2 - Math.PI / 2;
    pos.set(t, { x: cx + Math.cos(angle) * r, y: cy + Math.sin(angle) * r });
  });

  return (
    <svg viewBox={`0 0 ${W} ${H}`} width="100%" height={H} role="img" aria-label="Money flow">
      <defs>
        {/* markerUnits=userSpaceOnUse: keep the arrowhead a FIXED size. The
            default (strokeWidth) scaled it by the edge weight (up to ~15×),
            producing a giant stray triangle on the thickest flows. */}
        <marker id="arrow" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="9" markerHeight="9" markerUnits="userSpaceOnUse" orient="auto">
          <path d="M0,0 L10,5 L0,10 Z" fill="var(--fg-muted)" />
        </marker>
      </defs>
      {drawn.map((e, i) => {
        const a = pos.get(e.from_type)!;
        const b = pos.get(e.to_type)!;
        // sqrt (not linear) so a dominant edge doesn't flatten the rest to
        // hairlines — small flows stay legibly visible.
        const norm = Math.sqrt(flowAmount(e) / max);
        const sw = 1 + norm * 14;
        // Slight curve.
        const mx = (a.x + b.x) / 2;
        const my = (a.y + b.y) / 2;
        const dx = b.x - a.x;
        const dy = b.y - a.y;
        const len = Math.sqrt(dx * dx + dy * dy);
        const nx = -dy / len;
        const ny = dx / len;
        const offset = 24;
        const cx1 = mx + nx * offset;
        const cy1 = my + ny * offset;
        return (
          <path
            key={i}
            d={`M ${a.x} ${a.y} Q ${cx1} ${cy1} ${b.x} ${b.y}`}
            stroke={TYPE_COLOR[e.from_type] ?? 'var(--fg-muted)'}
            strokeOpacity={0.15 + norm * 0.6}
            strokeWidth={sw}
            fill="none"
            markerEnd="url(#arrow)"
          />
        );
      })}
      {types.map((t) => {
        const p = pos.get(t)!;
        return (
          <g key={t}>
            <circle cx={p.x} cy={p.y} r={18} fill={TYPE_COLOR[t] ?? 'var(--fg-muted)'} stroke="var(--bg-card)" strokeWidth={3} />
            <text x={p.x} y={p.y + 4} textAnchor="middle" fontSize={11} fill="var(--bg)" fontWeight={600}>{t.slice(0, 3)}</text>
            <text x={p.x} y={p.y + 36} textAnchor="middle" fontSize={10} fill="var(--fg-soft)">{t}</text>
          </g>
        );
      })}
    </svg>
  );
}

export function MoneyFlowLegend({ edges }: { edges: Edge[] }) {
  if (edges.length === 0) return null;
  return (
    <div className="chart-key-inline">
      {edges.slice(0, 8).map((e, i) => (
        <span key={i}>
          <span className={`badge badge-${e.from_type}`}>{e.from_type}</span>{' → '}
          <span className={`badge badge-${e.to_type}`}>{e.to_type}</span>{' '}
          {CURRENCY_SYMBOL}{Math.round(parseFloat(e.amount)).toLocaleString()}
        </span>
      ))}
    </div>
  );
}
