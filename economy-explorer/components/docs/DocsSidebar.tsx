'use client';
// Docs section nav. Active link via usePathname (mirrors components/HeaderNav).
// On mobile it collapses behind a toggle; selecting a link closes it.

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { useState } from 'react';
import type { DocsNavGroup } from '@/lib/docs';
import { useIsAdmin } from '@/components/ViewerContext';

// Admin-only section. Kept out of the server-built public tree (so it never
// reaches non-admins or the sitemap); shown here only when the viewer is an
// admin. The pages themselves also gate on the viewer at render time.
const ADMIN_GROUP: DocsNavGroup = {
  title: 'Admin',
  href: '/docs/admin',
  order: 999,
  items: [
    { title: 'Managing Explorer access', href: '/docs/admin/roles', order: 2 },
    { title: 'Operator commands', href: '/docs/admin/operator-commands', order: 3 },
    { title: 'Government accounts', href: '/docs/admin/government-accounts', order: 4 },
    { title: 'Webhooks', href: '/docs/admin/webhooks', order: 6 },
    { title: 'Configuration', href: '/docs/admin/configuration', order: 10 },
  ],
};

export function DocsSidebar({ tree }: { tree: DocsNavGroup[] }) {
  const path = usePathname();
  const isAdmin = useIsAdmin();
  const [open, setOpen] = useState(false);
  const close = () => setOpen(false);
  const groups = isAdmin ? [...tree, ADMIN_GROUP] : tree;

  return (
    <aside className={`docs-sidebar${open ? ' open' : ''}`}>
      <button
        type="button"
        className="docs-sidebar-toggle"
        onClick={() => setOpen((o) => !o)}
        aria-expanded={open}
      >
        Documentation menu
      </button>
      <nav className="docs-nav" aria-label="Documentation">
        {groups.map((group, i) => (
          <div className="docs-nav-group" key={group.title ?? `top-${i}`}>
            {group.title &&
              (group.href ? (
                <Link
                  href={group.href}
                  className={`docs-nav-section${path === group.href ? ' active' : ''}`}
                  onClick={close}
                  prefetch={false}
                >
                  {group.title}
                </Link>
              ) : (
                <span className="docs-nav-section">{group.title}</span>
              ))}
            <ul>
              {group.items.map((item) => (
                <li key={item.href}>
                  <Link
                    href={item.href}
                    className={path === item.href ? 'active' : ''}
                    onClick={close}
                    prefetch={false}
                  >
                    {item.title}
                  </Link>
                </li>
              ))}
            </ul>
          </div>
        ))}
      </nav>
    </aside>
  );
}
