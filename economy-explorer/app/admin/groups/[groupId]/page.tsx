import { buildMetadata } from '@/lib/metadata';
import { notFound } from 'next/navigation';
import { getViewer } from '@/lib/auth/viewer';
import { auditView } from '@/lib/audit';
import { PrivacyGate } from '@/components/PrivacyGate';
import { BackLink } from '@/components/BackLink';
import { getGroup, listGroupMembers } from '@/lib/services/group';
import { CAPABILITIES, CAPABILITY_LABELS, CAPABILITY_DESCRIPTIONS, normalizeCapability } from '@/lib/auth/capabilities';
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
  if (viewer.anon) return <PrivacyGate kind="login" title="Access groups" hint="Sign in as admin to manage groups." />;
  if (viewer.role !== 'admin') return <PrivacyGate kind="private" title="Admin only" />;

  const { groupId } = await params;
  const id = Number(groupId);
  if (!Number.isInteger(id)) notFound();

  const group = await getGroup(id);
  if (!group) notFound();
  await auditView(viewer, { path: `/admin/groups/${id}`, targetType: 'global', targetId: `group:${id}` });

  const members = await listGroupMembers(id);

  return (
    <>
      <BackLink href="/admin/groups" label="Groups" />
      <div className="page-heading">
        <h1>{group.name}</h1>
        {group.description && <span className="sub">{group.description}</span>}
      </div>

      <div className="card">
        <div className="card-title">Permissions <span className="sub">tick what membership of this group grants — saved to this group and applied to every member</span></div>
        <CapabilitiesForm
          groupId={id}
          all={CAPABILITIES.map((c) => ({ value: c, label: CAPABILITY_LABELS[c], description: CAPABILITY_DESCRIPTIONS[c] }))}
          // Normalize legacy aliases (e.g. 'staff.audit' → 'viewer') so a pre-rename
          // row renders as its current checkbox; saving then rewrites it canonically.
          selected={group.capabilities.map((c) => normalizeCapability(c) ?? c)}
        />
      </div>

      <div className="card">
        <div className="card-title">LuckPerms source <span className="sub">members of this in-game group/permission are synced into this group by the reconciliation cron; blank = manual only</span></div>
        <LuckpermsNodeForm groupId={id} node={group.luckpermsNode} />
      </div>

      <div className="card">
        <div className="card-title">Members <span className="sub">manual grants are editable; “synced” rows are owned by the cron</span></div>
        <MemberManager groupId={id} members={members} />
      </div>
    </>
  );
}
