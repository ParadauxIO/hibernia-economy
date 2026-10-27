'use client';
import type { AppHref } from '@/lib/routes';

import type { ReactNode } from 'react';
import { usePathname, useSearchParams } from 'next/navigation';
import { SidebarLayout } from '@democracycraft/design-system/next';

export interface GovNavItem { label: string; href: AppHref }
export interface GovNavGroup { label?: string; items: GovNavItem[] }

/** Params every /gov page shares, carried between sections so the unit and range stick. */
const SHARED = ['unit', 'range', 'from', 'to', 'cmp'];

/**
 * The /gov workspace frame: SidebarLayout with links that keep the current unit
 * and range. Compliance pages are their own tool and don't take a unit.
 */
export function GovShell({ nav, children }: { nav: GovNavGroup[]; children: ReactNode }) {
  const pathname = usePathname();
  const params = useSearchParams();
  const carried = new URLSearchParams();
  for (const key of SHARED) {
    const v = params.get(key);
    if (v) carried.set(key, v);
  }
  const qs = carried.toString();
  const groups = nav.map((g) => ({
    label: g.label,
    items: g.items.map((item) => {
      const keep = qs && !item.href.startsWith('/gov/compliance') && !item.href.startsWith('/admin');
      const current = item.href === '/gov' || item.href === '/gov/compliance' ? pathname === item.href : pathname === item.href || pathname.startsWith(`${item.href}/`);
      return { label: item.label, href: keep ? `${item.href}?${qs}` as const : item.href, current };
    }),
  }));
  return <SidebarLayout title="Government" nav={groups}>{children}</SidebarLayout>;
}
