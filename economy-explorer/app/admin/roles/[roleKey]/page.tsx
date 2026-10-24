import Link from 'next/link';
import { notFound } from 'next/navigation';
import { getViewer, hasPermission } from '@/lib/auth';
import { listRolesForAdmin, listDirectRoleMembers } from '@/lib/services/role';
import { PrivacyGate } from '@/components/PrivacyGate';
import { RoleEditor } from '../role-editor';
import { RoleMembers } from './members';
export const dynamic = 'force-dynamic';
export default async function RolePage({ params }: { params: Promise<{ roleKey: string }> }) {
  const viewer = await getViewer();
  if (!hasPermission(viewer, 'roles.read')) return <PrivacyGate kind={viewer.anon ? 'login' : 'private'} title="Role access required" />;
  const { roleKey } = await params;
  const role = (await listRolesForAdmin()).find(role => role.key === roleKey);
  if (!role) notFound();
  const members = await listDirectRoleMembers(role.key);
  return <>
    <div className="page-heading"><h1>{role.name}</h1><Link href="/admin/roles">All roles</Link></div>
    <div className="card"><div className="card-title">Permissions</div><RoleEditor role={role} actorPermissions={viewer.permissions} /></div>
    <div className="card"><div className="card-title">Direct assignments</div><RoleMembers roleKey={role.key} members={members}
      canAssign={hasPermission(viewer, 'roles.assign') && role.permissions.every(p => viewer.permissions.includes(p))} /></div>
  </>;
}
