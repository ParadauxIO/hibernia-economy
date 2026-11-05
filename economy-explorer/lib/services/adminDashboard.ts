import 'server-only';
import { memo } from '@/lib/cache';
import { requirePermission } from '@/lib/auth/access';
import type { Viewer } from '@/lib/auth/viewer';
import { countDeniedRequests, getApiKeyHealth, getWebhookHealth, listRecentChanges, type ApiKeyHealth, type DeniedRequests, type WebhookHealth } from '@/lib/sql/adminDashboard';
import type { AuditEntry } from '@/lib/sql/audit';
import { listFeatureFlags, type FeatureFlagRow } from '@/lib/sql/featureFlags';
import { countTxnsByPlugin, listLedgerPlugins } from '@/lib/sql/ledger';

// One reader per /admin dashboard panel. Each checks the permission of the tool
// it summarises, so a panel never shows more than the tool behind it would.
// Figures are cached briefly: the dashboard is a glance, not a live monitor.

const TTL_MS = 60_000;
const DAY_MS = 86_400_000;
export const KEY_EXPIRY_DAYS = 14;

export function getDashboardWebhooks(viewer: Viewer): Promise<WebhookHealth> {
  requirePermission(viewer, 'webhooks.read.all');
  return memo('admin-dashboard:webhooks', TTL_MS, getWebhookHealth);
}

export function getDashboardApiKeys(viewer: Viewer): Promise<ApiKeyHealth> {
  requirePermission(viewer, 'api_keys.read');
  return memo('admin-dashboard:api-keys', TTL_MS, () => getApiKeyHealth(KEY_EXPIRY_DAYS));
}

export function getDashboardDenied(viewer: Viewer): Promise<DeniedRequests> {
  requirePermission(viewer, 'audit.read');
  return memo('admin-dashboard:denied', TTL_MS, () => countDeniedRequests(new Date(Date.now() - DAY_MS)));
}

export function getDashboardChanges(viewer: Viewer, limit: number): Promise<AuditEntry[]> {
  requirePermission(viewer, 'audit.read');
  return listRecentChanges(limit);
}

export function getDashboardFlags(viewer: Viewer): Promise<FeatureFlagRow[]> {
  requirePermission(viewer, 'feature_flags.read');
  return listFeatureFlags();
}

export interface LedgerWriter {
  plugin: string | null;
  lastAt: Date;
  last24h: number;
}

/** Every plugin that has written to the ledger, with its latest settlement and the last day's volume. */
export function getDashboardLedgerWriters(viewer: Viewer): Promise<LedgerWriter[]> {
  requirePermission(viewer, 'transactions.read');
  return memo('admin-dashboard:ledger-writers', TTL_MS, async () => {
    const plugins = await listLedgerPlugins();
    const counts = await countTxnsByPlugin(plugins.map((p) => p.plugin_system), new Date(Date.now() - DAY_MS));
    return plugins.map((p) => ({ plugin: p.plugin_system, lastAt: p.last_at, last24h: counts.get(p.plugin_system) ?? 0 }));
  });
}
