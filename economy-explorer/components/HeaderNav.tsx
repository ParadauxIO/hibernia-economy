'use client';
// Primary nav. Lights up the active section so users see where they are.
// Active match is inclusive — /accounts/* keeps Accounts highlighted, etc.

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import type { Route } from 'next';
import { useViewer } from '@/components/ViewerContext';

interface NavLink {
  href: Route;
  label: string;
  match: (path: string) => boolean;
}

export function HeaderNav() {
  const path = usePathname();
  const { isAdmin, canInvestigate } = useViewer();
  const links: NavLink[] = [
    {
      href: '/',
      label: 'Economy',
      match: (p) =>
        p === '/' ||
        p.startsWith('/economy/') ||
        p.startsWith('/money-flow') ||
        p.startsWith('/government'),
    },
    { href: '/accounts', label: 'Accounts', match: (p) => p.startsWith('/accounts') },
    { href: '/firms', label: 'Firms', match: (p) => p.startsWith('/firms') },
    {
      href: '/market',
      label: 'Market',
      match: (p) => p.startsWith('/market') || p.startsWith('/chestshop'),
    },
    { href: '/docs', label: 'Docs', match: (p) => p.startsWith('/docs') },
  ];
  if (canInvestigate && !isAdmin) {
    links.push({ href: '/transactions', label: 'Investigate', match: (p) => p.startsWith('/transactions') });
  }
  if (isAdmin) {
    // Single entry → the /admin section, which carries its own sub-navbar
    // (components/AdminNav) for the individual tools. /transactions is an
    // admin-gated tool that lives under the admin section too.
    links.push({ href: '/admin', label: 'Admin', match: (p) => p.startsWith('/admin') || p.startsWith('/transactions') });
  }
  return (
    <nav className="explorer-nav" aria-label="Primary">
      {links.map((l) => (
        <Link
          key={l.href}
          href={l.href}
          className={l.match(path) ? 'active' : ''}
          aria-current={l.match(path) ? 'page' : undefined}
          // Default prefetch (prop omitted): Next prefetches only the static shell
          // up to each route's loading.tsx, not the dynamic DB data — top-level nav
          // feels instant without prefetch hammering the ledger. Content links
          // elsewhere stay prefetch={false} per the anti-scraping posture.
        >
          {l.label}
        </Link>
      ))}
    </nav>
  );
}
