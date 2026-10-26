import Link from 'next/link';
import type { Route } from 'next';
import { PageHeader, Tag, When } from '@democracycraft/design-system';
import { DataTable, TablePager, columnOptions, type Column } from '@democracycraft/design-system/table';
import { FilterBar } from '@/components/FilterBar';
import { getViewer, hasPermission } from '@/lib/auth';
import { buildMetadata } from '@/lib/metadata';
import { listRoleGrantsForAdmin } from '@/lib/services/role-grants';
import { adminRoleGrantsTable } from '@/lib/tables/admin';
import { parseTableState, tableHref, withPage, withSort, type TableState } from '@/lib/tables/core';
import { AdminGate } from '../_components/AdminGate';
import { AdminTableEmpty, RouterLink } from '../_components/table';

export const dynamic = 'force-dynamic';

export async function generateMetadata() {
  return buildMetadata({ title: 'Admin · Role grants', description: 'Every role each user holds and where it comes from.', path: '/admin/role-grants' });
}

const TABLE_ID = 'role-grants-table';
const PATH = '/admin/role-grants';
const SOURCE_LABEL: Record<string, string> = { baseline: 'Player baseline', direct: 'Direct', manual: 'Manual group', luckperms: 'LuckPerms', business: 'Firm', realty: 'Region' };

type Grant = Awaited<ReturnType<typeof listRoleGrantsForAdmin>>['rows'][number];

const columns: Column<Grant>[] = [
  {
    key: 'name',
    header: 'User',
    sort: 'name',
    cell: (g) => (
      <>
        <Link href={`/admin/players/${g.uuid}` as Route} prefetch={false}>{g.name ?? 'Unknown player'}</Link>
        <span className="dt-sub mono">{` ${g.uuid}`}</span>
      </>
    ),
  },
  {
    key: 'role',
    header: 'Role',
    sort: 'role',
    cell: (g) => <><Link href={`/admin/roles/${encodeURIComponent(g.role)}` as Route} prefetch={false}>{g.roleName}</Link><span className="dt-sub">{` ${g.role}`}</span></>,
  },
  { key: 'source', header: 'Source', sort: 'source', cell: (g) => SOURCE_LABEL[g.source] ?? g.source },
  { key: 'group', header: 'Originating group', cell: (g) => (g.groupId ? <Link href={`/admin/groups/${g.groupId}` as Route} prefetch={false}>{g.group}</Link> : '—') },
  { key: 'selector', header: 'Source node or selector', cell: (g) => (g.selector ? <span className="mono">{g.selector}</span> : '—') },
  {
    key: 'status',
    header: 'Status',
    cell: (g) => (g.active ? <Tag tone="pos">Active</Tag> : <Tag tone="warn">Inactive (expired or source disabled/changed)</Tag>),
  },
  { key: 'synced', header: 'Last synced', secondary: true, optional: true, cell: (g) => <When value={g.syncedAt} time /> },
  { key: 'added', header: 'Recorded', secondary: true, optional: true, cell: (g) => <When value={g.addedAt} time /> },
  { key: 'by', header: 'Recorded by', secondary: true, optional: true, cell: (g) => (g.addedBy ? <span className="mono">{g.addedBy}</span> : 'Not recorded') },
];

export default async function RoleGrantsPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const viewer = await getViewer();
  if (!hasPermission(viewer, 'roles.read') || !hasPermission(viewer, 'groups.read')) {
    return <AdminGate viewer={viewer} title="Role and group read access required" permission="roles.read and groups.read" />;
  }
  const state = parseTableState(await searchParams, adminRoleGrantsTable);
  const result = await listRoleGrantsForAdmin(state);
  const totalPages = Math.max(1, Math.ceil(result.total / state.limit));
  const href = (s: TableState<typeof adminRoleGrantsTable>) => tableHref(PATH, s, adminRoleGrantsTable);

  return (
    <>
      <PageHeader
        title="Role grants"
        subtitle="One row per role source. Active sources combine into a user's effective roles; what each role allows depends on its current definition."
      />
      <p className="muted">
        The player baseline applies to every signed-in user. LuckPerms sources reflect resolved inheritance or effective permission checks;
        the upstream inheritance path and the LuckPerms actor are not recorded. Inactive memberships grant nothing.
      </p>

      <FilterBar table={adminRoleGrantsTable} total={result.total} columns={columnOptions(columns)} tableId={TABLE_ID} />
      <DataTable
        id={TABLE_ID}
        label="Role grant provenance"
        columns={columns}
        rows={result.rows}
        rowKey={(g) => `${g.uuid}:${g.role}:${g.source}:${g.groupId ?? ''}`}
        sort={state.sort}
        sortHref={(field, dir) => href(withSort(state, field, dir, adminRoleGrantsTable))}
        link={RouterLink}
        density="compact"
        empty={<AdminTableEmpty pathname={PATH} state={state} table={adminRoleGrantsTable} />}
      />
      <TablePager page={Math.min(state.page, totalPages)} totalPages={totalPages} hrefFor={(p) => href(withPage(state, p))} link={RouterLink} label="Role grant pages" />
    </>
  );
}
