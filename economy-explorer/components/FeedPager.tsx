// Newer / Older and jump-to-date for keyset feeds (/transactions, /chestshop/sales).
// The design system's CursorPager markup and classes, with one difference: the
// jump form carries the table state as repeated hidden fields, so every filter
// chip (one `f` param each) survives the jump. CursorPager's `jump.searchParams`
// is a plain record and can only carry one value per name.

import Link from 'next/link';
import type { Route } from 'next';

export interface FeedPagerProps {
  newerHref: string | null;
  olderHref: string | null;
  /** "Showing 50 of 1,000+ transactions". */
  summary?: string | undefined;
  jump: { action: string; param: string; value?: string | undefined; hidden: readonly [string, string][]; max?: string | undefined };
}

export function FeedPager({ newerHref, olderHref, summary, jump }: FeedPagerProps) {
  return (
    <nav className="pagination" aria-label="Pagination">
      <span className="pagination-summary">{summary}</span>
      <div className="pagination-controls">
        <form className="pagination-jump" action={jump.action} method="get">
          {jump.hidden.map(([k, v], i) => <input key={`${k}-${i}`} type="hidden" name={k} value={v} />)}
          <label className="pagination-jump-label">
            Jump to <input type="date" name={jump.param} defaultValue={jump.value} max={jump.max} />
          </label>
          <button type="submit" className="btn btn-quiet">Go</button>
        </form>
        {newerHref ? <Link href={newerHref as Route} prefetch={false} rel="prev">Newer</Link> : <span className="disabled" aria-disabled="true">Newer</span>}
        {olderHref ? <Link href={olderHref as Route} prefetch={false} rel="next">Older</Link> : <span className="disabled" aria-disabled="true">Older</span>}
      </div>
    </nav>
  );
}
