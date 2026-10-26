'use client';
// Sub-nav under the main header, shown on grouped sections (Economy, Market).
// Mirrors treasury-ui/src/explorer/SubNav.tsx. Tiny client component so we can
// drive the .active class from the URL without round-tripping through RSC.

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import type { Route } from 'next';
import type { Permission } from '@/lib/auth/policy';

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
            aria-current={active ? 'page' : undefined}
            prefetch={false}
          >
            {it.label}
          </Link>
        );
      })}
    </nav>
  );
}

// Mirrors the SPA's ECONOMY_SUB / MARKET_SUB lists in ExplorerLayout. Government
// is appended only for viewers who can open it (see canSeeGovernment).
export const ECONOMY_SUB: SubNavItem[] = [
  { href: '/', label: 'Overview', match: (p) => p === '/' },
  { href: '/economy/wealth', label: 'Wealth', match: (p) => p.startsWith('/economy/wealth') },
  { href: '/money-flow', label: 'Money Flow', match: (p) => p.startsWith('/money-flow') },
  { href: '/economy/taxation', label: 'Taxation', match: (p) => p.startsWith('/economy/taxation') },
];

const ECONOMY_GOVERNMENT: SubNavItem = { href: '/government', label: 'Government', match: (p) => p.startsWith('/government') };

/**
 * Presentation only; /government still gates server-side. The client viewer
 * carries permissions but not ReBAC scope, so a `government.read` holder with no
 * government account in scope still sees the tab (and an empty page). Every
 * other player, who holds neither permission, no longer lands on a gate.
 */
export function canSeeGovernment(permissions: readonly Permission[]): boolean {
  return permissions.includes('government.read.all') || permissions.includes('government.read');
}

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
export function pickSubNav(path: string, permissions: readonly Permission[]): SubNavItem[] | null {
  if (
    path === '/' ||
    path.startsWith('/economy/') ||
    path.startsWith('/money-flow') ||
    path.startsWith('/government')
  ) {
    return canSeeGovernment(permissions) ? [...ECONOMY_SUB, ECONOMY_GOVERNMENT] : ECONOMY_SUB;
  }
  if (path.startsWith('/market') || path.startsWith('/chestshop')) {
    return permissions.includes('market.sales.read') ? [...MARKET_SUB, MARKET_SALES] : MARKET_SUB;
  }
  return null;
}
