// 7×24 day-of-week × hour-of-day heatmap. Pure SVG, RSC-safe.
// Input: cells with dow0 0=Mon..6=Sun and hr 0..23.

interface HeatmapCell { dow0: number; hr: number; txn_count: number }
interface HeatmapProps {
  cells: HeatmapCell[];
  label?: string;
}

const DOWS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

export function Heatmap({ cells, label = 'Transactions' }: HeatmapProps) {
  if (cells.length === 0) {
    return <div className="state-empty" style={{ height: 170, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>No data.</div>;
  }

  const grid: number[][] = Array.from({ length: 7 }, () => Array(24).fill(0));
  let max = 0;
  for (const c of cells) {
    const day = grid[c.dow0];
    if (!day || c.hr < 0 || c.hr > 23) continue;
    day[c.hr] = c.txn_count;
    if (c.txn_count > max) max = c.txn_count;
  }

  const CELL = 22;
  const GAP = 2;
  const LABEL_W = 36;
  const TOP_LABEL_H = 18;
  const width = LABEL_W + 24 * (CELL + GAP);
  const height = TOP_LABEL_H + 7 * (CELL + GAP);

  return (
    <div tabIndex={0} role="region" aria-label="Hourly activity; scroll horizontally for all 24 hours" style={{ overflowX: 'auto', maxWidth: '100%' }}>
      <svg width={width} height={height} role="img" aria-label={label} style={{ minWidth: width, display: 'block' }}>
        {Array.from({ length: 24 }, (_, h) => (
          <text key={`h${h}`} x={LABEL_W + h * (CELL + GAP) + CELL / 2} y={12} fontSize={9} fill="var(--fg-muted)" textAnchor="middle">
            {h % 2 === 0 ? h : ''}
          </text>
        ))}
        {DOWS.map((d, i) => (
          <text key={d} x={LABEL_W - 6} y={TOP_LABEL_H + i * (CELL + GAP) + CELL / 2 + 3} fontSize={10} fill="var(--fg-muted)" textAnchor="end">{d}</text>
        ))}
        {grid.flatMap((row, dow) =>
          row.map((v, hr) => {
            const norm = max > 0 ? v / max : 0;
            return (
              <rect
                key={`${dow}-${hr}`}
                x={LABEL_W + hr * (CELL + GAP)}
                y={TOP_LABEL_H + dow * (CELL + GAP)}
                width={CELL}
                height={CELL}
                rx={2}
                fill={v === 0 ? 'var(--bg-soft)' : 'var(--c-1)'}
                fillOpacity={v === 0 ? 1 : 0.15 + norm * 0.85}
              >
                <title>{`${DOWS[dow]} ${hr}:00 — ${v} txns`}</title>
              </rect>
            );
          })
        )}
      </svg>
    </div>
  );
}
