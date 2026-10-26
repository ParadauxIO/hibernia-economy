'use client';
// Primary navigation (the six sections of spec §6.1) and the section nav under
// the top bar. Both are driven by lib/nav from the current path: a page never
// declares its section, and never renders its own section tabs.

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import type { Route } from 'next';
import { useViewer } from '@/components/ViewerContext';
import { itemMatches, sectionForPath, sectionItems, visibleSections } from '@/lib/nav';

export function HeaderNav() {
  const path = usePathname();
  const viewer = useViewer();
  const active = sectionForPath(path);
  return (
    <nav className="shell-nav" aria-label="Primary">
      {visibleSections(viewer).map((section) => {
        const current = active?.id === section.id;
        return (
          <Link
            key={section.id}
            href={section.href as Route}
            className="shell-nav-link"
            aria-current={current ? (path === section.href ? 'page' : 'true') : undefined}
            // Default prefetch: Next prefetches only the static shell up to each
            // route's loading.tsx, never the DB data, so top-level nav feels
            // instant without prefetch hammering the ledger.
          >
            {section.label}
          </Link>
        );
      })}
    </nav>
  );
}

/** The active section's pages, as a row under the top bar. Renders nothing for single-page sections. */
export function HeaderSectionNav() {
  const path = usePathname();
  const viewer = useViewer();
  const found = sectionItems(path, viewer);
  if (!found) return null;
  return (
    <div className="shell-subbar">
      <nav className="shell-subnav" aria-label={found.section.label}>
        {found.items.map((item) => {
          const current = itemMatches(item, path);
          return (
            <Link
              key={item.href}
              href={item.href as Route}
              prefetch={false}
              className="shell-subnav-link"
              aria-current={current ? (path === item.href ? 'page' : 'true') : undefined}
            >
              {item.label}
            </Link>
          );
        })}
      </nav>
    </div>
  );
}
