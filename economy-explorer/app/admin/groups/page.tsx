import { buildMetadata } from '@/lib/metadata';
import Link from 'next/link';
import type { Route } from 'next';
import { getViewer } from '@/lib/auth/viewer';
import { auditView } from '@/lib/audit';
import { PrivacyGate } from '@/components/PrivacyGate';
import { listGroups } from '@/lib/services/group';
import { normalizeCapability } from '@/lib/auth/capabilities';
import { fmtN } from '@/lib/format';
import { CreateGroupForm } from './create-group-form';
import { DeleteGroupButton } from './delete-group-button';

export const dynamic = 'force-dynamic';

export async function generateMetadata() {
  return buildMetadata({ title: 'Groups', description: 'Manage explorer access groups and capabilities.', path: '/admin/groups' });
}

export default async function AdminGroupsPage() {
  const viewer = await getViewer();
  if (viewer.anon) return <PrivacyGate kind="login" title="Access groups" hint="Sign in as admin to manage groups." />;
  if (viewer.role !== 'admin') return <PrivacyGate kind="private" title="Admin only" />;
  await auditView(viewer, { path: '/admin/groups', targetType: 'global' });

  const groups = await listGroups();

  return (
    <>
      <div className="page-heading">
        <h1>Access groups</h1>
        <span className="sub">{fmtN(groups.length)} groups · capabilities grant access; LuckPerms-fed groups are reconciled by the cron</span>
      </div>

      <div className="card">
        <div className="card-title">New group</div>
        <CreateGroupForm />
      </div>

      <div className="card">
        <div className="card-title">All groups</div>
        <div className="table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th>Name</th>
                <th>Capabilities</th>
                <th>LuckPerms node</th>
                <th className="amount">Members</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {groups.length === 0 && <tr><td colSpan={5} className="empty">No groups yet.</td></tr>}
              {groups.map((g) => (
                <tr key={g.groupId}>
                  <td>
                    <Link href={`/admin/groups/${g.groupId}` as Route} className="rowlink" prefetch={false}>{g.name}</Link>
                    {g.description && <div className="sub">{g.description}</div>}
                  </td>
                  <td>
                    {g.capabilities.length === 0
                      ? <span className="muted">—</span>
                      : g.capabilities.map((c) => <span key={c} className="badge" style={{ marginRight: 4 }}>{normalizeCapability(c) ?? c}</span>)}
                  </td>
                  <td>{g.luckpermsNode ? <span className="mono">{g.luckpermsNode}</span> : <span className="muted">manual only</span>}</td>
                  <td className="amount mono">
                    {fmtN(g.memberCount)}
                    {g.luckpermsMemberCount > 0 && <span className="sub"> ({fmtN(g.luckpermsMemberCount)} synced)</span>}
                  </td>
                  <td style={{ whiteSpace: 'nowrap' }}>
                    <Link href={`/admin/groups/${g.groupId}` as Route} className="btn" prefetch={false}>Manage</Link>{' '}
                    <DeleteGroupButton groupId={g.groupId} name={g.name} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </>
  );
}
