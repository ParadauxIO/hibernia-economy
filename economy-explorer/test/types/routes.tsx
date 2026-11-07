// Compile-only contracts, checked by npm run typecheck after next typegen.
import Link from 'next/link';
import { BackLink, EntityLink, SidebarLayout, Tabs } from '@democracycraft/design-system/next';
import { DataTable, type LinkComponent } from '@democracycraft/design-system/table';
import type { AppHref } from '@/lib/routes';
import type { NavItem } from '@/lib/nav';
import { accountHref } from '@/lib/entityLinks';
import { tableHref, type TableState, type AnyTable } from '@/lib/tables/core';

export function routeContracts(state: TableState, table: AnyTable) {
  const good: AppHref = '/docs';
  const dynamic: AppHref = accountHref(42);
  const RouterLink: LinkComponent<AppHref> = Link;
  // @ts-expect-error unknown page must not become an application href
  const bad: AppHref = '/acounts';
  // @ts-expect-error navigation models must check destinations at construction
  const nav: NavItem = { label: 'Broken', href: '/admin/audti' };
  void bad; void nav;
  return <>
    <Link href={good}>Docs</Link>
    <Link href={tableHref(dynamic, state, table)}>Account</Link>
    <BackLink href={dynamic} label="Account" />
    <Tabs label="Account" items={[{ label: 'Account', href: dynamic }]} />
    {/* @ts-expect-error Next Link must reject unknown static routes */}
    <Link href="/acounts">Broken</Link>
    {/* @ts-expect-error shared back links must preserve route checking */}
    <BackLink href="/acounts" label="Broken" />
    {/* @ts-expect-error entity links must preserve route checking */}
    <EntityLink kind="account" name="Broken" href="/acounts/42" />
    {/* @ts-expect-error route tabs must preserve route checking */}
    <Tabs label="Broken" items={[{ label: 'Broken', href: '/acounts' }]} />
    {/* @ts-expect-error sidebar data must preserve route checking */}
    <SidebarLayout title="Broken" nav={[{ items: [{ label: 'Broken', href: '/acounts' }] }]}><span /></SidebarLayout>
    {/* @ts-expect-error a typed table link must reject an unknown row route */}
    <DataTable id="broken" label="Broken" rows={[42]} columns={[]} rowKey={(id) => id} rowHref={() => '/acounts/42'} link={RouterLink} />
  </>;
}
