'use client';
// Sub-navbar for the admin area. One "Admin" entry in the main header opens this;
// it lists every admin tool and highlights the active one. /transactions lives
// outside /admin (its detail pages aren't admin-only) but is an admin-gated tool,
// so it's surfaced here too and renders this nav itself.

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import type { Route } from 'next';

interface AdminLink {
  href: Route;
  label: string;
  match: (p: string) => boolean;
}

const LINKS: AdminLink[] = [
  { href: '/admin', label: 'Overview', match: (p) => p === '/admin' },
  { href: '/admin/players', label: 'Players', match: (p) => p.startsWith('/admin/players') },
  { href: '/admin/accounts', label: 'Accounts', match: (p) => p.startsWith('/admin/accounts') },
  { href: '/transactions', label: 'Transactions', match: (p) => p.startsWith('/transactions') },
  { href: '/admin/api-keys', label: 'API keys', match: (p) => p.startsWith('/admin/api-keys') },
  { href: '/admin/groups', label: 'Groups', match: (p) => p.startsWith('/admin/groups') },
  { href: '/admin/firms', label: 'Firms', match: (p) => p.startsWith('/admin/firms') },
  { href: '/admin/webhooks', label: 'Webhooks', match: (p) => p.startsWith('/admin/webhooks') },
];

export function AdminNav() {
  const path = usePathname();
  return (
    <nav className="admin-subnav" aria-label="Admin">
      {LINKS.map((l) => (
        <Link key={l.href} href={l.href} className={l.match(path) ? 'active' : ''} prefetch={false}>
          {l.label}
        </Link>
      ))}
    </nav>
  );
}
