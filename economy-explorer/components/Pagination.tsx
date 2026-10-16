// Server-rendered pagination. Each link is a plain <a> with the next page's
// searchParams — no client JS needed.

import Link from 'next/link';
import type { Route } from 'next';

interface PaginationProps {
  page: number;
  totalPages: number;
  totalItems: number;
  basePath: string;
  searchParams: Record<string, string | undefined>;
}

export function Pagination({ page, totalPages, totalItems, basePath, searchParams }: PaginationProps) {
  function href(target: number): Route {
    const sp = new URLSearchParams();
    for (const [k, v] of Object.entries(searchParams)) {
      if (v !== undefined) sp.set(k, v);
    }
    sp.set('page', String(target));
    return `${basePath}?${sp.toString()}` as Route;
  }

  const prev = Math.max(1, page - 1);
  const next = Math.min(totalPages, page + 1);

  return (
    <div className="pagination">
      <span className="pagination-summary">{totalItems.toLocaleString()} total · page {page} of {totalPages}</span>
      <div className="pagination-controls">
        {page > 1 ? <Link href={href(1)}>« First</Link> : <span className="disabled" aria-disabled="true">« First</span>}
        {page > 1 ? <Link href={href(prev)}>‹ Prev</Link> : <span className="disabled" aria-disabled="true">‹ Prev</span>}
        {page < totalPages ? <Link href={href(next)}>Next ›</Link> : <span className="disabled" aria-disabled="true">Next ›</span>}
        {page < totalPages ? <Link href={href(totalPages)}>Last »</Link> : <span className="disabled" aria-disabled="true">Last »</span>}
      </div>
    </div>
  );
}
