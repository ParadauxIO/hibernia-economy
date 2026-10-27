import Link from 'next/link';
import { PageHeader, Section, Tag } from '@democracycraft/design-system';
import { DataTable, TablePager, columnOptions, type Column } from '@democracycraft/design-system/table';
import { FilterBar } from '@/components/FilterBar';
import { hasPermission } from '@/lib/auth/policy';
import { getViewer } from '@/lib/auth/viewer';
import { auditView } from '@/lib/audit';
import { buildMetadata } from '@/lib/metadata';
import { getAdminGroups } from '@/lib/services/admin';
import type { GroupSummary } from '@/lib/sql/group';
import { adminGroupsTable } from '@/lib/tables/admin';
import { parseTableState, tableHref, withPage, withSort, type TableState } from '@/lib/tables/core';
import { AdminGate } from '../_components/AdminGate';
import { AdminTableEmpty, RouterLink } from '../_components/table';
import { CreateGroupForm } from './create-group-form';
import { DeleteGroupButton } from './delete-group-button';

export const dynamic = 'force-dynamic';

export async function generateMetadata() {
  return buildMetadata({ title: 'Groups', description: 'Manage explorer access groups and the roles they grant.', path: '/admin/groups' });
}

const TABLE_ID = 'admin-groups-table';
const PATH = '/admin/groups';

function sources(g: GroupSummary) {
  const out = [
    ...(g.luckpermsNode ? [<span key="lp">LuckPerms <code>{g.luckpermsNode}</code></span>] : []),
    ...g.syncSources.map((s) => <span key={s.source}>{s.source === 'business' ? 'Firm' : 'Region'} <code>{s.selector}</code></span>),
  ];
  return out.length ? out.reduce<React.ReactNode[]>((acc, el, i) => (i ? [...acc, ', ', el] : [el]), []) : <span className="muted">Manual only</span>;
}

export default async function AdminGroupsPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const viewer = await getViewer();
  if (!hasPermission(viewer, 'groups.read')) return <AdminGate viewer={viewer} title="Group access required" permission="groups.read" />;
  await auditView(viewer, { path: PATH, targetType: 'global' });

  const state = parseTableState(await searchParams, adminGroupsTable);
  const groups = await getAdminGroups(viewer, state);
  const href = (s: TableState<typeof adminGroupsTable>) => tableHref(PATH, s, adminGroupsTable);
  const canDelete = hasPermission(viewer, 'groups.delete');

  const columns: Column<GroupSummary>[] = [
    {
      key: 'name',
      header: 'Group',
      sort: 'name',
      cell: (g) => <><Link href={`/admin/groups/${g.groupId}`} prefetch={false}>{g.name}</Link>{g.description && <span className="dt-sub">{` · ${g.description}`}</span>}</>,
    },
    {
      key: 'roles',
      header: 'Grants roles',
      cell: (g) => (g.capabilities.length ? g.capabilities.map((c) => <Tag key={c} tone="accent">{c}</Tag>) : <span className="muted">None</span>),
    },
    { key: 'sources', header: 'Members come from', secondary: true, cell: sources },
    {
      key: 'members',
      header: 'Live members',
      sort: 'members',
      numeric: true,
      cell: (g) => <>{g.activeMemberCount.toLocaleString('en-US')}{g.memberCount > g.activeMemberCount && <span className="dt-sub">{` of ${g.memberCount.toLocaleString('en-US')}`}</span>}</>,
    },
    ...(canDelete ? [{
      key: 'actions',
      header: <span className="sr-only">Actions</span>,
      label: 'Actions',
      align: 'end' as const,
      cell: (g: GroupSummary) => <DeleteGroupButton groupId={g.groupId} name={g.name} />,
    }] : []),
  ];

  return (
    <>
      <PageHeader
        title="Access groups"
        subtitle="A group's members get every role it grants. Members come from LuckPerms, a firm, a region, or are added by hand."
      />

      {hasPermission(viewer, 'groups.create') && (
        <Section id="new-group" title="New group">
          <CreateGroupForm />
        </Section>
      )}

      <Section id="all-groups" title="All groups" divider={hasPermission(viewer, 'groups.create')}>
        <FilterBar table={adminGroupsTable} total={groups.total} columns={columnOptions(columns)} tableId={TABLE_ID} />
        <DataTable
          id={TABLE_ID}
          label="All groups"
          columns={columns}
          rows={groups.rows}
          rowKey={(g) => g.groupId}
          sort={state.sort}
          sortHref={(field, dir) => href(withSort(state, field, dir, adminGroupsTable))}
          link={RouterLink}
          empty={<AdminTableEmpty pathname={PATH} state={state} table={adminGroupsTable} />}
        />
        <TablePager page={groups.page} totalPages={Math.max(1, Math.ceil(groups.total / state.limit))} hrefFor={(p) => href(withPage(state, p))} link={RouterLink} label="Groups pages" />
      </Section>
    </>
  );
}
