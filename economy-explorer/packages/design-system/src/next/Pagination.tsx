// Server-rendered pagers. Every control is a plain link, so no client JS is needed.

import Link from 'next/link';
import type { Route } from 'next';
import { formatNum } from '../format';

interface PaginationProps<T extends string> {
  page: number;
  totalPages: number;
  totalItems: number;
  basePath: Route<T>;
  searchParams: Record<string, string | undefined>;
  /** Noun for the summary ("accounts"). Default "results". */
  noun?: string | undefined;
}

/** Page numbers to show: first, last, and a window around the current page, with gaps as null. */
export function pageWindow(page: number, total: number, around = 1): (number | null)[] {
  const pages = new Set([1, total]);
  for (let p = page - around; p <= page + around; p++) if (p >= 1 && p <= total) pages.add(p);
  const sorted = [...pages].sort((a, b) => a - b);
  const out: (number | null)[] = [];
  sorted.forEach((p, i) => {
    const prev = sorted[i - 1];
    if (prev !== undefined && p - prev === 2) out.push(prev + 1);
    else if (prev !== undefined && p - prev > 2) out.push(null);
    out.push(p);
  });
  return out;
}

function hrefFor<T extends string>(basePath: Route<T>, searchParams: Record<string, string | undefined>, param: string, value: string | null) {
  const sp = new URLSearchParams();
  for (const [k, v] of Object.entries(searchParams)) if (v !== undefined && v !== '' && k !== param) sp.set(k, v);
  if (value !== null) sp.set(param, value);
  const qs = sp.toString();
  return { pathname: basePath, search: qs ? `?${qs}` : '' };
}

/** Numbered pages for bounded result sets. `Pager` is the same component. */
export function Pagination<T extends string>({ page, totalPages, totalItems, basePath, searchParams, noun = 'results' }: PaginationProps<T>) {
  const href = (p: number) => hrefFor(basePath, searchParams, 'page', String(p));
  const last = Math.max(1, totalPages);
  return <nav className="pagination" aria-label="Pagination">
    <span className="pagination-summary">{formatNum(totalItems)} {noun} · page {page} of {last}</span>
    <div className="pagination-controls">
      {page > 1 ? <Link href={href(page - 1)} prefetch={false} rel="prev">Previous</Link> : <span className="disabled" aria-disabled="true">Previous</span>}
      <span className="pagination-pages">
        {pageWindow(page, last).map((p, i) => p === null
          ? <span key={`gap${i}`} className="pagination-gap" aria-hidden="true">…</span>
          : p === page
            ? <span key={p} className="pagination-page" aria-current="page">{p}</span>
            : <Link key={p} href={href(p)} prefetch={false} className="pagination-page" aria-label={`Page ${p}`}>{p}</Link>)}
      </span>
      {page < last ? <Link href={href(page + 1)} prefetch={false} rel="next">Next</Link> : <span className="disabled" aria-disabled="true">Next</span>}
    </div>
  </nav>;
}

export { Pagination as Pager };

interface CursorPagerProps<T extends string> {
  /** Link to newer rows, or null at the start of the feed. */
  newerHref?: Route<T> | null | undefined;
  /** Link to older rows, or null at the end of the feed. */
  olderHref?: Route<T> | null | undefined;
  /** A GET form jumping to a date: submits `param=YYYY-MM-DD` to `basePath`, keeping `searchParams`. */
  jump?: { basePath: Route<T>; param: string; value?: string; searchParams?: Record<string, string | undefined>; min?: string; max?: string };
  /** "1,000+ transactions" style summary for unbounded feeds. */
  summary?: string | undefined;
}

/** Newer / Older for keyset-paginated feeds, plus an optional jump-to-date. */
export function CursorPager<T extends string>({ newerHref, olderHref, jump, summary }: CursorPagerProps<T>) {
  return <nav className="pagination" aria-label="Pagination">
    {summary ? <span className="pagination-summary">{summary}</span> : <span className="pagination-summary" />}
    <div className="pagination-controls">
      {jump && <form className="pagination-jump" action={jump.basePath} method="get">
        {Object.entries(jump.searchParams ?? {}).filter(([k, v]) => v !== undefined && k !== jump.param).map(([k, v]) => <input key={k} type="hidden" name={k} value={v} />)}
        <label className="pagination-jump-label">Jump to <input type="date" name={jump.param} defaultValue={jump.value} min={jump.min} max={jump.max} /></label>
        <button type="submit" className="btn btn-quiet">Go</button>
      </form>}
      {newerHref ? <Link href={newerHref} prefetch={false} rel="prev">Newer</Link> : <span className="disabled" aria-disabled="true">Newer</span>}
      {olderHref ? <Link href={olderHref} prefetch={false} rel="next">Older</Link> : <span className="disabled" aria-disabled="true">Older</span>}
    </div>
  </nav>;
}
