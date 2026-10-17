'use client';
// Sub-nav under the main header, shown on grouped sections (Economy, Market).
// Mirrors treasury-ui/src/explorer/SubNav.tsx. Tiny client component so we can
// drive the .active class from the URL without round-tripping through RSC.

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import type { Route } from 'next';

export interface SubNavItem {
  href: string;
  label: string;
  match?: (path: string) => boolean;
}

export function SubNav({ items }: { items: SubNavItem[] }) {
  const path = usePathname();
  return (
    <nav className="subnav" aria-label="Secondary">
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

// Mirrors the SPA's ECONOMY_SUB / MARKET_SUB lists in ExplorerLayout.
export const ECONOMY_SUB: SubNavItem[] = [
  { href: '/', label: 'Overview', match: (p) => p === '/' },
  { href: '/economy/wealth', label: 'Wealth', match: (p) => p.startsWith('/economy/wealth') },
  { href: '/money-flow', label: 'Money Flow', match: (p) => p.startsWith('/money-flow') },
  { href: '/economy/taxation', label: 'Taxation', match: (p) => p.startsWith('/economy/taxation') },
  { href: '/government', label: 'Government', match: (p) => p.startsWith('/government') },
];

// Items + Shops are public. The per-firm market "Firms" tab was removed (it
// duplicated the main Firms page). The raw Sales feed is for moderators and admins — regular
// players don't need to know it exists.
export const MARKET_SUB: SubNavItem[] = [
  { href: '/market', label: 'Browse Items', match: (p) => p === '/market' || p === '/chestshop' || p.startsWith('/chestshop/items') },
  { href: '/chestshop/shops', label: 'Find Shops', match: (p) => p.startsWith('/chestshop/shops') },
  { href: '/market/activity', label: 'Activity', match: (p) => p.startsWith('/market/activity') },
];

const MARKET_SALES: SubNavItem = {
  href: '/chestshop/sales',
  label: 'Sales',
  match: (p) => p.startsWith('/chestshop/sales'),
};

// Pure-function path-matcher so the nav can choose which list to render.
export function pickSubNav(path: string, canInvestigate: boolean): SubNavItem[] | null {
  if (
    path === '/' ||
    path.startsWith('/economy/') ||
    path.startsWith('/money-flow') ||
    path.startsWith('/government')
  ) {
    return ECONOMY_SUB;
  }
  if (path.startsWith('/market') || path.startsWith('/chestshop')) {
    return canInvestigate ? [...MARKET_SUB, MARKET_SALES] : MARKET_SUB;
  }
  return null;
}
