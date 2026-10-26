'use client';
// Client pieces shared by the recharts plots: legend toggles, tooltip, text
// measurement. Imported only by plots/*, so it ships in the lazy chart chunk.

import { useCallback, useMemo, useState, type ReactNode } from 'react';
import { useMediaQuery } from '@/components/useMediaQuery';
import { AXIS_FONT_PX, axisWidth, colorOf, estimateTextWidth, type Series } from './kit';

let canvas: HTMLCanvasElement | null = null;

/** Measure text in the page's real font, falling back to the estimate (jsdom has no canvas). */
export function measureText(text: string, fontPx = AXIS_FONT_PX): number {
  try {
    canvas ??= document.createElement('canvas');
    const ctx = canvas.getContext('2d');
    if (!ctx) return estimateTextWidth(text, fontPx);
    ctx.font = `${fontPx}px ${getComputedStyle(document.body).fontFamily}`;
    // Tabular figures are a touch wider than the canvas default; keep the larger.
    return Math.max(ctx.measureText(text).width, estimateTextWidth(text, fontPx) * 0.9);
  } catch {
    return estimateTextWidth(text, fontPx);
  }
}

export function measuredAxisWidth(labels: string[]): number {
  return axisWidth(labels, AXIS_FONT_PX, (s) => measureText(s));
}

/** Visible-series state. Secondary series marked hideOnNarrow start hidden on phones. */
export function useSeriesToggle(series: Series[]) {
  const narrow = useMediaQuery('(max-width: 720px)');
  const [choice, setChoice] = useState<Set<string> | null>(null);
  const hidden = useMemo(() => choice ?? new Set(narrow ? series.filter((s) => s.hideOnNarrow).map((s) => s.key) : []), [choice, narrow, series]);
  const toggle = useCallback((key: string) => {
    const next = new Set(hidden);
    if (next.has(key)) next.delete(key);
    else if (series.length - next.size > 1) next.add(key); // never hide the last visible series
    setChoice(next);
  }, [hidden, series.length]);
  return { hidden, toggle };
}

/** Same place on every chart: top left. Items are toggle buttons. */
export function Legend({ series, hidden, onToggle, axisNotes = false }: { series: Series[]; hidden: Set<string>; onToggle: (key: string) => void; axisNotes?: boolean }) {
  if (series.length < 2) return <span />;
  return <div className="chart-legend" role="group" aria-label="Series shown">
    {series.map((s, i) => <button key={s.key} type="button" className="chart-legend-item" aria-pressed={!hidden.has(s.key)} onClick={() => onToggle(s.key)}>
      <span className="chart-swatch" style={{ background: colorOf(s, i) }} aria-hidden="true" />
      {s.label}{axisNotes && <span className="chart-legend-axis"> · {(s.axis ?? 'left') === 'left' ? 'left axis' : 'right axis'}</span>}
    </button>)}
  </div>;
}

export interface TooltipRow { key: string; label: string; color: string; value: string; note?: string | undefined }

/** Tooltip body. Recharts positions it; the content is ours. */
export function TooltipCard({ title, rows }: { title: ReactNode; rows: TooltipRow[] }) {
  if (rows.length === 0) return null;
  return <div className="chart-tooltip">
    <div className="chart-tooltip-title">{title}</div>
    {rows.map((r) => <div key={r.key} className="chart-tooltip-row">
      <span className="chart-tooltip-label"><span className="chart-swatch" style={{ background: r.color }} aria-hidden="true" />{r.label}</span>
      <span className="chart-tooltip-value">{r.value}{r.note && <span className="chart-tooltip-note">{r.note}</span>}</span>
    </div>)}
  </div>;
}

export const GRID_STROKE = 'var(--border-soft)';
export const AXIS_TICK = { fontSize: AXIS_FONT_PX, fill: 'var(--fg-muted)' };
