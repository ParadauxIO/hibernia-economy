// 7 × 24 weekday-by-hour heatmap. Server-rendered HTML grid: 24 equal columns
// that fill the card at every width (the old fixed 22px SVG used half the card
// on desktop and cut off after hour 11–21 on narrower screens).

import { formatNum } from '@democracycraft/design-system/format';
import { ChartDataTable, ChartEmpty, ChartFrame } from './ChartFrame';

interface HeatmapCell { dow0: number; hr: number; txn_count: number }

interface HeatmapProps {
  /** dow0: 0 = Monday … 6 = Sunday; hr: 0–23 (UTC). */
  cells: HeatmapCell[];
  /** Noun for the counts, plural ("Transactions"). */
  label?: string | undefined;
}

const DOWS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
const HOURS = Array.from({ length: 24 }, (_, h) => h);
const pad = (h: number) => `${String(h).padStart(2, '0')}:00`;

/** Sequential bucket 0–5 for a count (0 = none). */
export function heatLevel(value: number, max: number): number {
  if (value <= 0 || max <= 0) return 0;
  return Math.min(5, Math.max(1, Math.ceil((value / max) * 5)));
}

export function Heatmap({ cells, label = 'Transactions' }: HeatmapProps) {
  if (cells.length === 0) return <ChartFrame><ChartEmpty height={170} /></ChartFrame>;
  const grid: number[][] = Array.from({ length: 7 }, () => Array<number>(24).fill(0));
  for (const c of cells) {
    const day = grid[c.dow0];
    if (day && c.hr >= 0 && c.hr <= 23) day[c.hr] = c.txn_count;
  }
  const flat = grid.flatMap((row, d) => row.map((v, h) => ({ d, h, v })));
  const max = Math.max(0, ...flat.map((c) => c.v));
  const busiest = flat.reduce((a, b) => (b.v > a.v ? b : a));
  const noun = label.toLowerCase();
  const summary = `${label} by weekday and hour (UTC). Busiest: ${DOWS[busiest.d]} ${pad(busiest.h)}, ${formatNum(busiest.v)} ${noun}.`;

  return <ChartFrame note="Hours in UTC." data={<ChartDataTable label={`${label} by weekday and hour (UTC)`}
    columns={[{ label: 'Day' }, ...HOURS.map((h) => ({ label: pad(h), numeric: true }))]}
    rows={grid.map((row, d) => ({ key: DOWS[d]!, cells: [DOWS[d], ...row.map((v) => formatNum(v))] }))} />}
    legend={<div className="heat-legend" aria-hidden="true">Less{[0, 1, 2, 3, 4, 5].map((l) => <span key={l} className="heat-cell" data-level={l} />)}More</div>}>
    <div className="heat" role="img" aria-label={summary}>
      <span />
      {HOURS.map((h) => <span key={h} className="heat-hour">{h % 3 === 0 ? h : ''}</span>)}
      {grid.map((row, d) => [
        <span key={`d${d}`} className="heat-day">{DOWS[d]}</span>,
        ...row.map((v, h) => <span key={`${d}-${h}`} className="heat-cell" data-level={heatLevel(v, max)} title={`${DOWS[d]} ${pad(h)}: ${formatNum(v)} ${noun}`} />),
      ])}
    </div>
  </ChartFrame>;
}
