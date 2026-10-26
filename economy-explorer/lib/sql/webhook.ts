import 'server-only';
import { sql } from 'kysely';
import { db, uuidToBin, binToUuid } from '@/lib/db';
import { isDiscordWebhookUrl } from '@/lib/util/discord';

export interface WebhookRow {
  id: number;
  scope: 'account' | 'firm';
  accountId: number | null;
  firmId: number | null;
  url: string;
  active: boolean;
  consecutiveFailures: number;
  viaApiKey: boolean;
  isDiscord: boolean;
  disabledAt: Date | null;
  createdAt: Date;
}

export interface DeliveryRow {
  deliveryId: number;
  txnId: number;
  accountId: number;
  status: string;
  attempts: number;
  httpStatus: number | null;
  lastError: string | null;
  nextAttemptAt: Date;
  createdAt: Date;
}

export interface SubDbRow {
  subscription_id: number;
  api_key_id: number | null;
  account_id: number | null;
  firm_id: number | null;
  target_url: string;
  active: number;
  consecutive_failures: number;
  disabled_at: Date | null;
  created_at: Date;
}

function toRow(r: SubDbRow): WebhookRow {
  return {
    id: r.subscription_id,
    scope: r.firm_id != null ? 'firm' : 'account',
    accountId: r.account_id,
    firmId: r.firm_id,
    url: r.target_url,
    active: r.active === 1,
    consecutiveFailures: Number(r.consecutive_failures),
    viaApiKey: r.api_key_id != null,
    isDiscord: isDiscordWebhookUrl(r.target_url),
    disabledAt: r.disabled_at,
    createdAt: r.created_at,
  };
}

const SUB_COLS = sql`subscription_id, api_key_id, account_id, firm_id, target_url, active, consecutive_failures, disabled_at, created_at`;

export interface FinanceFirm {
  firmId: number;
  displayName: string;
}

/**
 * Firms the player may scope a webhook to: any firm they **proprietor** (owners
 * have implicit financial access and are not stored as firm_employee rows), or
 * one where their current employment carries FINANCIAL or ADMIN. The same
 * predicate as the financial-privacy gate (lib/sql/firm.ts
 * hasFirmFinancialAccess), so webhook scope == view scope.
 */
export async function listFinanceFirms(ownerUuid: string): Promise<FinanceFirm[]> {
  const r = await sql<{ firm_id: number; display_name: string }>`
    SELECT f.firm_id, f.display_name
    FROM firm f
    WHERE f.is_archived = 0
      AND (
        f.proprietor_uuid_bin = ${uuidToBin(ownerUuid)}
        OR EXISTS (
          SELECT 1
          FROM firm_employee fe
          JOIN firm_role_permission rp ON rp.role_id = fe.role_id AND rp.deleted_at IS NULL
          WHERE fe.firm_id = f.firm_id
            AND fe.player_uuid_bin = ${uuidToBin(ownerUuid)}
            AND fe.left_at IS NULL
            AND rp.permission IN ('FINANCIAL', 'ADMIN')
        )
      )
    ORDER BY f.display_name
  `.execute(db);
  return r.rows.map((row) => ({ firmId: row.firm_id, displayName: row.display_name }));
}

/** All of the player's webhooks (explorer- and API-key-created), newest first. */
export async function listForOwner(ownerUuid: string): Promise<WebhookRow[]> {
  const r = await sql<SubDbRow>`
    SELECT ${SUB_COLS} FROM webhook_subscription
    WHERE owner_uuid_bin = ${uuidToBin(ownerUuid)}
    ORDER BY subscription_id DESC
  `.execute(db);
  return r.rows.map(toRow);
}

/** A single webhook, owner-scoped (null if not found or not owned). */
export async function findForOwner(id: number, ownerUuid: string): Promise<WebhookRow | null> {
  const r = await sql<SubDbRow>`
    SELECT ${SUB_COLS} FROM webhook_subscription
    WHERE subscription_id = ${id} AND owner_uuid_bin = ${uuidToBin(ownerUuid)}
  `.execute(db);
  return r.rows[0] ? toRow(r.rows[0]) : null;
}

// Writes to webhook_subscription go through the REST admin API (ADT-14):
// lib/treasury.ts createWebhook / setWebhookActive / setWebhookUrl /
// setWebhookSecret / deleteWebhook. kysely is read-only here.

// ── Admin (fleet-wide, not owner-scoped) ────────────────────────────────────

export interface AdminWebhookRow extends WebhookRow {
  ownerUuid: string | null;
  ownerName: string | null;
  accountName: string | null;
  firmName: string | null;
  keyType: string;
}

export interface AdminRawRow extends SubDbRow {
  owner_uuid_bin: Buffer;
  key_type: string;
  account_name: string | null;
  firm_name: string | null;
  owner_name: string | null;
}

export function toAdminRow(r: AdminRawRow): AdminWebhookRow {
  return {
    ...toRow(r),
    ownerUuid: binToUuid(r.owner_uuid_bin), // NOT NULL (V13)
    ownerName: r.owner_name,
    accountName: r.account_name,
    firmName: r.firm_name,
    keyType: r.key_type,
  };
}

/** Joins for the admin webhook table (lib/sql/admin.ts). */
export const ADMIN_JOINS = sql`
  FROM webhook_subscription s
  LEFT JOIN accounts a ON a.account_id = s.account_id
  LEFT JOIN firm fm ON fm.firm_id = s.firm_id
  LEFT JOIN economy_players fp ON fp.player_uuid_bin = s.owner_uuid_bin`;

// Admin webhook writes also go through the REST admin API (ADT-14): the fleet-wide
// create/setActive/delete map to the same endpoints with no ownerUuid scope.

/** Recent deliveries for a webhook — owner-scoped via the subscription join. */
export async function listRecentDeliveries(
  subscriptionId: number,
  ownerUuid: string,
  limit = 50,
): Promise<DeliveryRow[]> {
  const r = await sql<{
    delivery_id: number;
    txn_id: number;
    account_id: number;
    status: string;
    attempts: number;
    http_status: number | null;
    last_error: string | null;
    next_attempt_at: Date;
    created_at: Date;
  }>`
    SELECT d.delivery_id, d.txn_id, d.account_id, d.status, d.attempts,
           d.http_status, d.last_error, d.next_attempt_at, d.created_at
    FROM webhook_delivery d
    JOIN webhook_subscription s ON s.subscription_id = d.subscription_id
    WHERE d.subscription_id = ${subscriptionId} AND s.owner_uuid_bin = ${uuidToBin(ownerUuid)}
    ORDER BY d.delivery_id DESC
    LIMIT ${limit}
  `.execute(db);
  return r.rows.map((row) => ({
    deliveryId: row.delivery_id,
    txnId: row.txn_id,
    accountId: row.account_id,
    status: row.status,
    attempts: Number(row.attempts),
    httpStatus: row.http_status,
    lastError: row.last_error,
    nextAttemptAt: row.next_attempt_at,
    createdAt: row.created_at,
  }));
}
