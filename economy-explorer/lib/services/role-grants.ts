import 'server-only';
import { getViewer } from '@/lib/auth/viewer';
import { requirePermission } from '@/lib/auth/access';
import { auditView } from '@/lib/audit';
import { listRoleGrants, type GrantFilters } from '@/lib/sql/role-grants';

export async function listRoleGrantsForAdmin(filters: GrantFilters) {
  const viewer = await getViewer();
  requirePermission(viewer, 'roles.read');
  requirePermission(viewer, 'groups.read');
  await auditView(viewer, { path: '/admin/role-grants', targetType: 'global' });
  return listRoleGrants(filters);
}
