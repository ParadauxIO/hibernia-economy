'use client';
// Docs section nav. Active link via usePathname (mirrors components/HeaderNav).
// On mobile it collapses behind a toggle; selecting a link closes it.

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { useState } from 'react';
import type { DocsNavGroup } from '@/lib/docs';
import { useViewer } from '@/components/ViewerContext';

// Admin-only section. Kept out of the server-built public tree (so it never
// reaches the public navigation or sitemap); shown here only with
// docs.admin.read. The pages enforce the same permission at render time.
const ADMIN_GROUP: DocsNavGroup = {
  title: 'Admin',
  href: '/docs/admin',
  order: 999,
  items: [
    { title: 'Admin-operator runbook', href: '/docs/admin/operator-runbook', order: 0 },
    { title: 'Managing Explorer access', href: '/docs/admin/roles', order: 2 },
    { title: 'Operator commands', href: '/docs/admin/operator-commands', order: 3 },
    { title: 'Government accounts', href: '/docs/admin/government-accounts', order: 4 },
    { title: 'Explorer administration', href: '/docs/admin/explorer-administration', order: 5 },
    { title: 'Webhooks', href: '/docs/admin/webhooks', order: 6 },
    { title: 'Access and credentials', href: '/docs/admin/access-and-credentials', order: 7 },
    { title: 'Incident procedures', href: '/docs/admin/incidents', order: 8 },
    { title: 'Release and recovery', href: '/docs/admin/release-and-recovery', order: 9 },
    { title: 'Configuration', href: '/docs/admin/configuration', order: 10 },
    { title: 'Service configuration', href: '/docs/admin/config-services', order: 15 },
    { title: 'Realty operations', href: '/docs/admin/realty-operations', order: 16 },
  ],
};

export function DocsSidebar({ tree }: { tree: DocsNavGroup[] }) {
  const path = usePathname();
  const isAdmin = useViewer().permissions.includes('docs.admin.read');
  const [open, setOpen] = useState(false);
  const close = () => setOpen(false);
  const groups = isAdmin ? [...tree, ADMIN_GROUP] : tree;

  return (
    <aside className={`docs-sidebar${open ? ' open' : ''}`} aria-label="Documentation">
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
