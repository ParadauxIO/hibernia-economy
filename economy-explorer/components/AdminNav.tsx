'use client';
// Sub-navbar for the admin area. One "Admin" entry in the main header opens this;
// it lists every admin tool and highlights the active one. /transactions lives
// outside /admin (its detail pages aren't admin-only) but is an admin-gated tool,
// so it's surfaced here too and renders this nav itself.

import { ADMIN_TOOLS } from '@/lib/auth/admin';
import { useViewer } from '@/components/ViewerContext';
import Link from 'next/link';
import { usePathname } from 'next/navigation';
import type { Route } from 'next';

export function AdminNav() {
  const path = usePathname();
  const { permissions } = useViewer();
  const links = [{ href: '/admin', title: 'Overview' }, ...ADMIN_TOOLS.filter(t => permissions.includes(t.permission))];
  return (
    <nav className="admin-subnav" aria-label="Admin">
      {links.map((l) => (
        <Link key={l.href} href={l.href as Route} className={(l.href === '/admin' ? path === '/admin' : path.startsWith(l.href)) ? 'active' : ''} prefetch={false}>
          {l.title}
        </Link>
      ))}
    </nav>
  );
}
