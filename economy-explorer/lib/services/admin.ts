import 'server-only';
import { requirePermission } from '@/lib/auth/access';
import type { Viewer } from '@/lib/auth/viewer';
import { queryKeyUsage, type KeyUsage } from '@/lib/prometheus';
import { unstable_cache } from 'next/cache';
import {
  countAdminApiKeys,
  countAdminPlayers,
  countAdminWebhooks,
  findPlayerNamesByUuid,
  listAdminApiKeyPage,
  listAdminPlayers,
  listAdminWebhooks,
  listAdminGroups,
  listAdminRoles,
  type AdminApiKeyRow,
  type AdminPlayerRow,
  type AdminRoleRow,
} from '@/lib/sql/admin';
import type { GroupSummary } from '@/lib/sql/group';
import { auditView } from '@/lib/audit';
import { countAccounts, listAccounts, type ExplorerAccountRow } from '@/lib/sql/ledger';
import { findFirmNames } from '@/lib/sql/tableLookups';
import type { AdminWebhookRow } from '@/lib/sql/webhook';
import type { AccountsTableState } from '@/lib/tables/accounts';
import type { AdminApiKeysState, AdminGroupsState, AdminPlayersState, AdminRolesState, AdminWebhooksState } from '@/lib/tables/admin';

// The admin list pages' service layer: one permission check per list, then the
// lib/sql/admin queries. Pages call these, never the DAL.

export interface Page<R> { rows: R[]; total: number }

export async function getAdminPlayers(viewer: Viewer, state: AdminPlayersState): Promise<Page<AdminPlayerRow>> {
  requirePermission(viewer, 'player.read.all');
  const [rows, total] = await Promise.all([listAdminPlayers(state), countAdminPlayers(state)]);
  return { rows, total };
}

/** The accounts directory for admins: archived accounts included unless a chip says otherwise. */
export async function getAdminAccounts(viewer: Viewer, state: AccountsTableState): Promise<Page<ExplorerAccountRow> & { entityLabels: Record<string, string> }> {
  requirePermission(viewer, 'account.admin.read');
  const firmIds = state.filters.filter((f) => f.field === 'firm').flatMap((f) => f.values.map(Number));
  const [rows, total, firms] = await Promise.all([
    listAccounts({ state, limit: state.limit, offset: (state.page - 1) * state.limit, includeArchived: true }),
    countAccounts({ state, includeArchived: true }),
    findFirmNames(firmIds),
  ]);
  const entityLabels: Record<string, string> = {};
  for (const [id, name] of firms) entityLabels[`firm:${id}`] = name;
  return { rows, total, entityLabels };
}

export async function getAdminWebhooks(viewer: Viewer, state: AdminWebhooksState): Promise<Page<AdminWebhookRow>> {
  requirePermission(viewer, 'webhooks.read.all');
  const [rows, total] = await Promise.all([listAdminWebhooks(state), countAdminWebhooks(state)]);
  return { rows, total };
}

// Per-key usage comes from Prometheus, which is spiky under load. Cache it 30s
// (slightly-stale usage stats are fine) so a slow Prometheus stops blocking the
// page on every hit. unstable_cache can't round-trip a Map through the data
// cache, so we cache entries and rebuild the Map below. One tenant per pod.
const getCachedKeyUsage = unstable_cache(
  async (): Promise<[number, KeyUsage][]> => Array.from((await queryKeyUsage()).entries()),
  ['api-key-usage'],
  { revalidate: 30 },
);

export async function getAdminApiKeys(viewer: Viewer, state: AdminApiKeysState): Promise<Page<AdminApiKeyRow> & {
  active: number;
  usage: Map<number, KeyUsage>;
  entityLabels: Record<string, string>;
}> {
  requirePermission(viewer, 'api_keys.read');
  const owners = state.filters.filter((f) => f.field === 'owner').flatMap((f) => f.values);
  const [rows, counts, usage, names] = await Promise.all([
    listAdminApiKeyPage(state),
    countAdminApiKeys(state),
    getCachedKeyUsage(),
    findPlayerNamesByUuid(owners),
  ]);
  const entityLabels: Record<string, string> = {};
  for (const [uuid, name] of names) entityLabels[`owner:${uuid}`] = name;
  return { rows, total: counts.total, active: counts.active, usage: new Map(usage), entityLabels };
}

/** Access groups for /admin/groups. Few rows, so no paging. */
export async function getAdminGroups(viewer: Viewer, state: AdminGroupsState): Promise<GroupSummary[]> {
  requirePermission(viewer, 'groups.read');
  return listAdminGroups(state);
}

/** Role definitions for /admin/roles, with permission, member and group counts. */
export async function getAdminRoles(viewer: Viewer, state: AdminRolesState): Promise<AdminRoleRow[]> {
  requirePermission(viewer, 'roles.read');
  await auditView(viewer, { path: '/admin/roles', targetType: 'global' });
  return listAdminRoles(state);
}
