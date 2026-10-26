'use client';
// Tool navigation for the admin area, styled as the design system's route
// tabs. The account menu's "Admin" entry opens /admin; this lists every tool
// the viewer holds and marks the current one. /transactions lives outside
// /admin (its detail pages aren't admin-only) but is an admin tool, so it is
// listed here too and renders this nav itself.

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
    <nav className="tabs admin-tabs" aria-label="Admin">
      {links.map((l) => {
        const current = l.href === '/admin' ? path === '/admin' : path === l.href || path.startsWith(`${l.href}/`);
        return (
          <Link key={l.href} href={l.href as Route} className="tab" aria-current={current ? 'page' : undefined} prefetch={false}>
            {l.title}
          </Link>
        );
      })}
    </nav>
  );
}
