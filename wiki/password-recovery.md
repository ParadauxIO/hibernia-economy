# In-game password recovery

Players with an existing Minecraft-to-Keycloak account link can reset their web
password without an email address:

1. Run `/treasuryapi ui resetpassword` to see the confirmation instructions.
2. Run `/treasuryapi ui resetpassword confirm` to replace the current password.
3. Copy the temporary password from the private command response, sign in to the
   web application with the existing username, and choose a permanent password
   when Keycloak prompts.

The command takes no username, UUID, or password argument. It derives the account
from the authenticated player's UUID and `explorer_identity`. Console cannot run
it. Missing links or multiple linked identities require staff assistance; the
command does not guess which account to reset. Failed and successful attempts
are limited to one per player per minute, per plugin instance (reset on restart).

Enable the plugin's `keycloak.enabled` setting and configure its service-account
credentials. The service account needs permission to manage the target users,
including the Keycloak Admin REST `PUT /admin/realms/{realm}/users/{user-id}/reset-password`
endpoint. No SMTP configuration or email attribute is needed. The realm must
allow these accounts to set local passwords and complete the update-password
required action without demanding an email address. Custom password policies can
reject generated passwords; test the flow against the deployed realm before rollout.

The temporary password contains 192 random bits and upper/lowercase, numeric, and
special characters. Keycloak marks it temporary, requiring replacement at login;
this does not give it a separate time-based expiry. The plugin neither persists
nor logs it, but the private response can remain in the player's client chat log.
Treat that response as a secret. Resetting does not explicitly revoke existing
Keycloak sessions or the explorer's own sessions.

Minecraft authentication is the recovery credential: use authenticated online-mode
connections or a correctly secured authenticated proxy. Existing links must be
trusted and accurate. Do not expose this flow on an unauthenticated offline-mode
server. The `treasuryapi.ui.resetpassword` permission defaults to true, as does the
parent `treasuryapi.command`; privileged role commands still require the
operator-default `treasuryapi.ui.user` permission. Explicit permission denials
continue to apply.

Validation: unit tests cover sender-only targeting, confirmation, missing and
ambiguous links, cooldowns, sanitized failures, and the HTTP credential payload.
`ExplorerUiMapperIT` verifies the reverse lookup against MariaDB. A deployment
smoke test should use a disposable linked account without email, verify the old
password no longer works, and complete the forced password change in the browser.

Live Keycloak validation (2026-10-13): the `minecraft` realm has optional email,
email verification disabled, and `UPDATE_PASSWORD` enabled. The
`treasury-api-plugin` service-account token includes `realm-management/manage-users`.
A disposable account without email was reset using that service account, then
completed the forced password-change form through the `treasury-explorer` OIDC
browser flow. The temporary password was rejected afterward and the new password
successfully authorized. The disposable account was deleted. No realm settings
needed changing. This verifies Keycloak; deployment and the actual in-game
command still need a server smoke test.

## Browser recovery (Java and Bedrock)

V55 adds the `explorer_password_recovery` table and registers
`explorer.browser_recovery` disabled. The `/recover` page creates a ten-character
code and a separate 256-bit browser secret in an HttpOnly, SameSite=Strict cookie
(Secure in production). Only SHA-256 hashes reach the database. The five-minute
expiry is computed and enforced by MariaDB. No password is sent through chat or
stored in the economy database.

The player first runs `/treasuryapi ui recover <code>`, reads the warning, then
runs `/treasuryapi ui recover confirm <code>`. The same
`treasuryapi.ui.resetpassword` permission applies. Approval derives the UUID from
the authenticated sender and requires exactly one existing account link. The
short code authorizes approval only; it cannot complete a recovery. Approval
attempts share the existing per-player, per-instance one-minute reset cooldown.
There can be only one approved request per Keycloak subject at a time. A previous
approval must be completed, cancelled from its browser, or expire before another
can be approved (start a new browser request to clean expired rows).

The browser submits its new password after approval. Completion locks and consumes
the request transactionally, rechecks that the unique account link still matches,
and then calls Keycloak with a permanent credential. Failed/uncertain upstream
calls do not restore the request or retry it: try logging in with the new password,
then start over if necessary. Keycloak enforces its password policy in addition to
the page's 12–128-character bounds. Disabled accounts cannot be recovered. The
success page shows the actual Keycloak username. Existing sessions are not revoked,
matching the legacy reset behavior.

### Rollout

1. Apply V55 and deploy both the plugin and Explorer before enabling the flag.
2. Provision a dedicated confidential Keycloak service-account client in the
   realm selected by `AUTH_KEYCLOAK_ISSUER`. Grant user lookup and password reset
   access for the intended users (the existing plugin uses `manage-users`). Store
   `RECOVERY_KEYCLOAK_CLIENT_ID` and `RECOVERY_KEYCLOAK_CLIENT_SECRET` in the
   Explorer deployment's secret management and restart the consumers. Do not
   change the public `AUTH_KEYCLOAK_ID` client or give its browser a secret.
3. Configure shared Redis. Recovery fails closed without it. Start/finish are
   each limited to five requests per minute per trusted ingress IP; status checks
   to thirty. `BLOCK_TRUST_ENVOY=1` uses the existing authenticated ingress address
   contract; without it, clients share one conservative bucket, never untrusted
   `X-Forwarded-For` values.
4. Enable `explorer.browser_recovery` through the existing feature-flag controls
   after verifying the above prerequisites. Keep the site on HTTPS.
5. With a disposable linked account, test Java and Bedrock confirmation, password
   selection, fresh Keycloak login, expiration and replay. Never use a real
   player's account as a smoke-test fixture.

Disabling the flag blocks new approvals and web recovery operations without
changing existing passwords or the legacy command. An already admitted operation
may complete. No production credentials, realm settings, or flags are changed by
this code change. Local tests cover command sender identity, confirmation,
cooldowns, hash interoperability, atomic claims, expiry, changed/ambiguous links,
browser binding, sanitization and upstream request payloads. The browser smoke
check uses a local Keycloak test double; live realm password-policy behavior and
actual Bedrock command interaction still need the deployment smoke test.
