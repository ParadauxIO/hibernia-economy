'use client';

import Link from 'next/link';
import type { Route } from 'next';
import { usePathname, useRouter } from 'next/navigation';
import { useId, type ReactNode } from 'react';
import { cx } from '../cx';

export interface SidebarItem {
  label: string;
  href: string;
  /** Overrides the pathname match (e.g. a section index that owns sub-routes). */
  current?: boolean | undefined;
  count?: number | string | undefined;
}

export interface SidebarGroup {
  label?: string | undefined;
  items: SidebarItem[];
}

export interface SidebarLayoutProps {
  /** Workspace name above the navigation ("My finances"). */
  title: string;
  nav: SidebarGroup[];
  children: ReactNode;
}

function isCurrent(item: SidebarItem, pathname: string): boolean {
  if (item.current !== undefined) return item.current;
  return pathname === item.href;
}

/**
 * The workspace shell for /me, /gov and /admin: left navigation that becomes a
 * select at the top on phones. The page's own PageHeader goes in `children`.
 */
export function SidebarLayout({ title, nav, children }: SidebarLayoutProps) {
  const pathname = usePathname();
  const router = useRouter();
  const selectId = useId();
  const all = nav.flatMap((g) => g.items);
  const current = all.find((item) => isCurrent(item, pathname));
  return <div className="sidebar-layout">
    <aside className="sidebar">
      <p className="sidebar-title">{title}</p>
      <nav aria-label={title} className="sidebar-nav">
        {nav.map((group, gi) => <div key={gi} className="sidebar-group">
          {group.label && <p className="sidebar-group-label">{group.label}</p>}
          <ul>
            {group.items.map((item) => <li key={item.href}>
              <Link href={item.href as Route} prefetch={false} className={cx('sidebar-link')} aria-current={isCurrent(item, pathname) ? 'page' : undefined}>
                <span>{item.label}</span>{item.count !== undefined && <span className="sidebar-count">{item.count}</span>}
              </Link>
            </li>)}
          </ul>
        </div>)}
      </nav>
      <div className="sidebar-select">
        <label htmlFor={selectId} className="sr-only">{title}: section</label>
        <select id={selectId} className="select" value={current?.href ?? ''} onChange={(e) => router.push(e.target.value as Route)}>
          {!current && <option value="" disabled>{title}</option>}
          {nav.map((group, gi) => group.label
            ? <optgroup key={gi} label={group.label}>{group.items.map((item) => <option key={item.href} value={item.href}>{item.label}</option>)}</optgroup>
            : group.items.map((item) => <option key={item.href} value={item.href}>{item.label}</option>))}
        </select>
      </div>
    </aside>
    <div className="sidebar-content">{children}</div>
  </div>;
}
