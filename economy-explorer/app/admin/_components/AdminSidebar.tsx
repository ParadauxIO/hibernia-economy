'use client';
import type { AppHref } from '@/lib/routes';

import { usePathname } from 'next/navigation';
import type { ReactNode } from 'react';
import { SidebarLayout, type SidebarGroup } from '@democracycraft/design-system/next';
import styles from '../admin.module.css';

export interface AdminNavGroup {
  label?: string;
  items: { label: string; href: AppHref }[];
}

/**
 * The /admin workspace shell. SidebarLayout matches the exact pathname; admin
 * tools own sub-routes (/admin/groups/12), so the current item is the longest
 * href the path starts with.
 */
export function AdminSidebar({ groups, children }: { groups: AdminNavGroup[]; children: ReactNode }) {
  const pathname = usePathname();
  const hrefs = groups.flatMap((g) => g.items.map((i) => i.href));
  const current = hrefs
    .filter((href) => pathname === href || (href !== '/admin' && pathname.startsWith(`${href}/`)))
    .sort((a, b) => b.length - a.length)[0];
  const nav: SidebarGroup<AppHref>[] = groups.map((g) => ({
    label: g.label,
    items: g.items.map((i) => ({ ...i, current: i.href === current })),
  }));
  return <SidebarLayout title="Admin" nav={nav}><div className={styles.content}>{children}</div></SidebarLayout>;
}
