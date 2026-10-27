import Link from 'next/link';
import { TablePager } from '@democracycraft/design-system/table';
import { FilterBar } from '@/components/FilterBar';
import { adminGroupMembersTable } from '@/lib/tables/admin';
import { parseTableState, tableHref, withPage } from '@/lib/tables/core';
import { RouterLink } from '../../_components/table';
import { notFound } from 'next/navigation';
import { MetricCard, MetricRow, Num, PageHeader, Section } from '@democracycraft/design-system';
import { BackLink } from '@democracycraft/design-system/next';
import { hasPermission } from '@/lib/auth/policy';
import { getViewer } from '@/lib/auth/viewer';
import { auditView } from '@/lib/audit';
import { buildMetadata } from '@/lib/metadata';
import { getGroup, groupMemberCountsBySource, listGroupMembers } from '@/lib/services/group';
import { listRoleDefinitions } from '@/lib/sql/role';
import { AdminGate } from '../../_components/AdminGate';
import styles from '../../admin.module.css';
import { CapabilitiesForm } from './capabilities-form';
import { LuckpermsNodeForm } from './luckperms-node-form';
import { MemberManager } from './member-manager';
import { SyncSourceForm } from './sync-source-form';

export const dynamic = 'force-dynamic';

export async function generateMetadata({ params }: { params: Promise<{ groupId: string }> }) {
  const { groupId } = await params;
  return buildMetadata({ title: 'Group', description: 'Manage an explorer access group.', path: `/admin/groups/${groupId}` });
}


export default async function GroupDetailPage({ params, searchParams }: { params: Promise<{ groupId: string }>; searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const viewer = await getViewer();
  if (!hasPermission(viewer, 'groups.read')) return <AdminGate viewer={viewer} title="Group access required" permission="groups.read" />;

  const { groupId } = await params;
  const id = Number(groupId);
  if (!Number.isInteger(id) || id <= 0) notFound();

  const group = await getGroup(id);
  if (!group) notFound();
  await auditView(viewer, { path: `/admin/groups/${id}`, targetType: 'group', targetId: String(id) });

  const state = parseTableState(await searchParams, adminGroupMembersTable);
  const [members, roles, counts] = await Promise.all([listGroupMembers(id, state), listRoleDefinitions(), groupMemberCountsBySource(id)]);
  const delegable = group.capabilities.every(key => roles.find(r => r.key === key)?.permissions.every(p => viewer.permissions.includes(p)));
  const assignable = roles.filter(role => role.permissions.every(p => viewer.permissions.includes(p)) || group.capabilities.includes(role.key));
  const compliance = roles.find(role => role.key === 'compliance');
  const linked = roles.filter(role => group.capabilities.includes(role.key));
  const count = (source: 'manual' | 'luckperms' | 'business' | 'realty') => counts.find(c => c.source === source) ?? { total: 0, active: 0 };
  const canSync = delegable && hasPermission(viewer, 'groups.sync.update');
  const firm = group.syncSources.find(s => s.source === 'business')?.selector ?? null;
  const region = group.syncSources.find(s => s.source === 'realty')?.selector ?? null;
  const live = (source: 'manual' | 'luckperms' | 'business' | 'realty') => {
    const c = count(source);
    return `${c.active.toLocaleString('en-US')} live${c.total > c.active ? ` of ${c.total.toLocaleString('en-US')}` : ''}`;
  };

  return (
    <>
      <BackLink href="/admin/groups" label="Groups" />
      <PageHeader
        title={group.name}
        subtitle={group.description ?? 'An explorer access group. Its members get every role it grants.'}
        meta={group.luckpermsNode ? <>Synced from LuckPerms <code>{group.luckpermsNode}</code></> : undefined}
      />

      <MetricRow label="Group summary">
        <MetricCard label="Live members" value={<Num value={group.activeMemberCount} />} meta={group.memberCount > group.activeMemberCount ? `${group.memberCount} including expired syncs` : 'Members whose grant is current'} />
        <MetricCard label="Roles granted" value={<Num value={group.capabilities.length} />} meta={linked.map(r => r.name).join(', ') || 'None yet'} />
        <MetricCard label="LuckPerms source" value={group.luckpermsNode ? <code>{group.luckpermsNode}</code> : 'None'} meta={group.luckpermsNode ? live('luckperms') : 'Manual members only'} />
      </MetricRow>

      <Section
        id="roles"
        title="Roles this group grants"
        description="Tick a role to give it to every member. You can only grant roles whose permissions you hold yourself."
      >
        <ol className={styles.steps}>
          <li>Set where members come from under <a href="#sources">Membership sources</a>: a LuckPerms group (for example <code>group.doc</code>), a firm, a region, or manual members.</li>
          <li>Tick the roles they should get{compliance ? <>, for example <Link href={`/admin/roles/${compliance.key}`} prefetch={false}>{compliance.name}</Link> for a department that needs the compliance tools</> : null}.</li>
          <li>Membership then follows the source automatically. What each role allows is edited on its <Link href={'/admin/roles'} prefetch={false}>role page</Link>.</li>
        </ol>
        <CapabilitiesForm
          groupId={id}
          all={assignable.map(role => ({ value: role.key, label: role.name, description: role.description ?? '', permissions: role.permissions.length }))}
          selected={group.capabilities}
          disabled={!delegable || !hasPermission(viewer, 'groups.roles.update')}
        />
      </Section>

      <Section
        id="sources"
        title="Membership sources"
        description="Synced members are refreshed by the Treasury API plugin at most every 30 minutes and expire after an hour without a refresh. Changing or clearing a source removes its old synced members immediately; manual members stay."
        divider
      >
        <div className={styles.stack}>
          <Section level={3} title={`LuckPerms · ${live('luckperms')}`} description="A bare group name or group.<name> follows LuckPerms inheritance; a dotted permission node uses an effective permission check.">
            <LuckpermsNodeForm groupId={id} node={group.luckpermsNode} disabled={!canSync} />
          </Section>
          <Section level={3} title={`Firm · ${live('business')}`} description="The firm's proprietor and staff with FINANCIAL or ADMIN permission.">
            <SyncSourceForm groupId={id} source="business" selector={firm} disabled={!canSync} />
          </Section>
          <Section level={3} title={`Region · ${live('realty')}`} description="The region's title holder and its current, unexpired tenant.">
            <SyncSourceForm groupId={id} source="realty" selector={region} disabled={!canSync} />
          </Section>
          <p className={styles.hint}>Manual members: {live('manual')}.</p>
        </div>
      </Section>

      <Section id="members" title="Members" description="Manual members can be added and removed here; synced rows are owned by their source." divider>
        <FilterBar table={adminGroupMembersTable} total={members.total} />
        <MemberManager groupId={id} members={members.rows} disabled={!delegable || !hasPermission(viewer, 'groups.members.update')} />
        <TablePager page={members.page} totalPages={Math.max(1, Math.ceil(members.total / state.limit))}
          hrefFor={(p) => tableHref(`/admin/groups/${id}`, withPage(state, p), adminGroupMembersTable)} link={RouterLink} label="Group member pages" />
      </Section>
    </>
  );
}
