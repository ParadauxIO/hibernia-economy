'use client';
// Activity calendar (GitHub-style heatmap). Pure SVG; 52 weeks × 7 days of
// daily activity squares with an instant hover tooltip showing the day's count.

import { useRef, useState } from 'react';

export interface CalendarPoint { date: string; count: number }

interface ActivityCalendarProps {
  data: CalendarPoint[];
  /** Noun for the tooltip count, e.g. "transactions". */
  label?: string;
}

interface HoverState { iso: string; count: number; left: number; top: number }

const CELL = 11;
const GAP = 2;

export function ActivityCalendar({ data, label = 'transactions' }: ActivityCalendarProps) {
  const [hover, setHover] = useState<HoverState | null>(null);
  const scrollRef = useRef<HTMLDivElement>(null);

  if (data.length === 0) return null;

  const map = new Map<string, number>();
  for (const p of data) map.set(p.date, p.count);
  const maxCount = Math.max(1, ...data.map((p) => p.count));

  // 365 days ending today.
  const today = new Date();
  today.setUTCHours(0, 0, 0, 0);
  const days: { iso: string; count: number; col: number; row: number }[] = [];
  for (let i = 364; i >= 0; i--) {
    const d = new Date(today);
    d.setUTCDate(today.getUTCDate() - i);
    const iso = d.toISOString().slice(0, 10);
    const col = Math.floor((364 - i) / 7);
    const row = (d.getUTCDay() + 6) % 7; // Mon=0, Sun=6
    days.push({ iso, count: map.get(iso) ?? 0, col, row });
  }

  const cols = Math.ceil(days.length / 7);
  const width = cols * (CELL + GAP);
  const height = 7 * (CELL + GAP);

  function level(count: number) {
    if (count === 0) return 0;
    const norm = count / maxCount;
    if (norm < 0.25) return 1;
    if (norm < 0.5) return 2;
    if (norm < 0.75) return 3;
    return 4;
  }
  const colors = ['var(--bg-soft)', 'var(--c-1)', 'var(--c-1)', 'var(--c-1)', 'var(--c-1)'];
  const opacities = [1, 0.25, 0.5, 0.75, 1];

  const fmtDate = (iso: string) =>
    new Date(iso + 'T00:00:00Z').toLocaleDateString('en-US', { timeZone: 'UTC', month: 'short', day: 'numeric', year: 'numeric' });

  // The horizontal-scroll wrapper (overflowX: auto) also clips vertically —
  // CSS resolves the cross-axis `visible` to `auto` — so a tooltip rendered
  // *above* a top-row cell was cut off. Keep scrolling on the inner wrapper and
  // render the tooltip in the outer, overflow-visible box so it can escape
  // upward; offset its x by the wrapper's scrollLeft to track the visible cell.
  return (
    <div className="activity-calendar" style={{ position: 'relative', maxWidth: '100%' }}>
      {/* Scrolls horizontally on narrow screens: focusable so keyboards can too. */}
      <div ref={scrollRef} role="region" aria-label={label} tabIndex={0} className="scroll-region" style={{ overflowX: 'auto', maxWidth: '100%' }}>
        <svg width={width} height={height} role="img" aria-label={label} style={{ minWidth: width, display: 'block' }}>
          {days.map((d) => {
            const lv = level(d.count);
            return (
              <rect
                key={d.iso}
                x={d.col * (CELL + GAP)}
                y={d.row * (CELL + GAP)}
                width={CELL}
                height={CELL}
                rx={2}
                fill={colors[lv]}
                fillOpacity={opacities[lv]}
                onMouseEnter={() =>
                  setHover({
                    iso: d.iso,
                    count: d.count,
                    left: d.col * (CELL + GAP) + CELL / 2 - (scrollRef.current?.scrollLeft ?? 0),
                    top: d.row * (CELL + GAP),
                  })
                }
                onMouseLeave={() => setHover(null)}
              >
                <title>{`${d.iso}: ${d.count} ${label}`}</title>
              </rect>
            );
          })}
        </svg>
      </div>
      <p className="chart-subtitle">Daily activity in UTC.</p>
      {hover && (
        <div
          className="tooltip"
          style={{
            position: 'absolute',
            left: hover.left,
            top: hover.top,
            transform: 'translate(-50%, calc(-100% - 6px))',
            pointerEvents: 'none',
            whiteSpace: 'nowrap',
          }}
        >
          <div className="tooltip-title">{fmtDate(hover.iso)}</div>
          <div className="tooltip-row">
            <span className="label">{hover.count === 1 ? label.replace(/s$/, '') : label}</span>
            <span className="value">{hover.count.toLocaleString()}</span>
          </div>
        </div>
      )}
    </div>
  );
}
