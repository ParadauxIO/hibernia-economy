import 'server-only';
import { getViewer, hasPermission, requirePermission } from '@/lib/auth';
import { getGovernmentAccounts, getFineCategorySummary, getRecentFines } from '@/lib/sql/government';
import { auditView } from '@/lib/audit';

/** Authorization stays inside the service so a new route cannot forget scope. */
export async function getGovernmentDashboard(days: number) {
  const viewer = await getViewer();
  const global = hasPermission(viewer, 'government.read.all');
  if (!global) requirePermission(viewer, 'government.read');
  if (!global && !viewer.minecraftUuid) return { govAccounts: [], cats: [], fines: [] };
  const govAccounts = await getGovernmentAccounts(global ? { all: true } : { playerUuid: viewer.minecraftUuid! });
  const ids = global ? null : govAccounts.map(a => a.account_id);
  const [cats, fines] = await Promise.all([getFineCategorySummary(days, ids), getRecentFines(days, 100, ids)]);
  await auditView(viewer, { path: '/government', targetType: 'global' });
  return { govAccounts, cats, fines };
}
