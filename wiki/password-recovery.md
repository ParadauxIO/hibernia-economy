# Browser password recovery (Java and Bedrock)

V56 adds the `explorer_password_recovery` table. Browser recovery replaces the
chat temporary-password flow and is available without a feature flag.
The legacy `/treasuryapi ui resetpassword` and
`/treasuryapi ui resetpassword confirm` commands now only show browser recovery
instructions. `/recover` is a website path, not an in-game command.

The `/recover` page creates a ten-character
code and a separate 256-bit browser secret in an HttpOnly, SameSite=Strict cookie
(Secure in production). Only SHA-256 hashes reach the database. The five-minute
expiry is computed and enforced by MariaDB. No password is sent through chat or
stored in the economy database.

The player first runs `/treasuryapi ui recover <code>`, reads the warning, then
runs `/treasuryapi ui recover confirm <code>`. The same
`treasuryapi.ui.resetpassword` permission applies. Approval derives the UUID from
the authenticated sender and requires exactly one existing account link. The
short code authorizes approval only; it cannot complete a recovery. Approval
attempts have a per-player, per-instance one-minute cooldown.
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
and API keys are unchanged.

## Rollout

1. Apply V56 before deploying either consumer. Coordinate the plugin and Explorer
   deployments: recovery is available immediately once configured.
2. Provision a dedicated confidential Keycloak service-account client in the
   realm selected by `AUTH_KEYCLOAK_ISSUER`. Grant user lookup and password reset
   access for the intended users (the existing plugin uses `manage-users`). Store
   `RECOVERY_KEYCLOAK_CLIENT_ID` and `RECOVERY_KEYCLOAK_CLIENT_SECRET` in the
   Explorer deployment's secret management and restart the consumers. Do not
   change the public `AUTH_KEYCLOAK_ID` client or give its browser a secret.
   The plugin's `keycloak.enabled` setting and service credentials only control
   account-link attribute updates; browser recovery does not use them.
3. Configure shared Redis. Recovery fails closed without it. Start/finish are
   each limited to five requests per minute per trusted ingress IP; status checks
   to thirty. `BLOCK_TRUST_ENVOY=1` uses the existing authenticated ingress address
   contract; without it, clients share one conservative bucket, never untrusted
   `X-Forwarded-For` values.
4. Keep the site on HTTPS and verify all prerequisites before exposing recovery.
5. With a disposable linked account, test Java and Bedrock confirmation, password
   selection, fresh Keycloak login, expiration and replay. Never use a real
   player's account as a smoke-test fixture.

Minecraft authentication is the recovery credential: use online mode or a securely
authenticated proxy, and keep existing identity links accurate.

Local tests cover command sender identity, confirmation,
cooldowns, hash interoperability, atomic claims, expiry, changed/ambiguous links,
browser binding, sanitization and upstream request payloads. The browser smoke
check uses a local Keycloak test double; live realm password-policy behavior and
actual Bedrock command interaction still need the deployment smoke test.
