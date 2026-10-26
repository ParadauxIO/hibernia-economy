import type { ReactNode } from 'react';
import { cx } from './cx';

export interface EmptyStateProps {
  title?: ReactNode | undefined;
  /** Body text, or legacy children. */
  children?: ReactNode | undefined;
  /** A link or button that gets the reader somewhere useful ("Clear filters"). */
  action?: ReactNode | undefined;
  /** Tighter padding, for use inside a table or chart. */
  compact?: boolean | undefined;
  id?: string | undefined;
}

/** Nothing to show. Says why and offers the next step. */
export function EmptyState({ title, children, action, compact = false, id }: EmptyStateProps) {
  return <div id={id} className={cx('state-empty', compact && 'is-compact')}>
    {title != null && <p className="state-title">{title}</p>}
    {children != null && <div className="state-body">{children}</div>}
    {action != null && <div className="state-action">{action}</div>}
  </div>;
}

export interface ErrorStateProps {
  title?: ReactNode | undefined;
  children?: ReactNode | undefined;
  /** Retry button or a route away. Error boundaries pass their reset handler here. */
  action?: ReactNode | undefined;
  /** A reference the reader can quote (error digest). */
  reference?: string | undefined;
}

/** Something failed. Plain language, a way to retry, never framework prose. */
export function ErrorState({ title = 'This part of the page didn’t load', children, action, reference }: ErrorStateProps) {
  return <div className="state-error" role="alert">
    <p className="state-title">{title}</p>
    {children != null && <div className="state-body">{children}</div>}
    {action != null && <div className="state-action">{action}</div>}
    {reference && <p className="state-ref">Reference <code>{reference}</code></p>}
  </div>;
}

export type AccessGateKind = 'login' | 'private' | 'link' | 'request';

export interface AccessGateProps {
  kind: AccessGateKind;
  title: ReactNode;
  description?: ReactNode | undefined;
  /**
   * The host's call to action: a login form (server action), a link to /link, or
   * a link explaining how to request access. The package has no auth.
   */
  action?: ReactNode | undefined;
  /** For kind="request": the relationships that would grant access, in plain words. */
  grants?: ReactNode[] | undefined;
  /** Inline variant for a gated section inside an otherwise public page. */
  inline?: boolean | undefined;
}

function LockGlyph() {
  return <svg className="gate-glyph" width="16" height="16" viewBox="0 0 16 16" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round">
    <rect x="3" y="7" width="10" height="7" rx="1.5" /><path d="M5.5 7V5a2.5 2.5 0 0 1 5 0v2" />
  </svg>;
}

/**
 * Private content the viewer can't see, and how they could. Replaces PrivacyGate;
 * adds kind="request", which lists the relationships that grant access.
 */
export function AccessGate({ kind, title, description, action, grants, inline = false }: AccessGateProps) {
  return <div className={cx('access-gate', inline && 'is-inline')} data-kind={kind}>
    <p className="state-title"><LockGlyph />{title}</p>
    {description != null && <div className="state-body">{description}</div>}
    {kind === 'request' && grants && grants.length > 0 && <div className="access-grants">
      <p className="access-grants-label">You can see this if you are:</p>
      <ul>{grants.map((g, i) => <li key={i}>{g}</li>)}</ul>
    </div>}
    {action != null && <div className="state-action">{action}</div>}
  </div>;
}

/* ───────────── skeletons ───────────── */

export function SkeletonLine({ w = '100%', h = 14 }: { w?: string | number; h?: number }) {
  // Size is data, not style: width/height vary per caller.
  return <span className="skeleton skeleton-line" style={{ width: typeof w === 'number' ? `${w}px` : w, height: h }} />;
}

/** Several lines of text, the last one short. */
export function SkeletonText({ lines = 3 }: { lines?: number }) {
  return <span className="skeleton-text" aria-hidden="true">
    {Array.from({ length: lines }, (_, i) => <SkeletonLine key={i} w={i === lines - 1 ? '60%' : '100%'} />)}
  </span>;
}

/** Generic table placeholder. */
export function SkeletonTable({ rows = 8, cols = 5 }: { rows?: number; cols?: number }) {
  return <div className="table-wrap" aria-hidden="true">
    <table className="data-table">
      <tbody>
        {Array.from({ length: rows }).map((_, r) => <tr key={r}>
          {Array.from({ length: cols }).map((_, c) => <td key={c}><SkeletonLine w={c === 0 ? '55%' : '38%'} /></td>)}
        </tr>)}
      </tbody>
    </table>
  </div>;
}

/** A MetricRow placeholder with the real row's height. */
export function SkeletonMetricRow({ count = 4 }: { count?: number }) {
  return <div className="metric-row" aria-hidden="true">
    {Array.from({ length: count }, (_, i) => <div key={i} className="metric">
      <div className="metric-label"><SkeletonLine w="50%" h={12} /></div>
      <div className="metric-value-row"><SkeletonLine w="70%" h={28} /></div>
      <div className="metric-delta"><SkeletonLine w="40%" h={12} /></div>
    </div>)}
  </div>;
}

/**
 * A chart placeholder at the chart's final size, so nothing moves when the
 * chart library arrives. `aria-busy` lets tests (and assistive tech) wait for it.
 */
export function SkeletonChart({ height, label = 'Loading chart', busy = true }: { height: number | string; label?: string | undefined; busy?: boolean | undefined }) {
  return <div className="skeleton-chart" style={{ height }} role="status" aria-busy={busy} aria-label={label}>
    <span className="skeleton skeleton-chart-plot" />
  </div>;
}
