---
title: Access and credentials
order: 7
description: Recover website access, manage player API keys, rotate SERVICE tokens and signing secrets, and diagnose API refusals.
---

# Access and credentials

Website sessions, Minecraft identity links, Explorer roles and Treasury API tokens
are separate. First identify which layer failed; changing a role cannot repair
an expired token, and resetting a password does not revoke API keys.

## Login and linking recovery

1. Confirm the tenant URL and OIDC issuer/client/callback settings. A failed sign-in
   belongs to the identity/proxy layer; a successful login without personal data
   usually needs [Minecraft linking](/docs/guides/using-the-explorer#step-2--link-your-minecraft-account).
2. Generate a fresh link code and have the player run `/treasuryapi ui link <code>`
   themselves within five minutes. Verify the linked Minecraft name afterward.
   Do not attach a claimant to an arbitrary UUID or bypass a conflicting identity.
3. For an already linked player who forgot their password, use the
   [self-service reset](/docs/guides/using-the-explorer#recover-your-website-password).
   Missing or ambiguous links need staff investigation; the command does not guess.
4. If data is still unavailable, check the actual role permissions, direct grants,
   manual and LP group memberships, and the account/firm relationship. Removing
   one grant does not revoke another path. `/treasuryapi ui sync` refreshes the
   requesting player's LP relationships. Check reconciliation errors before
   bypassing access with a global role.

For password reset, enable TreasuryAPI's Keycloak integration and a service
account permitted to manage/reset the target users. The realm must allow local
passwords and the update-password required action without requiring email.
Minecraft authentication is the recovery credential: use authenticated online
mode or a securely authenticated proxy. The command is player-only, takes no
username argument, and limits confirmed attempts to one per minute per plugin
instance. The temporary password requires replacement at login; it has no separate
time-based expiry and may remain in the player's private client log.

Verify rollout with a disposable linked account: reset, reject the old password,
complete the required password change, then reject the temporary password.
Reset does not explicitly revoke Keycloak or Explorer sessions. For a compromised
identity, also revoke sessions in the appropriate identity/session system and
revoke affected API keys. See the detailed
[password-recovery implementation notes](https://github.com/ParadauxIO/hibernia-economy/blob/develop/wiki/password-recovery.md).

## Recovering staff access

Use a second administrator to restore the missing permission bundle. Trusted
console or `treasuryapi.ui.user` can assign an **existing configured role**:

```text
/treasuryapi ui user list <player>
/treasuryapi ui user add <role-key> <player>
/treasuryapi ui user remove <role-key> <player>
```

A role named `admin` is not a bypass: if its stored permission bundle was emptied,
assigning it again does not repair the bundle. Use an independent administrator
or an explicitly reviewed database recovery from the saved role definitions.
Check both permitted and denied pages after recovery. See [roles](/docs/admin/roles).

## API keys

[Admin → API keys](/admin/api-keys) requires `api_keys.read`. Verify the key ID,
issuer, personal/firm scope, expiry and revocation state before acting.

| Action | Permission | Result and check |
|---|---|---|
| Revoke | `api_keys.revoke` | Invalidates the key; verify an authenticated request with the old token fails. The owner issues a replacement if needed. |
| Force rotate | `api_keys.rotate` | Replaces the stored token identifier and expiry, invalidating the old token. **No new token is shown or minted by Explorer.** The owner reissues in-game. |
| Issuer limit | `rate_limits.update` | Multiplies the issuer's allowances, affecting their keys. UI clamps to 0.10–1000. Record a reason; multiplier 1 with an empty note removes the override. Verify the saved value and response headers. |

Owners use `/treasuryapi personal list|reissue|revoke` or the business equivalents
in the [command reference](/docs/reference/treasury-api). Tokens are shown once
and cannot be recovered from storage. Check expiry before it interrupts an app;
REST `POST /api/v1/auth/rotate` requires a still-valid token.

## SERVICE token creation and renewal

The SERVICE token grants REST administrative authority and belongs only on the
Explorer server. Players cannot issue or manage it through in-game key commands.
Use the repository's
[mint script](https://github.com/ParadauxIO/hibernia-economy/blob/develop/economy-explorer/scripts/mint-service-token.mjs).
It does not connect to the database: minting alone changes nothing.

1. Select the correct tenant and operator Minecraft UUID. This UUID becomes the
   ledger initiator for service transfers. Inspect existing SERVICE rows using a
   private database client:

   ```sql
   SELECT key_id, HEX(owner_uuid_bin) AS owner_hex, revoked, expires_at
   FROM api_keys WHERE key_type = 'SERVICE';
   ```

2. For first-time setup only, run `node economy-explorer/scripts/mint-service-token.mjs
   --create --owner <uuid>`, inspect its generated INSERT, apply it to the correct
   tenant and record `key_id`. For renewal, use the existing non-revoked row.
3. Load the REST service's current `JWT_SECRET` securely into the command environment.
   Do not put its value in a command argument or a ticket. In a private working
   directory outside version control:

   ```bash
   umask 077
   node /path/to/hibernia-economy/economy-explorer/scripts/mint-service-token.mjs \
     --key-id <key-id> --owner <uuid> --days 180 > token.txt 2> apply.sql
   ```

4. Check the command succeeded; inspect the generated SQL without publishing the
   token. Apply `apply.sql` and require **one affected row**. Zero means the owner,
   ID, key type or revoked state is wrong; stop and resolve it.
5. The SQL invalidates the previous token for that row. Update `TREASURY_ADMIN_TOKEN`
   in the authoritative secret store from `token.txt` and restart Explorer as a
   coordinated change. Schedule renewal before the recorded expiry.
6. Verify an authenticated `GET /api/v1/auth/me` against the intended API and an
   authorized admin journey on staging. Verify the superseded token is rejected.
   Remove temporary token/SQL files using your secret-handling procedure.

For minimal interruption, prepare a separate SERVICE row/token, switch and verify
Explorer, then revoke the old row through the supported admin key operation.
Keep the old credential only until the replacement is verified; a failed cutover
can switch back while the old row is still valid. Do not roll back to a credential
known to be compromised.

## Shared signing-secret rotation

This invalidates **all** existing tokens, including SERVICE tokens. Coordinate a
maintenance window and notify integration owners. Generate random material (for
example `openssl rand -base64 48`) and store it as `base64:<value>` in both
TreasuryAPI `api.jwt-secret` and REST `JWT_SECRET`. Restart both together, mint/apply
the SERVICE token using the new secret and update Explorer. Owners reissue personal
and business tokens in-game. Verify new tokens succeed and old tokens fail.

If rollback is necessary, restore a consistent signing secret and matching token
rows/credentials across consumers. Restoring only the secret does not undo token
identifier updates. Keep a record of which rows changed; never blindly restore
an old whole database over new financial transactions to repair credentials.

## API errors and rate limits

| Symptom | Check and response |
|---|---|
| `401` / expired, revoked or superseded token | Check expiry, key ID/state, issuer and shared signing secret. Reissue/renew through the appropriate path. |
| `403` | Check token scope and current account/firm authority; adding a website role does not expand a personal/business token. |
| Admin `404`, public rates still work | Remove a duplicate `/api/v1` from `TREASURY_API_BASE_URL`; check gateway prefix. |
| `409` idempotency conflict | The same source-account key was reused with a different request. Recover the original operation; use a new key only for a genuinely new transfer. |
| `429` | Honor `Retry-After`; investigate issuer/route load before changing multipliers. Additional keys are not independent allowances. |
| `503` on a transfer | Check shared rate-limit Redis and service logs. Money endpoints fail closed when the limiter is unavailable. Inspect the ledger before retrying. |

Read-only retries can back off. Transfer clients should retain the same
`Idempotency-Key` and identical request body for retries of one operation; see
[API integration guidance](/docs/guides/api#safe-transfers-and-retries).
