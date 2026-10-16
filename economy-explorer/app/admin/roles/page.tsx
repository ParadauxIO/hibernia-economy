import Link from 'next/link';
import { getViewer, hasPermission } from '@/lib/auth';
import { listRolesForAdmin } from '@/lib/services/role';
import { PrivacyGate } from '@/components/PrivacyGate';
import { RoleEditor } from './role-editor';
import { TableWrap } from '@/components/TableWrap';
export const dynamic = 'force-dynamic';
export default async function RolesPage() {
  const viewer = await getViewer();
  if (!hasPermission(viewer, 'roles.read')) return <PrivacyGate kind={viewer.anon ? 'login' : 'private'} title="Role access required" />;
  const roles = await listRolesForAdmin();
  return <>
    <div className="page-heading"><h1>Roles</h1><span className="sub">Configure permissions and direct assignments. Groups can grant any defined role.</span></div>
    <div className="card"><TableWrap label="Roles"><table className="data-table">
      <thead><tr><th>Role</th><th>Description</th><th>Permissions</th></tr></thead>
      <tbody>{roles.map(role => <tr key={role.key}>
        <td><Link href={`/admin/roles/${encodeURIComponent(role.key)}`} prefetch={false}>{role.name}</Link><div className="sub">{role.key}</div></td>
        <td>{role.description}</td><td>{role.permissions.length}</td>
      </tr>)}</tbody>
    </table></TableWrap></div>
    {hasPermission(viewer, 'roles.create') && <div className="card"><div className="card-title">Create role</div><RoleEditor actorPermissions={viewer.permissions} /></div>}
  </>;
}
