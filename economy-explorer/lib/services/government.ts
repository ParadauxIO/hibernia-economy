import 'server-only';
import { getViewer, hasPermission, requirePermission } from '@/lib/auth';
import { getGovernmentAccounts, getRecentFines, getFineTotals, countFines } from '@/lib/sql/government';
import { auditView } from '@/lib/audit';

/** Authorization stays inside the service so a new route cannot forget scope. */
export async function getGovernmentDashboard(days: number, options?: { page: number; q?: string; status?: string }) {
  const viewer = await getViewer();
  const global = hasPermission(viewer, 'government.read.all');
  if (!global) requirePermission(viewer, 'government.read');
  if (!global && !viewer.minecraftUuid) return { govAccounts: [], fines: [], total: 0, totals: { activeCount: 0, activeAmount: '0.00' } };
  const govAccounts = await getGovernmentAccounts(global ? { all: true } : { playerUuid: viewer.minecraftUuid! });
  const ids = global ? null : govAccounts.map(a => a.account_id);
  const [fines, total, totals] = await Promise.all([
    options ? getRecentFines(days, 25, ids, { ...options, offset: (options.page - 1) * 25 }) : Promise.resolve([]),
    options ? countFines(days, ids, options) : Promise.resolve(0), getFineTotals(days, ids),
  ]);
  await auditView(viewer, { path: '/government', targetType: 'global' });
  return { govAccounts, fines, totals, total };
}
