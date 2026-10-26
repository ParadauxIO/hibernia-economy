import 'server-only';
import { getViewer } from '@/lib/auth/viewer';
import { requirePermission } from '@/lib/auth/access';
import { auditView } from '@/lib/audit';
import { countRoleGrants, listRoleGrants, type GrantFilters, type GrantSort } from '@/lib/sql/role-grants';
import type { AdminRoleGrantsState } from '@/lib/tables/admin';

/** Table state → the DAL's filters. Text chips match the role key exactly. */
export function grantFiltersFor(state: AdminRoleGrantsState): GrantFilters {
  const filters: GrantFilters = { q: state.q, page: state.page, pageSize: state.limit, sort: state.sort.field as GrantSort, dir: state.sort.dir };
  for (const f of state.filters) {
    if (f.field === 'role' && f.values[0]) filters.role = f.values[0];
    if (f.field === 'source') filters.sources = f.values;
    // Both statuses selected is the same as no status filter.
    if (f.field === 'status' && f.values.length === 1 && f.values[0]) filters.status = f.values[0];
  }
  return filters;
}

export async function listRoleGrantsForAdmin(state: AdminRoleGrantsState) {
  const viewer = await getViewer();
  requirePermission(viewer, 'roles.read');
  requirePermission(viewer, 'groups.read');
  await auditView(viewer, { path: '/admin/role-grants', targetType: 'global' });
  const filters = grantFiltersFor(state);
  const [page, total] = await Promise.all([listRoleGrants(filters), countRoleGrants(filters)]);
  return { ...page, total };
}
