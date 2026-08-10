import 'server-only';

/**
 * Server-only client for the treasury-rest-api **admin** surface (the ledger-
 * authoritative tier). The explorer never moves money or archives accounts
 * directly — it calls these SERVICE-scoped endpoints, which run the faithful
 * disband/rename control flow in one DB transaction. Configured per environment
 * via TREASURY_API_BASE_URL + TREASURY_ADMIN_TOKEN (k8s secret).
 */
const BASE = process.env.TREASURY_API_BASE_URL?.replace(/\/+$/, '');
const TOKEN = process.env.TREASURY_ADMIN_TOKEN;

export interface DisbandedAccount {
  accountId: number;
  sweptAmount: string | null;
  toAccountId: number | null;
  archived: boolean;
}
export interface DisbandResult {
  firmId: number;
  displayName: string;
  archived: boolean;
  accounts: DisbandedAccount[];
}
export interface FirmResult {
  firmId: number;
  displayName: string;
  archived: boolean;
}

export function treasuryAdminConfigured(): boolean {
  return !!(BASE && TOKEN);
}

async function call<T>(path: string, method: string, body?: unknown): Promise<T> {
  if (!BASE || !TOKEN) {
    throw new Error('Treasury admin API is not configured (TREASURY_API_BASE_URL / TREASURY_ADMIN_TOKEN).');
  }
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: { authorization: `Bearer ${TOKEN}`, 'content-type': 'application/json' },
    body: body !== undefined ? JSON.stringify(body) : undefined,
    cache: 'no-store',
  });
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    // The API returns { "error": "CODE", "message": "..." }; surface the message.
    let message = text;
    try {
      const parsed = JSON.parse(text);
      if (parsed && typeof parsed.message === 'string') message = parsed.message;
      else if (parsed && typeof parsed.error === 'string') message = parsed.error;
    } catch {
      /* not JSON — keep raw text */
    }
    throw new Error(message || `Treasury API request failed (${res.status}).`);
  }
  return (await res.json()) as T;
}

export interface TransferResult {
  txnId: number;
  fromAccountId: number;
  toAccountId: number;
  amount: string;
  memo: string;
}
export interface AccountResult {
  accountId: number;
  accountType: string;
  displayName: string | null;
  archived: boolean;
  ownerUuid: string | null;
}

/** Disband a firm: sweep balances → archive accounts → soft-delete links → archive firm. */
export const disbandFirm = (firmId: number) =>
  call<DisbandResult>(`/api/v1/admin/firms/${firmId}/disband`, 'POST');

/** Rename a firm (backend applies the in-game name rules + uniqueness). */
export const renameFirm = (firmId: number, newName: string) =>
  call<FirmResult>(`/api/v1/admin/firms/${firmId}/rename`, 'POST', { newName });

/** Update a firm's business details (HQ region, Discord URL). */
export const updateFirmDetails = (firmId: number, body: { discordUrl?: string | null; hqRegion?: string | null }) =>
  call<FirmResult>(`/api/v1/admin/firms/${firmId}`, 'PATCH', body);

/** Move money between two arbitrary accounts with a memo. */
export const adminTransfer = (body: { fromAccountId: number; toAccountId: number; amount: string; memo: string }) =>
  call<TransferResult>(`/api/v1/admin/transfers`, 'POST', body);

/** Rename any account. */
export const renameAccount = (accountId: number, displayName: string) =>
  call<AccountResult>(`/api/v1/admin/accounts/${accountId}/display-name`, 'PATCH', { displayName });

/** Change an account's owner (UUID or player name). */
export const changeAccountOwner = (accountId: number, owner: string) =>
  call<AccountResult>(`/api/v1/admin/accounts/${accountId}/owner`, 'PATCH', { owner });

/** Archive / unarchive an account. */
export const archiveAccount = (accountId: number) =>
  call<AccountResult>(`/api/v1/admin/accounts/${accountId}/archive`, 'POST');
