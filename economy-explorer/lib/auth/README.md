# Explorer Auth

Access is `authenticated AND permission AND resource relationship`. Explicit
`*.read.all` permissions bypass the relationship for that resource only. Roles
are database records, combined as sets; role names confer no implicit powers.

`policy.ts` defines 44 stable, typed permission names and descriptions.
`explorer_role_definition` and `explorer_role_permission` store editable bundles.
V31 seeds player, government, moderator and admin; Admin → Roles can create,
edit and delete other roles, edit defaults, and assign roles to players directly.
The player baseline applies to every signed-in user and cannot be deleted, but
its permission set can be empty. Admin starts with every permission and is editable.
New permission names require an explicit migration/grant; there is no wildcard.

| Default role | Initial permissions |
| --- | --- |
| player | Own player data; related accounts, firm rosters and finances; own authorized webhooks |
| government | Related government accounts and fines, plus the player baseline |
| moderator | Global read-only player/account/firm/government investigations, ledger and market figures/sales |
| admin | All 44 initial permissions |

Read, write, assignment and source-management operations have separate grants.
For example `account.admin.read` opens the account tools, `account.rename` changes
names, and `account.transfer` moves funds. Reading keys does not grant revocation,
rotation or rate-limit changes. Viewing roles/groups does not grant edits.

```ts
import { getViewer, canViewAccount, requirePermission } from '@/lib/auth';
const viewer = await getViewer();
if (await canViewAccount(accountId, viewer)) {
  // Fetch private account data only after this check.
}
requirePermission(viewer, 'account.rename'); // throws 401/403
```

Use `canViewFirmInternals`, `canViewFirmFinancials`, `requireAccountRead`, or
`requirePlayerRead` for resource-scoped decisions. A permission check alone is
insufficient for account or firm reads. `requireRole` is a compatibility wrapper;
new gates should name the action, not a role. The displayed `viewer.role` is only
the highest-priority label; `capabilities` contains every effective role grant.

`policy.ts` is pure and edge-compatible. Middleware verifies the signed session
before admin routes. Pages, route handlers and server actions perform live role
checks at the request boundary. Government, role and group mutation services enforce
checks internally too. `auth/delegation.ts` serializes authorization changes,
re-resolves current actor grants under the policy lock, and checks that delegated
roles contain only permissions the actor holds. Role edits use revision checks
to reject stale saves; deletion cascades to direct and group assignments so
recreating a role never restores old grants. Role edits that remove the actor’s
permission for that operation are rolled back, as is removal of their own final
`roles.assign` grant through direct-assignment tools. Group/LP membership changes can still revoke access;
keep an independent recovery administrator. Do not put roles or relationships into long-lived session
JWTs: role revocation must take effect on the next request. React's viewer cache is
request-local. Shared data caches may be read only after fresh authorization;
government queries apply account scope before aggregation and are not globally cached.

Account relationships come from ownership, active direct account access, effective
LP group membership, or a firm's proprietor/active financial staff. A grant to one
firm account never grants the firm's roster or sales feed. Deleted firm roles do
not convey financial permission. `account_read_access_web` centralizes direct, firm-finance and
LP account relationships for both detail pages and My data. In-game spending,
public REST API key scopes and webhook authority are separate and are unchanged.

LP sync uses LuckPerms resolved inheritance for groups and effective permission
checks for permission nodes. It uses static server context, audits expired nodes,
includes known economy players (LP's unique-user list omits default-only users),
and serializes scheduled and self-sync runs on one singleton. Offline users are
released after checking. Full reconciliation reads every known user: its cost is
linear in players × configured groups and should be monitored on large servers.

Synced grants have a one-hour lease. Success refreshes or revokes them; failed
resolution does not publish a partially resolved run. A DB write failure can leave
some rows refreshed; untouched rows still expire. Manual grants do not expire.
Changing the source atomically clears its synced members; compare-on-insert prevents
a running old-node reconciliation from restoring those grants. Privileged reads
use the existing audit pipeline (which remains fail-open on audit DB failure).

## Rollout

1. Apply Flyway V30 and V31 before the plugin/explorer updates. V30 adds LP
   snapshots and converts `viewer`/`staff.audit` grants to `player`. V31 seeds
   configurable role definitions/permissions, preserves existing assignments,
   and retains unrecognized role keys with no permissions for administrator review.
2. Deploy the updated plugin and explorer together. Ensure existing configurations
   set `reconciliation.enabled: true` (the new default). Old plugins cannot refresh
   the new leases. Existing synced roles are denied until the first updated sync.
3. Review former viewer/government users. Explicitly assign moderator where global
   investigation is appropriate; verify government account direct/group grants.
4. Check scheduled sync or run `/treasuryapi ui sync`; verify unrelated-account
   denial, manual/LP coexistence, and revocation after removing the final LP member.

New migrations are additive; earlier Flyway checksums are unchanged. Rollback of
the web app to the old policy reintroduces global government access and should not
be used as an authorization rollback strategy.


## Regression coverage

Use a disposable MariaDB database whose name contains `test`, and export
`DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, and `DB_PASSWORD`.

```sh
# From economy-explorer; resets the disposable database.
RUN_INTEGRATION=1 npm test
npm run test:e2e -- --workers=2
# From the repository root; embedded MariaDB plus authoritative Flyway migrations.
bash gradlew :treasury-api-plugin:test :treasury-rest-api:test
```

Managed Playwright builds and starts the production app and seeds its database.
`authorization.spec.ts` signs Auth.js sessions using the local test secret, so
it exercises edge verification and real identity/role/permission resolution,
without the role-cookie shim. It covers forged/expired sessions, role editing and
direct grants, manual/LP groups and lease expiry, source changes, account and firm
relationships, government scope, live revocation, and stale-editor denial on both
desktop and mobile. `auth.spec.ts` retains the fast role-shim smoke tests.

For an already-running local test instance, set `E2E_BASE_URL` and
`E2E_AUTH_SECRET` to its test-only `AUTH_SECRET`; enable `E2E_AUTH_SHIM=1` only if
that instance also enables the legacy shim. Never point the mutating suite at a
production database. The real external Keycloak login and a live Paper/LP server
are outside these local tests: signed identity fixtures and LP API mocks represent
those boundaries. Plugin integration tests execute real command services and
reconciliation tasks through MyBatis against the production migrations, proving
custom-role grants, deletion/recreation, lease refresh/revocation, explicit denies,
manual-grant preservation, failed-sync behavior, and the separation of web read
relationships from API spending authority. Explorer SQL tests additionally verify
permission removal, delegation bounds, optimistic revisions, group cascades,
source edits, and rechecks after assignment revocation.
