'use client';
// Inline section tabs. Lives inside the page-heading row instead of as a
// separate global nav strip — replaces the old LayoutSubNav for a cleaner
// header. Auto-detects which tab list to show via usePathname; returns null
// on pages that aren't in a section group.

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import type { Route } from 'next';
import { pickSubNav } from '@/components/SubNav';
import { useViewer } from '@/components/ViewerContext';

export function SectionTabs() {
  const path = usePathname();
  const { permissions } = useViewer();
  const items = pickSubNav(path, permissions.includes('market.sales.read'));
  if (!items) return null;
  return (
    <nav className="section-tabs" aria-label="Section">
      {items.map((it) => {
        const active = it.match ? it.match(path) : path === it.href;
        return (
          <Link
            key={it.href}
            href={it.href as Route}
            className={active ? 'active' : ''}
            prefetch={false}
          >
            {it.label}
          </Link>
        );
      })}
    </nav>
  );
}