export const unarchiveAccount = (accountId: number) =>
  call<AccountResult>(`/api/v1/admin/accounts/${accountId}/unarchive`, 'POST');

/** Like {@link call} but for 204 No Content responses (no body to parse). */
async function callVoid(path: string, method: string, body?: unknown): Promise<void> {
  if (!BASE || !TOKEN) {
    throw new Error('Treasury admin API is not configured (TREASURY_API_BASE_URL / TREASURY_ADMIN_TOKEN).');
  }
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: { authorization: `Bearer ${TOKEN}`, 'content-type': 'application/json' },
    body: body !== undefined ? JSON.stringify(body) : undefined,
    cache: 'no-store',
  });
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    let message = text;
    try {
      const parsed = JSON.parse(text);
      if (parsed && typeof parsed.message === 'string') message = parsed.message;
      else if (parsed && typeof parsed.error === 'string') message = parsed.error;
    } catch {
      /* not JSON — keep raw text */
    }
    throw new Error(message || `Treasury API request failed (${res.status}).`);
  }
}

/** Set a per-issuer rate-limit multiplier override (admin). */
export const setRateLimitOverride = (ownerUuid: string, multiplier: string, note: string | null) =>
  callVoid(`/api/v1/admin/rate-limit-overrides/${encodeURIComponent(ownerUuid)}`, 'PUT', { multiplier, note });

/** Clear a per-issuer rate-limit multiplier override (admin). */
export const clearRateLimitOverride = (ownerUuid: string) =>
  callVoid(`/api/v1/admin/rate-limit-overrides/${encodeURIComponent(ownerUuid)}`, 'DELETE');

/** Revoke an API key (admin). */
export const revokeApiKey = (keyId: number) =>
  callVoid(`/api/v1/admin/api-keys/${keyId}/revoke`, 'POST');

/** Force-rotate an API key (admin) — invalidates the current token; owner reissues in-game. */
export const rotateApiKey = (keyId: number) =>
  call<{ expiresAt: string }>(`/api/v1/admin/api-keys/${keyId}/rotate`, 'POST');

// ── Webhook subscriptions (ADT-14). The optional ownerUuid scopes a mutation to
//    that owner's row (player self-service) vs. by-id (fleet admin). ──
// encodeURIComponent the interpolated identifier so a non-canonical value can't
// break or alter the request URL (ADT owneruuid-query-not-encoded / owneruuid-url-injection).
const ownerQ = (ownerUuid?: string) => (ownerUuid ? `?ownerUuid=${encodeURIComponent(ownerUuid)}` : '');

export const createWebhook = (body: {
  ownerUuid: string;
  keyType: 'PERSONAL' | 'BUSINESS' | 'GOVERNMENT';
  accountId: number | null;
  firmId: number | null;
  targetUrl: string;
  secret: string;
}) => call<{ subscriptionId: number }>(`/api/v1/admin/webhooks`, 'POST', body);

export const setWebhookActive = (id: number, active: boolean, ownerUuid?: string) =>
  call<{ affected: number }>(`/api/v1/admin/webhooks/${id}/active${ownerQ(ownerUuid)}`, 'PATCH', { active });

export const setWebhookUrl = (id: number, targetUrl: string, ownerUuid?: string) =>
  call<{ affected: number }>(`/api/v1/admin/webhooks/${id}/url${ownerQ(ownerUuid)}`, 'PATCH', { targetUrl });

export const setWebhookSecret = (id: number, secret: string, ownerUuid?: string) =>
  call<{ affected: number }>(`/api/v1/admin/webhooks/${id}/secret${ownerQ(ownerUuid)}`, 'PATCH', { secret });

export const deleteWebhook = (id: number, ownerUuid?: string) =>
  call<{ affected: number }>(`/api/v1/admin/webhooks/${id}${ownerQ(ownerUuid)}`, 'DELETE');
