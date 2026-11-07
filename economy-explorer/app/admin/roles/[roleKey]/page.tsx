import { TablePager } from '@democracycraft/design-system/table';
import { FilterBar } from '@/components/FilterBar';
import { adminRoleMembersTable } from '@/lib/tables/admin';
import { parseTableState, tableHref, withPage } from '@/lib/tables/core';
import { RouterLink } from '../../_components/table';
import { notFound } from 'next/navigation';
import { PageHeader, Section } from '@democracycraft/design-system';
import { BackLink } from '@democracycraft/design-system/next';
import { getViewer, hasPermission } from '@/lib/auth';
import { buildMetadata } from '@/lib/metadata';
import { listRolesForAdmin, listDirectRoleMembers } from '@/lib/services/role';
import { AdminGate } from '../../_components/AdminGate';
import { RoleEditor } from '../role-editor';
import { RoleMembers } from './members';

export const dynamic = 'force-dynamic';

export async function generateMetadata({ params }: { params: Promise<{ roleKey: string }> }) {
  const { roleKey } = await params;
  return buildMetadata({ title: 'Admin · Role', description: 'A role definition and its direct assignments.', path: `/admin/roles/${roleKey}` });
}

export default async function RolePage({ params, searchParams }: { params: Promise<{ roleKey: string }>; searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const viewer = await getViewer();
  if (!hasPermission(viewer, 'roles.read')) return <AdminGate viewer={viewer} title="Role access required" permission="roles.read" />;
  const { roleKey } = await params;
  const role = (await listRolesForAdmin()).find(role => role.key === roleKey);
  if (!role) notFound();
  const state = parseTableState(await searchParams, adminRoleMembersTable);
  const members = await listDirectRoleMembers(role.key, state);
  return <>
    <BackLink href="/admin/roles" label="Roles" />
    <PageHeader title={role.name} eyebrow={<span className="mono">{role.key}</span>} subtitle={role.description ?? undefined}
      meta={`${role.permissions.length} permission${role.permissions.length === 1 ? '' : 's'} · ${members.total} direct member${members.total === 1 ? '' : 's'}`} />
    <Section id="permissions" title="Permissions"><RoleEditor role={role} actorPermissions={viewer.permissions} /></Section>
    <Section id="assignments" title="Direct assignments" divider>
      <FilterBar table={adminRoleMembersTable} total={members.total} />
      <RoleMembers roleKey={role.key} members={members.rows}
        canAssign={hasPermission(viewer, 'roles.assign') && role.permissions.every(p => viewer.permissions.includes(p))} />
      <TablePager page={members.page} totalPages={Math.max(1, Math.ceil(members.total / state.limit))}
        hrefFor={(p) => tableHref(`/admin/roles/${role.key}`, withPage(state, p), adminRoleMembersTable)} link={RouterLink} label="Direct member pages" />
    </Section>
  </>;
}
