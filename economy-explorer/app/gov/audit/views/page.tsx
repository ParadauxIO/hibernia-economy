import type { AppHref } from '@/lib/routes';
import Link from 'next/link';
import { PageHeader, When } from '@democracycraft/design-system';
import { CursorPager } from '@democracycraft/design-system/next';
import { DataTable, TableEmpty, type Column, type LinkComponent } from '@democracycraft/design-system/table';
import { FilterBar } from '@/components/FilterBar';
import { buildMetadata } from '@/lib/metadata';
import { getViewsAudit } from '@/lib/services/government';
import type { ViewRow } from '@/lib/sql/government';
import { cleared, filterText, parseTableState, tableHref, withCursor, withQuery, withoutFilter, type TableState } from '@/lib/tables/core';
import { govViewsTable } from '@/lib/tables/gov';
import { searchGovEntitiesAction } from '../../actions';
import { GovGate } from '../../GovGate';
import { AccountRef, GovControls, PlayerRef, ScopeNote, govPage, isGate, type SearchParams } from '../../shared';
import { AuditTabs } from '../tabs';

export const dynamic = 'force-dynamic';

export async function generateMetadata() {
  return buildMetadata({ title: 'Government audit: views', description: 'Who viewed government data in your scope on {server}.', path: '/gov/audit/views' });
}

const RouterLink: LinkComponent<AppHref> = Link;

const columns: Column<ViewRow>[] = [
  { key: 'actor', header: 'Viewer', cell: (v) => <PlayerRef uuid={v.actor_uuid} name={v.actor_name} /> },
  { key: 'at', header: 'When', secondary: true, cell: (v) => <When value={v.at} time /> },
  { key: 'role', header: 'Role', secondary: true, cell: (v) => v.actor_role ?? '—' },
  { key: 'what', header: 'What', cell: (v) => v.target_type === 'account' && v.target_id ? <AccountRef id={Number(v.target_id)} name={null} /> : <span className="muted">Workspace page</span> },
  { key: 'path', header: 'Page', secondary: true, cell: (v) => <code>{v.method === 'GET' ? '' : `${v.method} `}{v.path}</code> },
];

export default async function GovAuditViewsPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const sp = await searchParams;
  const page = await govPage(sp, '/gov/audit/views');
  if (isGate(page)) return page.gate;
  if (!page.scope.canAudit) return <GovGate reason="permission" title="Audit access required" description="The audit view needs the government.audit.read permission." />;
  const state = parseTableState(sp, govViewsTable);
  const v = await getViewsAudit(page.scope, page.selection.ids, page.range.range, state);
  const href = (s: TableState) => tableHref('/gov/audit/views', s, govViewsTable, page.extra);
  const chips = [
    ...(state.q ? [{ text: `“${state.q}”`, removeHref: href(withQuery(state, '')) }] : []),
    ...state.filters.map((f) => ({ text: filterText(f, govViewsTable, { entityLabels: v.labels }), removeHref: href(withoutFilter(state, f.field)) })),
  ];
  return <>
    <PageHeader title="Audit" subtitle="Explorer views and exports of these accounts and of this workspace" tabs={<AuditTabs current="views" query={new URLSearchParams(page.extra).toString()} />} />
    <GovControls page={page} />
    <ScopeNote page={page} />
    <FilterBar table={govViewsTable} entityLabels={v.labels} searchEntity={searchGovEntitiesAction} tableId="gov-views-table" />
    <DataTable id="gov-views-table" label="Views of in-scope data" columns={columns} rows={v.rows} rowKey={(r) => r.audit_id} link={RouterLink}
      empty={<TableEmpty noun="views" filters={chips} clearHref={href(cleared(state))} link={RouterLink} />} />
    <CursorPager newerHref={state.cursor ? href(withCursor(state, null)) : undefined} olderHref={v.next ? href(withCursor(state, v.next)) : undefined} />
  </>;
}
