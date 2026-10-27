'use client';
import type { SidebarGroup } from '@democracycraft/design-system/next';

import { usePathname } from 'next/navigation';
import type { ReactNode } from 'react';
import { SidebarLayout } from '@democracycraft/design-system/next';

const GROUPS: SidebarGroup[] = [
  { items: [
    { label: 'Overview', href: '/me' },
    { label: 'Transactions', href: '/me/transactions' },
    { label: 'Statements', href: '/me/statements' },
    { label: 'Taxes and fines', href: '/me/taxes' },
  ] },
  { label: 'More', items: [
    { label: 'Market', href: '/me/market' },
    { label: 'Firms', href: '/me/firms' },
    { label: 'Developer', href: '/me/developer' },
  ] },
];

/** The workspace sidebar. A section stays current on its sub-pages (a statement month, a webhook). */
export function MeSidebar({ children }: { children: ReactNode }) {
  const path = usePathname();
  const nav = GROUPS.map((g) => ({
    ...g,
    items: g.items.map((i) => ({ ...i, current: i.href === '/me' ? path === '/me' : path === i.href || path.startsWith(`${i.href}/`) })),
  }));
  return <SidebarLayout title="My finances" nav={nav}>{children}</SidebarLayout>;
}
