import { hasPermission } from '@/lib/auth/policy';
import { buildMetadata } from '@/lib/metadata';
import { notFound } from 'next/navigation';
import { getViewer } from '@/lib/auth/viewer';
import { auditView } from '@/lib/audit';
import { PrivacyGate } from '@/components/PrivacyGate';
import { BackLink } from '@democracycraft/design-system/next';
import { getGroup, listGroupMembers } from '@/lib/services/group';
import { listRoleDefinitions } from '@/lib/sql/role';
import { CapabilitiesForm } from './capabilities-form';
import { LuckpermsNodeForm } from './luckperms-node-form';
import { MemberManager } from './member-manager';

export const dynamic = 'force-dynamic';

export async function generateMetadata({ params }: { params: Promise<{ groupId: string }> }) {
  const { groupId } = await params;
  return buildMetadata({ title: 'Group', description: 'Manage an explorer access group.', path: `/admin/groups/${groupId}` });
}

export default async function GroupDetailPage({ params }: { params: Promise<{ groupId: string }> }) {
  const viewer = await getViewer();
  if (viewer.anon) return <PrivacyGate kind="login" title="Access groups" hint="Sign in to manage groups." />;
  if (!hasPermission(viewer, 'groups.read')) return <PrivacyGate kind="private" title="Permission required" />;

  const { groupId } = await params;
  const id = Number(groupId);
  if (!Number.isInteger(id)) notFound();

  const group = await getGroup(id);
  if (!group) notFound();
  await auditView(viewer, { path: `/admin/groups/${id}`, targetType: 'global', targetId: `group:${id}` });

  const [members, roles] = await Promise.all([listGroupMembers(id), listRoleDefinitions()]);
  const delegable = group.capabilities.every(key => roles.find(r => r.key === key)?.permissions.every(p => viewer.permissions.includes(p)));
  const assignable = roles.filter(role => role.permissions.every(p => viewer.permissions.includes(p)) || group.capabilities.includes(role.key));

  return (
    <>
      <BackLink href="/admin/groups" label="Groups" />
      <div className="page-heading">
        <h1>{group.name}</h1>
        {group.description && <span className="sub">{group.description}</span>}
      </div>

      <div className="card">
        <div className="card-title">Roles <span className="sub">tick what membership of this group grants — saved to this group and applied to every member</span></div>
        <CapabilitiesForm
          groupId={id}
          all={assignable.map(role => ({ value: role.key, label: role.name, description: role.description ?? '' }))}
          selected={group.capabilities}
          disabled={!delegable || !hasPermission(viewer, 'groups.roles.update')}
        />
      </div>

      <div className="card">
        <div className="card-title">LuckPerms source <span className="sub">members of this in-game group/permission are synced into this group by the reconciliation cron; blank disables LuckPerms sync</span></div>
        <LuckpermsNodeForm groupId={id} node={group.luckpermsNode} disabled={!delegable || !hasPermission(viewer, 'groups.sync.update')} />
      </div>

      <div className="card">
        <div className="card-title">Members <span className="sub">manual grants are editable; “synced” rows are owned by the cron</span></div>
        <MemberManager groupId={id} members={members} disabled={!delegable || !hasPermission(viewer, 'groups.members.update')} />
      </div>
    </>
  );
}
