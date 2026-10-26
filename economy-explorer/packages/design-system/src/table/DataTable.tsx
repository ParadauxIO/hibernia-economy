import type { ComponentType, ReactNode } from 'react';
import './table.css';

export type SortDirection = 'asc' | 'desc';
export type Density = 'comfortable' | 'compact';

/** Anything that renders an anchor: a plain `a`, or the host's router link (Next `Link`). */
export type LinkComponent = ComponentType<{ href: string; className?: string; children?: ReactNode; prefetch?: boolean; 'aria-label'?: string }>;

export interface Column<R> {
  /** CSS-safe key. Targets the Columns menu (`data-col`) and React keys. */
  key: string;
  /** Header content. A string doubles as the label used on phones and in the Columns menu. */
  header: ReactNode;
  /** Plain-text label when `header` isn't a string. */
  label?: string;
  cell: (row: R, index: number) => ReactNode;
  align?: 'start' | 'center' | 'end';
  /** Right-aligned, tabular figures, never wraps. */
  numeric?: boolean;
  /** Hidden under 720 px; shown in the row's expandable details instead. */
  secondary?: boolean;
  /** Can be hidden from the Columns menu. */
  optional?: boolean;
  /** Sort key this header toggles. */
  sort?: string;
  /** Direction of the first click on an unsorted column. Numeric columns default to descending. */
  sortFirst?: SortDirection;
  footer?: ReactNode;
  className?: string;
}

export interface DataTableProps<R> {
  /** DOM id; the Columns menu scopes its rules to it. */
  id: string;
  /** Accessible name for the scroll region and the table caption. */
  label: string;
  columns: readonly Column<R>[];
  rows: readonly R[];
  rowKey: (row: R) => string | number;
  /** Makes the first cell a real link and the whole row clickable. */
  rowHref?: (row: R) => string;
  /** Accessible name for a row link when the first cell's text isn't enough. */
  rowLabel?: (row: R) => string;
  sort?: { field: string; dir: SortDirection };
  /** URL for sorting by `field` in `dir`. Required for sortable headers to render as links. */
  sortHref?: (field: string, dir: SortDirection) => string;
  density?: Density;
  /** Rendered in a full-width row when `rows` is empty. See `TableEmpty`. */
  empty?: ReactNode;
  link?: LinkComponent;
  /** Label for the phone-only details toggle. */
  detailsLabel?: string;
}

const PlainLink: LinkComponent = ({ href, className, children, 'aria-label': ariaLabel }) => (
  <a href={href} className={className} aria-label={ariaLabel}>{children}</a>
);

function textOf<R>(c: Column<R>): string {
  return c.label ?? (typeof c.header === 'string' ? c.header : c.key);
}

function cellClass<R>(c: Column<R>, extra?: string): string {
  return [
    c.numeric ? 'dt-num' : '',
    c.align === 'end' ? 'dt-end' : c.align === 'center' ? 'dt-center' : '',
    c.secondary ? 'dt-secondary' : '',
    c.className ?? '',
    extra ?? '',
  ].filter(Boolean).join(' ');
}

function SortHeader<R>({ column, sort, sortHref, Link }: { column: Column<R>; sort: DataTableProps<R>['sort']; sortHref: NonNullable<DataTableProps<R>['sortHref']>; Link: LinkComponent }) {
  const key = column.sort as string;
  const active = sort?.field === key;
  const first: SortDirection = column.sortFirst ?? (column.numeric ? 'desc' : 'asc');
  const next: SortDirection = active ? (sort.dir === 'desc' ? 'asc' : 'desc') : first;
  return (
    <Link href={sortHref(key, next)} className={`dt-sort${active ? ' is-active' : ''}`} prefetch={false}>
      <span>{column.header}</span>
      <span className="dt-sort-icon" aria-hidden="true">{active ? (sort.dir === 'desc' ? '↓' : '↑') : '↕'}</span>
      <span className="sr-only">{`, sort ${next === 'asc' ? 'ascending' : 'descending'}`}</span>
    </Link>
  );
}

/**
 * Server-rendered data table: real links for sorting and rows, `aria-sort` on
 * the sorted column, a sticky header inside a focusable scroll region, an empty
 * row, optional totals footer, and a phone layout where secondary columns fold
 * into an expandable details block under the first cell.
 */
