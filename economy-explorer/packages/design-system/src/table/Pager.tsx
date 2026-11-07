import type { LinkComponent } from './DataTable';
import './table.css';

const PlainLink = ({ href, className, children, 'aria-label': ariaLabel }: React.ComponentProps<LinkComponent>) => (
  <a href={href} className={className} aria-label={ariaLabel}>{children}</a>
);

/**
 * Numbered paging for bounded directories. The host builds each href from its
 * table state, so repeated params (one `f` per chip) survive. Unbounded feeds
 * use cursor paging instead (spec §4.3).
 */
export function TablePager<H extends string = string>({ page, totalPages, hrefFor, link, label = 'Pagination' }: {
  page: number;
  totalPages: number;
  hrefFor: (page: number) => H;
  link?: LinkComponent<H>;
  label?: string;
}) {
  if (totalPages <= 1) return null;
  const Link: LinkComponent<H> = link ?? PlainLink;
  const step = (target: number, text: string, aria: string) => (
    target >= 1 && target <= totalPages && target !== page
      ? <Link href={hrefFor(target)} className="dt-pager-link" aria-label={aria}>{text}</Link>
      : <span className="dt-pager-link is-disabled" aria-disabled="true">{text}</span>
  );
  return (
    <nav className="dt-pager" aria-label={label}>
      <span className="dt-pager-status">Page {page.toLocaleString('en-US')} of {totalPages.toLocaleString('en-US')}</span>
      <span className="dt-pager-links">
        {step(1, 'First', 'First page')}
        {step(page - 1, 'Previous', 'Previous page')}
        {step(page + 1, 'Next', 'Next page')}
        {step(totalPages, 'Last', 'Last page')}
      </span>
    </nav>
  );
}
