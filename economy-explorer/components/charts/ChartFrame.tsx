// The frame every chart shares: optional title and description, a plot area at
// a fixed height (so the server skeleton and the loaded chart are the same
// size), notes, and a "View chart data" table. Server component.

import './charts.css';
import type { ReactNode } from 'react';
import { EmptyState, TableWrap } from '@democracycraft/design-system';

export interface ChartFrameProps {
  title?: ReactNode | undefined;
  description?: ReactNode | undefined;
  /** Controls for this chart, top right. */
  actions?: ReactNode | undefined;
  /** Height of the plot area including the legend / axis-title rows. Omit for self-sizing charts (heatmap). */
  height?: number | undefined;
  /** Static legend under the plot (donut). Interactive legends live inside the plot. */
  legend?: ReactNode | undefined;
  /** "Daily buckets in UTC." and similar. */
  note?: ReactNode | undefined;
  /** The data table shown under "View chart data". */
  data?: ReactNode | undefined;
  children: ReactNode;
}

export function ChartFrame({ title, description, actions, height, legend, note, data, children }: ChartFrameProps) {
  return <figure className="chart-frame">
    {(title != null || description != null || actions != null) && <figcaption className="chart-frame-header">
      <div className="chart-frame-titles">
        {title != null && <div className="chart-frame-title">{title}</div>}
        {description != null && <div className="chart-frame-description">{description}</div>}
      </div>
      {actions != null && <div className="chart-frame-actions">{actions}</div>}
    </figcaption>}
    {height !== undefined ? <div className="chart-plot" style={{ height }}>{children}</div> : children}
    {legend}
    {note != null && <p className="chart-note">{note}</p>}
    {data != null && <details className="chart-data"><summary>View chart data</summary>{data}</details>}
  </figure>;
}

/** The accessible table behind a chart. Cells arrive formatted. */
export function ChartDataTable({ label, columns, rows }: { label: string; columns: { label: string; numeric?: boolean }[]; rows: { key: string; cells: ReactNode[] }[] }) {
  return <TableWrap label={label}>
    <table className="data-table" aria-label={label}>
      <thead><tr>{columns.map((c) => <th key={c.label} scope="col" className={c.numeric ? 'amount' : undefined}>{c.label}</th>)}</tr></thead>
      <tbody>{rows.map((r) => <tr key={r.key}>{r.cells.map((cell, i) => i === 0
        ? <th key={i} scope="row">{cell}</th>
        : <td key={i} className={columns[i]?.numeric ? 'amount' : undefined}>{cell}</td>)}</tr>)}</tbody>
    </table>
  </TableWrap>;
}

/** Shown in place of the plot when there is no data, at the plot's height. */
export function ChartEmpty({ height, children }: { height: number; children?: ReactNode }) {
  return <div className="chart-empty" style={{ height }}><EmptyState compact>{children ?? 'No data in this period.'}</EmptyState></div>;
}
