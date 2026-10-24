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
