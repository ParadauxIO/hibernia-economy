# Release feature flags

Release flags let you deploy incomplete features while keeping their behavior
inactive. Each flag has one value per economy database, shared by Paper plugins,
the REST API and Economy Explorer. Separate environment databases have independent
values. Flags do not grant permissions or replace ownership checks.

## Register a feature

Add a new Flyway migration in `economy-flyway`, after V52, with a unique lowercase
namespaced key (maximum 128 characters):

```sql
INSERT INTO feature_flags (name, description)
VALUES ('business.preview', 'Describe exactly what enabling this feature changes')
ON DUPLICATE KEY UPDATE name = VALUES(name);
```

The default is disabled. Registration must never overwrite an operator's choice.
Do not add an application-startup seed or reuse a retired key for another feature.
V52 intentionally registers no features and changes no existing economy behavior.

## Migration-dependent features and deployment order

Every feature that requires a database migration must use a release flag to
preserve current behavior while disabled. Economy Explorer can deploy up to
12 hours before the plugins; this is intentional. Treasury applies the
migrations when the plugins start. Explorer must continue working against the
previous schema throughout that gap.

Guard before executing queries against new tables or columns, constructing
schema-dependent services, or starting feature jobs. Hiding a UI control alone
is insufficient: guard its server actions, routes and service entry points too.
For replacements, the disabled branch must execute the existing behavior rather
than returning an unavailable error. For entirely new operations, returning
unavailable is appropriate. For example:

```ts
if (await isFeatureEnabled('explorer.preview')) {
  return loadUsingNewSchema();
}
return loadUsingExistingSchema();
```

Use this rollout sequence for each economy database:

1. Register the feature disabled in a new migration alongside backward-compatible
   schema changes. Do not enable it in that migration or on application startup.
2. Deploy Explorer with the guarded feature. A missing flag row means disabled;
   Explorer keeps using the existing schema and behavior.
3. Deploy the plugins on their normal schedule and verify that Treasury applied
   the required migrations successfully. Check that all consumers that will use
   the enabled feature are compatible, including any required backfill.
4. An operator explicitly enables the flag in Explorer's backoffice or with the
   operator script when the database and deployed consumers are ready. Applying
   the migration alone must not activate the feature.
5. Verify behavior after propagation. Disable the flag to restore the documented
   fallback if needed; disabling does not undo migrations or committed data.

Test the disabled path against the previous schema, the migrated-but-disabled
state, and the enabled state. Assert that the old-schema path performs no queries
against the new schema. Keep old columns/tables until old consumers and the
fallback no longer need them. A flag does not make destructive schema changes safe.

For the initial V52 rollout, missing `feature_flags` tables make runtime release
checks return false. Flag administration and the operator script require V52;
use them after Treasury has applied it. Runtime database users need SELECT on
`feature_flags`; Explorer and operator-script users also need UPDATE. Existing
Explorer audit and authorization table privileges still apply. Flags do not
bypass existing schema/release startup checks in other consumers.

## Check a flag in Paper

Depend on the published `treasury-api` and obtain `FeatureFlagApi` from Bukkit's
ServicesManager. Treasury owns the provider and background refresh. Business,
ChestShop and downstream plugins must not query the flag table themselves.

```java
FeatureFlagApi flags = Bukkit.getServicesManager().load(FeatureFlagApi.class);
if (flags == null || !flags.isEnabled("business.preview")) {
    // A command can send an unavailable message; listeners/jobs should return.
    return;
}
// Resolve/create the optional service only after the check.
previewServiceProvider.get().execute();
```

Checks are synchronous in-memory reads and safe on the server thread. Check again
at the public service entry point so a direct caller cannot bypass the command
wrapper. In Treasury, resolve through ServicesManager as above; do not assume Guice
constructs an API provider. Missing providers default off (`FeatureFlagApi.DISABLED`
is available as a fallback). The provider returns false after Treasury shuts down.

Keep constructors, static initializers and dependency injection providers free of
feature side effects. Register lightweight command/event/job wrappers that check
the flag before resolving optional code. Do not register a mechanic only at startup
if you expect its flag to work without a restart. Scheduled wrappers check before
each independent job; enabling must not replay missed jobs automatically.

## Check a flag in Explorer

Use the server-only helper at each service/action/route boundary:

```ts
import { requireFeatureEnabled, isFeatureEnabled } from '@/lib/featureFlags';

export async function performPreview() {
  await requireFeatureEnabled('business.preview');
  // Check the caller's existing permissions and ownership here as well.
  // Only then initialize or execute the optional feature.
}

// In a Server Component:
const showPreview = await isFeatureEnabled('business.preview');
// Pass the evaluated boolean to client components for visibility.
```

`requireFeatureEnabled` throws `FeatureDisabledError` with status 404 and code
`FEATURE_DISABLED`. Route handlers should map it through their existing error
response handling; pages can render their unavailable state. A browser-supplied
boolean is never authoritative. The helper forces request-time rendering. Check
outside shared result caches; never cache the entire guarded operation or a true
flag decision independently of this helper.

## Check a flag in Spring