export function DataTable<R>({
  id, label, columns, rows, rowKey, rowHref, rowLabel, sort, sortHref,
  density = 'comfortable', empty, link, detailsLabel = 'Details',
}: DataTableProps<R>) {
  const Link = link ?? PlainLink;
  const secondary = columns.filter((c) => c.secondary);
  const hasFooter = columns.some((c) => c.footer !== undefined);

  return (
    <div className="dt-wrap" role="region" aria-label={label} tabIndex={0}>
      <table id={id} className={`dt dt-table dt--${density}`}>
        <caption className="sr-only">{label}</caption>
        <thead>
          <tr>
            {columns.map((c) => {
              const sorted = c.sort !== undefined && sort?.field === c.sort;
              return (
                <th
                  key={c.key}
                  scope="col"
                  data-col={c.key}
                  className={cellClass(c)}
                  aria-sort={sorted ? (sort.dir === 'asc' ? 'ascending' : 'descending') : undefined}
                >
                  {c.sort !== undefined && sortHref ? <SortHeader column={c} sort={sort} sortHref={sortHref} Link={Link} /> : c.header}
                </th>
              );
            })}
          </tr>
        </thead>
        <tbody>
          {rows.length === 0 ? (
            <tr className="dt-empty-row">
              <td colSpan={columns.length}>{empty ?? <p className="dt-empty-text">Nothing to show.</p>}</td>
            </tr>
          ) : rows.map((row, i) => {
            const href = rowHref?.(row);
            return (
              <tr key={rowKey(row)} className={href ? 'dt-linked' : undefined}>
                {columns.map((c, ci) => {
                  const content = c.cell(row, i);
                  if (ci !== 0) return <td key={c.key} data-col={c.key} className={cellClass(c)}>{content}</td>;
                  const ariaLabel = rowLabel?.(row);
                  return (
                    <td key={c.key} data-col={c.key} className={cellClass(c, 'dt-primary')}>
                      {href ? <Link href={href} className="dt-rowlink" prefetch={false} {...(ariaLabel ? { 'aria-label': ariaLabel } : {})}>{content}</Link> : content}
                      {secondary.length > 0 && (
                        <details className="dt-more">
                          <summary>{detailsLabel}</summary>
                          <dl>
                            {secondary.map((s) => (
                              <div key={s.key} data-col={s.key}>
                                <dt>{textOf(s)}</dt>
                                <dd>{s.cell(row, i)}</dd>
                              </div>
                            ))}
                          </dl>
                        </details>
                      )}
                    </td>
                  );
                })}
              </tr>
            );
          })}
        </tbody>
        {hasFooter && rows.length > 0 && (
          <tfoot>
            <tr>
              {columns.map((c) => <td key={c.key} data-col={c.key} className={cellClass(c)}>{c.footer}</td>)}
            </tr>
          </tfoot>
        )}
      </table>
    </div>
  );
}

/** The parts of a column the client Columns menu needs (no render functions). */
export interface ColumnOption { key: string; label: string; optional: boolean }

export function columnOptions<R>(columns: readonly Column<R>[]): ColumnOption[] {
  return columns.map((c) => ({ key: c.key, label: textOf(c), optional: c.optional ?? false }));
}

/**
 * Empty-results content: says what's filtering the table and offers links to
 * drop the most recent filter or all of them.
 */
export function TableEmpty({ noun, filters, clearHref, link }: {
  /** Plural noun: "accounts". */
  noun: string;
  /** Active filters, most recently added last, each with a link that removes it. */
  filters: readonly { text: string; removeHref: string }[];
  clearHref?: string;
  link?: LinkComponent;
}) {
  const Link = link ?? PlainLink;
  if (filters.length === 0) return <p className="dt-empty-text">No {noun} yet.</p>;
  const last = filters[filters.length - 1] as { text: string; removeHref: string };
  return (
    <div className="dt-empty">
      <p className="dt-empty-text">
        No {noun} match {filters.map((f, i) => (
          <span key={f.text}>
            {i > 0 && (i === filters.length - 1 ? ' and ' : ', ')}
            <strong>{f.text}</strong>
          </span>
        ))}.
      </p>
      <p className="dt-empty-actions">
        <Link href={last.removeHref} className="dt-empty-link">Remove {last.text}</Link>
        {filters.length > 1 && clearHref && <Link href={clearHref} className="dt-empty-link">Clear all filters</Link>}
      </p>
    </div>
  );
}
