// Breadcrumbs for entity pages (spec §6.2), e.g. Directory › Firms › DRB ›
// Corporate Account. A server component: render it first in the page, above
// PageHeader. Pass `section` to get the leading crumbs from lib/nav (so labels
// and links never drift from the top bar) and `items` for the entity trail:
//
//   <Breadcrumbs section="/firms" items={[{ label: firm.name, href: `/firms/${…}` }, { label: account.name }]} />
//
// The last crumb is the current page: it is not a link and carries aria-current.
import Link from 'next/link';
import { sectionCrumbs, type Crumb } from '@/lib/nav';

export function Breadcrumbs({ section, items = [] }: { section?: string; items?: Crumb[] }) {
  const crumbs = [...(section ? sectionCrumbs(section) : []), ...items];
  if (crumbs.length === 0) return null;
  return (
    <nav aria-label="Breadcrumb" className="breadcrumbs">
      <ol>
        {crumbs.map((crumb, i) => {
          const last = i === crumbs.length - 1;
          return (
            <li key={`${i}-${crumb.label}`}>
              {last || !crumb.href ? (
                <span aria-current={last ? 'page' : undefined}>{crumb.label}</span>
              ) : (
                <Link href={crumb.href} prefetch={false}>{crumb.label}</Link>
              )}
            </li>
          );
        })}
      </ol>
    </nav>
  );
}
