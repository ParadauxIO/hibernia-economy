import Link from 'next/link';
import { PageHeader, Section } from '@democracycraft/design-system';
import { DataTable, TablePager, columnOptions, type Column } from '@democracycraft/design-system/table';
import { FilterBar } from '@/components/FilterBar';
import { getViewer, hasPermission } from '@/lib/auth';
import { buildMetadata } from '@/lib/metadata';
import { getAdminRoles } from '@/lib/services/admin';
import type { AdminRoleRow } from '@/lib/sql/admin';
import { adminRolesTable } from '@/lib/tables/admin';
import { parseTableState, tableHref, withPage, withSort, type TableState } from '@/lib/tables/core';
import { AdminGate } from '../_components/AdminGate';
import { AdminTableEmpty, RouterLink } from '../_components/table';
import { RoleEditor } from './role-editor';

export const dynamic = 'force-dynamic';

export async function generateMetadata() {
  return buildMetadata({ title: 'Admin · Roles', description: 'Role definitions and their permissions.', path: '/admin/roles' });
}

const TABLE_ID = 'admin-roles-table';
const PATH = '/admin/roles';

const columns: Column<AdminRoleRow>[] = [
  {
    key: 'name',
    header: 'Role',
    sort: 'name',
    cell: (r) => <><Link href={`/admin/roles/${encodeURIComponent(r.key)}`} prefetch={false}>{r.name}</Link><span className="dt-sub mono">{` ${r.key}`}</span></>,
  },
  { key: 'description', header: 'Description', secondary: true, cell: (r) => r.description ?? '—' },
  { key: 'permissions', header: 'Permissions', sort: 'permissions', numeric: true, cell: (r) => r.permissionCount },
  { key: 'members', header: 'Direct members', sort: 'members', numeric: true, secondary: true, cell: (r) => r.memberCount },
  { key: 'groups', header: 'Granted by groups', numeric: true, secondary: true, optional: true, cell: (r) => r.groupCount },
];

export default async function RolesPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const viewer = await getViewer();
  if (!hasPermission(viewer, 'roles.read')) return <AdminGate viewer={viewer} title="Role access required" permission="roles.read" />;
  const state = parseTableState(await searchParams, adminRolesTable);
  const roles = await getAdminRoles(viewer, state);
  const href = (s: TableState<typeof adminRolesTable>) => tableHref(PATH, s, adminRolesTable);

  return (
    <>
      <PageHeader
        title="Roles"
        subtitle="A role is a named set of permissions. Players get roles directly or through a group; roles combine."
        actions={hasPermission(viewer, 'groups.read')
          ? <Link href={'/admin/role-grants'} prefetch={false} className="btn btn-quiet">Browse user role grants and group provenance</Link>
          : undefined}
      />
      <FilterBar table={adminRolesTable} total={roles.total} columns={columnOptions(columns)} tableId={TABLE_ID} />
      <DataTable
        id={TABLE_ID}
        label="Roles"
        columns={columns}
        rows={roles.rows}
        rowKey={(r) => r.key}
        sort={state.sort}
        sortHref={(field, dir) => href(withSort(state, field, dir, adminRolesTable))}
        link={RouterLink}
        empty={<AdminTableEmpty pathname={PATH} state={state} table={adminRolesTable} />}
      />
      <TablePager page={roles.page} totalPages={Math.max(1, Math.ceil(roles.total / state.limit))} hrefFor={(p) => href(withPage(state, p))} link={RouterLink} label="Roles pages" />
      {hasPermission(viewer, 'roles.create') && (
        <Section id="create-role" title="Create a role" description="You can only include permissions you hold yourself." divider>
          <RoleEditor actorPermissions={viewer.permissions} />
        </Section>
      )}
    </>
  );
}
