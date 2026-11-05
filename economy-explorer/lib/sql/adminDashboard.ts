import 'server-only';
import { sql } from 'kysely';
import { db } from '@/lib/db';
import { AUDIT_ACTIONS } from '@/lib/tables/audit';
import { listLatestAuditByAction, type AuditEntry } from '@/lib/sql/audit';

// Health figures for the /admin dashboard. Every table here is small (a few
// hundred subscriptions, ~1k keys) or read through an index:
//   webhook_delivery → idx_delivery_due (status, next_attempt_at)
//   explorer_audit   → idx_audit_at (at), idx_audit_action (action, at)

export interface WebhookHealth {
  live: number;
  failing: number;
  disabled: number;
  /** PENDING deliveries past their next attempt that the dispatcher should be sending. */
  overdue: number;
  oldestOverdueAt: Date | null;
  /**
   * Deliveries to GOVERNMENT webhooks held while treasury.government_api_keys
   * is off. The dispatcher queues them on purpose and sends them on re-enable.
   */
  paused: number;
  /** Ledger transactions the dispatcher has not scanned yet. */
  dispatcherLag: number;
  dispatcherAt: Date | null;
}

export async function getWebhookHealth(governmentEnabled: boolean): Promise<WebhookHealth> {
  const [subs, due, cursor] = await Promise.all([
    sql<{ live: string | number | null; failing: string | number | null; disabled: string | number | null }>`
      SELECT SUM(s.active = 1 AND s.disabled_at IS NULL) AS live,
             SUM(s.active = 1 AND s.disabled_at IS NULL AND s.consecutive_failures > 0) AS failing,
             SUM(s.disabled_at IS NOT NULL) AS disabled
      FROM webhook_subscription s
    `.execute(db),
    // Mirrors the dispatcher's findDue gate (treasury-rest-api WebhookDeliveryMapper).
    sql<{ paused: number; c: string | number; oldest: Date | null }>`
      SELECT (s.key_type = 'GOVERNMENT' AND ${governmentEnabled ? 0 : 1} = 1) AS paused,
             COUNT(*) AS c, MIN(d.next_attempt_at) AS oldest
      FROM webhook_delivery d
      JOIN webhook_subscription s ON s.subscription_id = d.subscription_id
      WHERE d.status = 'PENDING' AND d.next_attempt_at <= NOW() AND s.active = 1 AND s.disabled_at IS NULL
      GROUP BY paused
    `.execute(db),
    sql<{ lag: string | number | null; updated_at: Date | null }>`
      SELECT (SELECT MAX(lt.txn_id) FROM ledger_txns lt) - c.last_dispatched_txn_id AS lag, c.updated_at
      FROM webhook_cursor c WHERE c.id = 1
    `.execute(db),
  ]);
  const s = subs.rows[0];
  const sending = due.rows.find((r) => Number(r.paused) === 0);
  const held = due.rows.find((r) => Number(r.paused) === 1);
  return {
    live: Number(s?.live ?? 0),
    failing: Number(s?.failing ?? 0),
    disabled: Number(s?.disabled ?? 0),
    overdue: Number(sending?.c ?? 0),
    oldestOverdueAt: sending?.oldest ?? null,
    paused: Number(held?.c ?? 0),
    dispatcherLag: Math.max(0, Number(cursor.rows[0]?.lag ?? 0)),
    dispatcherAt: cursor.rows[0]?.updated_at ?? null,
  };
}

export interface ApiKeyHealth {
  active: number;
  /** Unrevoked keys that expire within `days`. */
  expiringSoon: number;
}

export async function getApiKeyHealth(days: number): Promise<ApiKeyHealth> {
  const r = await sql<{ active: string | number | null; expiring: string | number | null }>`
    SELECT SUM(k.expires_at > NOW()) AS active,
           SUM(k.expires_at > NOW() AND k.expires_at <= NOW() + INTERVAL ${days} DAY) AS expiring
    FROM api_keys k WHERE k.revoked = 0
  `.execute(db);
  return { active: Number(r.rows[0]?.active ?? 0), expiringSoon: Number(r.rows[0]?.expiring ?? 0) };
}

export interface DeniedRequests {
  count: number;
  actors: number;
}

/** Requests the explorer refused (403) since `since`. */
export async function countDeniedRequests(since: Date): Promise<DeniedRequests> {
  const r = await sql<{ c: string | number; actors: string | number }>`
    SELECT COUNT(*) AS c, COUNT(DISTINCT e.actor_sub) AS actors
    FROM explorer_audit e WHERE e.at >= ${since} AND e.outcome = 403
  `.execute(db);
  return { count: Number(r.rows[0]?.c ?? 0), actors: Number(r.rows[0]?.actors ?? 0) };
}

/** Audit actions that change something, as opposed to reads and exports. */
export const CHANGE_ACTIONS = AUDIT_ACTIONS.map((a) => a.action).filter((a) => !['view', 'relationship.read', 'export', 'other'].includes(a));

/** The latest audited changes, newest first. */
export function listRecentChanges(limit: number): Promise<AuditEntry[]> {
  return listLatestAuditByAction(CHANGE_ACTIONS, limit);
}