Inject `FeatureFlagService` into the business service. Use
`flags.isEnabled("business.preview")` for conditional behavior, or
`flags.requireEnabled("business.preview")` before performing the operation. The
latter produces the existing API error envelope with HTTP 404 and code
`FEATURE_DISABLED`. Keep authentication and authorization checks in place.

## Enable or disable

In Explorer, open **Admin → Feature flags**. `feature_flags.read` permits reading
saved values; `feature_flags.update` permits changes. V52 grants both to the admin
role explicitly. Custom roles can receive these permissions through the existing
role editor. Concurrent stale saves are rejected; refresh before trying again.
The page shows saved database values, not a claim that every consumer has already
refreshed. Changes record before/after values in the existing audit trail, whose
sink remains fail-open if audit insertion fails.

For scripts, run from `economy-explorer` after `npm ci`, with `DB_HOST`, `DB_PORT`,
`DB_NAME`, `DB_USERNAME`, and `DB_PASSWORD` in the environment. For a local env file,
Node supports `node --env-file=.env scripts/feature-flags.mjs ...`.

```sh
node scripts/feature-flags.mjs list
node scripts/feature-flags.mjs set business.preview on PAR-556
node scripts/feature-flags.mjs set business.preview off rollback-PAR-556
```

The script prints its target database, validates inputs, rejects unknown keys,
serializes updates, increments revisions and records attribution. Repeating an
explicit set is safe (and advances the revision). It does not create an Explorer
session or audit entry; `updated_by` attributes the latest SQL/script change.

For releases that deliberately choose automatic activation, an explicit activation
migration can use the SQL below. Migration-dependent features following the
manual rollout above must instead use an operator action after readiness checks.
An operator can also execute this SQL as a script:

```sql
START TRANSACTION;
UPDATE feature_flags
SET enabled = 1, revision = revision + 1,
    updated_at = CURRENT_TIMESTAMP(3), updated_by = 'migration:Vnext'
WHERE name = 'business.preview';
-- Verify ROW_COUNT() = 1; unknown keys must not be silently created.
COMMIT;
```

Use `enabled = 0` for deactivation. Activation migrations affect every database
that receives them; use the operator script for environment-specific rollout.
Direct SQL must follow the revision/attribution contract to protect UI editors.
Readers detect even raw changes through polling, without a message bus. Direct DBA
writes bypass application authorization and cannot guarantee application auditing.

## Propagation and failure behavior

- Paper and Spring refresh serially in the background every five seconds after
  completion of the previous attempt. JDBC queries have a three-second timeout;
  pool acquisition uses the application's existing bounded pool timeout.
- Explorer refreshes on demand, at most once every five seconds per process.
  Concurrent requests share the pending load; each waits at most three seconds.
  SQL execution has a three-second server-side timeout. Idle instances refresh on
  their next check rather than running a permanent web-process timer.
- Missing keys, invalid keys/values and initial failures are disabled. A failed
  refresh retains the last successful snapshot only until its 30-second expiry.
  Snapshot age is measured from the start of the read using a monotonic clock, so
  slow queries cannot extend old enabled values. Evaluations enforce expiry even
  if the refresh worker stalls. Reads never publish a partial snapshot.
- Healthy consumers see changes within five seconds plus query time. Updates are
  not instantaneous or atomic across processes. Expiry disables new work during
  a prolonged database failure; recovery enables it again if the stored value is on.
- Evaluate once when admitting each logical operation. Already-admitted
  transactions finish normally. Disabling is not transaction cancellation or data
  rollback. Refresh failures/recovery are logged once per transition.

Flags cannot prevent compilation/linkage errors, unsafe migrations, eager feature
initialization or effects that occurred before a guard. Keep schema changes
backward compatible. For replacement mechanics, the disabled branch retains the
old behavior. Irreversible features need an explicit disable/recovery policy.

## Retire a flag

After the feature is complete, deploy code that no longer references its flag to
all supported consumers. Then remove the row in a new migration. Deleting a row
while older consumers still check it disables the feature for those consumers.
Keep keys unique forever. Per-player targeting, percentages, per-server overrides,
and Realty-specific upstream adoption are outside this initial implementation.

## Government API keys (PAR-521)

V53 registers `treasury.government_api_keys` disabled without overwriting existing
operator choices. Deploy compatible TreasuryAPI and REST consumers before enabling
it through the backoffice or `node scripts/feature-flags.mjs set
treasury.government_api_keys on PAR-521`. The separate
`treasuryapi.government.issue` permission remains required and default-denied.

While disabled, command/service issuance and in-game reissue are blocked. Valid
government JWTs receive `404 FEATURE_DISABLED` at authentication, including for
rotation; the rotation service also checks the flag. Other key types are unchanged.
Listing, revocation, and admin credential invalidation remain available for cleanup.
Government webhook deliveries remain pending and resume on re-enable; they do not
consume delivery attempts or prevent other subscriptions from progressing. Ingestion
continues queuing events while paused. Existing cache propagation and in-flight
operation semantics apply. Turning off this flag does not revoke keys permanently.
