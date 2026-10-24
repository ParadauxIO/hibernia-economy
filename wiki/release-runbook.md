# Release runbook — `develop` → `main` (through V42)

Ship one reviewed economy release through **V42**. Inspect each tenant's actual
starting schema and deployed artifacts; **V15 is a historical baseline, not a
verified current production state**. This is a **full-stop release**: old and new
code cannot share the database across this cutover. The selected Paper plugins
require Java 25 and Paper 26.3; confirm that runtime and the installed integrations
before choosing the release window.

Track readiness in [PAR-509](https://app.taskl.ie/w/paradaux/issues/PAR-509).
Completing this document or merging code is not evidence that a tenant was
preflighted, restored, migrated or accepted.

For the general mechanics see [database.md](database.md) (Flyway workflows) and
[ci-cd.md](ci-cd.md) (image builds, Argo CD). This page is the order of operations.

## Why this cannot be a rolling deploy

| Fact | Where |
|---|---|
| Merging to `main` builds and pushes `production-sha-<short>` (+ `:prod`) images for `economy-explorer` and `treasury-rest-api`. Argo CD Image Updater rolls any new `production-sha-*` tag into the production overlay on its own. | `.github/workflows/economy-explorer-docker.yml`, `treasury-rest-api-docker.yml` |
| One REST image and one explorer image serve **every** production tenant, so all tenants pick up the new code at the same moment. | comment in `treasury-rest-api-docker.yml` ("Compute tags") |
| Schema deploys are manual, one tenant per run. | `.github/workflows/economy-flyway-migrate.yml` |
| The production Paper bundle (Treasury, Business, ChestShop, TreasuryAPI) is published by CI with a manifest; installation is a separate coordinated operation and requires restart. | [build-and-test.md](build-and-test.md#the-release-bundle) |
| **New code needs the new schema:** it reads `economy_players` (V17), the `account_access` *table* (V18) and the `account_read_access_api` / `_web` views (V22). On a V15 database those queries fail. | `treasury`, `business`, `treasury-rest-api`, `economy-explorer` |
| **Old code needs the old schema:** pre-cutover code reads `firm_players` and `player_login_times` (explorer, REST API, Business, Treasury), which **V19 drops**. V18 drops `account_members`, `account_authorizers`, `account_group_members`, `account_group_authorizers` — the tables a pre-`account_access` Treasury build reads and writes. | `V18__…sql:92-95`, `V19__…sql:17-18` |

Other destructive steps in the same run: V23 nulls every stored `api_keys.token`,
V28 drops `firm_sale` (unused by any code, old or new), V29 deletes duplicate
`government_fines` rows. MariaDB DDL is not transactional, so a migration that
fails midway leaves the earlier ones applied — **the pre-migration backup is the
only way back.**

## How long it takes

Set the window from the **full V42 upgrade and restore rehearsal** on a recent
representative snapshot. Include every tenant, coordinated backup, identity
remediation, credential renewal and application verification. V42 replaces the
SYSTEM routing index with `ALGORITHM=COPY`; V40 audits names and backfills claims.
Do not assume the earlier timing covers those operations.

Historical evidence only: a 2026-10-18 rehearsal on a 2026-10-17 production-copy
snapshot ran V16–V36 in 233 seconds with a 128 MB buffer pool: 191 seconds for
V36 and 23 seconds for V20. V36 read 19.7 M postings / 9.9 M transactions and added
about 730 MB. The old estimate was a two-hour window with roughly one hour of
work; it is not a V42 deployment or restore measurement. Restoring the dump
rebuilds indexes and must be timed separately.

## 0. Before the window

1. **Freeze scope and record the release.** Record the selected develop SHA,
   eventual main merge SHA, Java/Paper version, all four plugin hashes, REST/Explorer
   immutable image tags/digests, configuration versions and prior compatible
   artifacts. The main plugin workflow publishes `release/manifest.json`; retain
   it with the bundle and verify its SHA/version/hashes. Resolve include/defer
   decisions for outstanding PRs before freezing. Passing CI is required; remaining
   real-provider, graphical and Bedrock acceptance needs explicit evidence or an
   agreed release disposition. See [PAR-520](https://app.taskl.ie/w/paradaux/issues/PAR-520).
2. **Read each tenant's plan.** Run **Economy Flyway · Info (read-only)** for
   `democracycraft` and `statecraft` from the frozen revision. Record the workflow
   SHA, actual applied/pending versions and compatibility-view state.
   - The workflow ignores **pending** migrations during validation. Its validation
     step is **fatal** for checksum mismatches, failed or missing migrations.
     An expected pending list does not make a failed workflow acceptable.
   - V17/V18 were edited before release, and the historical V38 collision was
     reconciled to taxation V38, employment V39 and namespace V40. Inspect any
     previously applied version against the selected files. An older checksum or
     differently applied V38 needs a deliberate reconciliation, never a blanket
     repair, baseline or out-of-order migration.
   - Coordinate [PAR-250](https://app.taskl.ie/w/paradaux/issues/PAR-250): inspect
     the actual production Explorer image and `account_access` / `account_group_access`
     object types. **Current V18 drops compatibility views before creating tables.**
     Do not manually drop a view while the serving Explorer depends on it.
3. **Pre-check the data the migrations assume.** A violated assumption fails the
   run partway (V17, V24) or silently drops someone's access (V18). For an actual
   pre-V17/pre-V18 layout, every row below must be `0`. On later schemas use the
   corresponding current tables and expected migration state; do not query dropped
   legacy tables:
   ```sql
   -- V17: economy_players.name_lower is UNIQUE
   SELECT 'names colliding case-insensitively', COUNT(*) FROM (
     SELECT 1 FROM firm_players GROUP BY LOWER(current_name) HAVING COUNT(*) > 1) t
   -- V18: account_access is keyed (account_id, subject) and built from account_members
   UNION ALL SELECT 'duplicate account members', COUNT(*) FROM (
     SELECT 1 FROM account_members GROUP BY account_id, member_uuid_bin HAVING COUNT(*) > 1) t
   UNION ALL SELECT 'active authorizers with no active member row', COUNT(*)
     FROM account_authorizers au
     LEFT JOIN account_members m ON m.account_id = au.account_id
          AND m.member_uuid_bin = au.authorizer_uuid_bin AND m.left_at IS NULL
    WHERE au.revoked_at IS NULL AND m.account_id IS NULL
   -- V24: UNIQUE over SYSTEM account names
   UNION ALL SELECT 'duplicate SYSTEM account names', COUNT(*) FROM (
     SELECT 1 FROM accounts WHERE account_type = 'SYSTEM'
      GROUP BY display_name HAVING COUNT(*) > 1) t;
   ```
   V29 removes duplicate fine rows by itself; to see whether it will,
   `SELECT COUNT(*) FROM (SELECT 1 FROM government_fines GROUP BY txn_id HAVING COUNT(*) > 1) t;`
4. **Audit V40 names on a restored clone.** With the clone migrated through V35
   or later, run [the broad inventory](../ops/name-collision-inventory.sql) and
   [the V40 blocker report](../ops/name-namespace-preflight.sql). The broad inventory
   includes informational same-identity matches; the blocker report must return
   zero rows after approved remediation. Review whitespace, released firms,
   historical aliases and player placeholders according to [the namespace rules](name-namespace.md).
   Never run these queries on a V15 layout or silently choose replacement names.
5. **Rehearse the whole sequence and restoration.** If V40 would reject SYSTEM
   labels, rehearse the writer-stop sequence at V39: capture original routing keys
   with [V42 preparation](system-wallet-routing.md#existing-database-blocked-before-v40),
   then apply reviewed label changes and migrate normally through V42. V42 running
   after V40 is too late to preserve a label already renamed. Retain evidence that
   IDs, owners, balances and routing survived. Do not run the all-pending production
   workflow until its plan accounts for this checkpoint.
6. **Prepare matching artifacts and credentials.** A local rehearsal bundle is
   `./gradlew release -Pci=true`; the main publication workflow builds the versioned
   production bundle. Preserve previous images, jars and secret-store versions.
   Prepare new random signing material (`openssl rand -base64 48`, configured as
   `base64:<value>`) through secure secret storage and a SERVICE renewal plan for
   each tenant. Do not print values in issue receipts or commit them.
7. **Assign operators and communicate.** Record the maintenance, migration,
   verification and rollback owners and the measured window. Tell API users their
   tokens will stop working and give the reissue commands from step 5. Inventory
   scheduled/external writers, including old copies of
   `ops/backfill-firm-account-owners.sql`, for the shutdown. Older copies reference
   tables dropped by V18 and must not resume after migration.

## 1. Hold the production rollout, then merge

1. Stop Argo CD from rolling new images into production (pause Image Updater /
   auto-sync for the explorer and REST API apps). The manifests live outside this
   repo.
2. Merge the release PR. Wait for these runs on `main` to go green:
   **Economy Explorer · Build and Push Docker Image to Harbor**, **Treasury REST API · Build and
   Push Docker Image to Harbor**, **Economy · Release plugins and APIs**. Record the actual successful workflow
   revisions. Image workflows have path filters; if no image was built for the
   chosen revision, use their manual workflow at that revision and verify the
   resulting tag/digest before deployment. Do not substitute an older mutable tag.

The old pods keep serving the old schema while images build under the verified
hold. If the hold cannot be established, stop here and resolve deployment control
before merging. Nothing new may start before all required schema changes and
nothing old may run after destructive migration or SYSTEM label remediation.

## 2. Stop everything that touches the database

- Stop the **game server(s)** — that is what unloads Treasury, Business, ChestShop
  and TreasuryAPI. Disabling individual plugins is not enough.
- Scale **`treasury-rest-api`** and **`economy-explorer`** to zero for every
  production tenant.
- Stop scheduled jobs, controllers and other external writers as inventoried in
  step 0. Confirm no application connections remain (`SHOW PROCESSLIST`). Keep
  every writer stopped across preparation, remediation, migration and installation.

## 3. Back up, then migrate — per tenant

1. Take and verify a coordinated backup of databases and relevant worlds/plugin
   files, including ChestShop item stores and recovery evidence. A database dump
   (`mariadb-dump --single-transaction --routines --triggers <db>`) preserves triggers
   and routines; include events where the environment uses them. Check exit status,
   checksum, size and object inventory against the rehearsed restore procedure.
   A dump file existing is not restore verification. Record player/access counts
   using the tenant's actual starting schema (legacy `firm_players` and active
   `account_members` only if they still exist), plus money/ownership invariants.
2. If the reviewed plan requires name remediation, use the **rehearsed V39
   checkpoint** before V40. The Migrate workflow applies all pending versions and
   has no target-version input; it must not be used to reach this checkpoint.
   The operator must use a reviewed targeted Flyway invocation from the frozen
   checkout with the correct tenant connection. With all writers still stopped,
   follow the exact [SYSTEM preparation procedure](system-wallet-routing.md#existing-database-blocked-before-v40)
   before approved SYSTEM label changes, then verify the private identity map and
   namespace blocker report. Inspect any failed history/partial objects before a
   separately reviewed repair. Keep V42 at its normal version in Flyway history.
3. Run **Economy Flyway · Migrate** from the selected `main` release revision,
   `target` = tenant, `confirm_target` = the same name, `baseline` = **off**.
   Record the checkout SHA. Require every expected migration through V42 successful
   and nothing pending; validate the resulting history. Do not advance main during
   the tenant runs.
4. Repeat for the other tenant. Every tenant sharing new images must be ready
   before any new code starts.

If a migration fails: stop, do not retry blind. Go to [Rollback](#rollback).

## 4. Deploy the new code

1. Replace the four plugin jars on each game server with the ones from `release/`.
2. Apply step 5, including SERVICE renewal, before opening applications to users.
   Confirm the server is on the reviewed Java 25/Paper 26.3 runtime and the complete
   integration artifact set. A plugin-only rollback does not undo schema or world changes.
3. Release the Argo CD hold so the `production-sha-<merge sha>` images roll out;
   scale the REST API and explorer back up.
4. Start the game server(s).

## 5. Rotate the token-signing secret

The REST API and the TreasuryAPI plugin derive the same HMAC key from one shared
secret (`common/…/JwtKeys.java`); they must be byte-identical.

1. Set `base64:<value>` as `JWT_SECRET` on the REST API (`api.jwt-secret` in
   `application-prod.yaml`) **and** as `api.jwt-secret` in
   `plugins/TreasuryAPI/config.yml`. Restart both (covered by step 4).
2. Every existing token stops verifying. Players and firms reissue in-game:
   `/treasuryapi personal reissue <keyId>` / `/treasuryapi business reissue <keyId>`
   (`… list` shows the IDs). Tokens are shown once and no longer stored (V23), so
   there is nothing to recover from the database.
3. Re-mint the explorer's **SERVICE** token against the new secret with
   `economy-explorer/scripts/mint-service-token.mjs` (no player can issue or manage a SERVICE key, so it
   is minted offline). Per tenant:
   ```bash
   # the existing key:
   #   SELECT key_id, bin_to_uuid(owner_uuid_bin), revoked, expires_at
   #     FROM api_keys WHERE key_type = 'SERVICE';
   # JWT_SECRET is already injected securely; never paste its value into history.
   umask 077
   node economy-explorer/scripts/mint-service-token.mjs \
       --key-id <key_id> --owner <owner uuid> > token.txt 2> apply.sql
   mariadb … <db> < apply.sql      # must report 1 row changed
   ```
   Set the contents of `token.txt` as the explorer's `TREASURY_ADMIN_TOKEN` and
   start/restart it, then delete the private temporary files. The token is only accepted once `apply.sql`
   has run, and running it invalidates the previous token. If no SERVICE row
   exists, `--create --owner <uuid>` prints an INSERT and returns its key ID when
   applied; **then run the mint command with that key ID**. Creating the row alone
   creates no usable token. Tokens last 180
   days unless `--days` says otherwise (pass the lifetime the existing key was
   given if it should stay long-lived); re-mint the same way before `expires_at`. Until this is done the
   explorer's admin actions and webhook management fail; read-only pages are
   unaffected.

## 6. Verify before reopening

- **Schema:** the Migrate run's final `flywayInfo`, or **Info** again — nothing pending.
- **Money:** run `ops/money-invariants.sql` against each tenant; `status` must be `OK`.
- **SYSTEM routing:** resolve original plugin keys to the recorded account IDs;
  verify owners, balances, balance-row counts and ledger invariants after any label
  remediation. Confirm namespace conflicts still reject.
- **Player directory (when upgrading from the legacy layout):** `SELECT COUNT(*) FROM economy_players;` ≥ the pre-migration
  `firm_players` count. Rows whose `current_name` is a 32-character hex string are
  login-only players seeded by V17; they fix themselves on next login.
- **Access (when upgrading from the legacy layout):** `SELECT level, COUNT(*) FROM account_access WHERE removed_at IS NULL
  GROUP BY level;` — MEMBER + AUTHORIZER should match the old active
  `account_members` count.
- **REST API:** `GET /actuator/health` is `UP`; an old token gets 401, a reissued one works.
- **Explorer:** `GET /healthz`; an account page, a firm page and `/chestshop` render
  with player names; sign in and open `/me`.
- **In game:** `/balance`, a `/pay`, one ChestShop purchase, `/firm sales summary`.
  Server log shows all four plugins enabled with no SQL errors.

## 7. After the release

- Run `ops/backfill-firm-account-owners.sql` **once** per tenant. It repairs firm
  accounts stranded by proprietor changes made before this release; the new code
  keeps them aligned from here on. It is idempotent — a second run changes nothing.
- Observe Explorer latency and estimated bridge volume (PAR-159). Historical sale
  linking (PAR-235) needs representative-data validation and its own scheduled
  execution; deploying its tooling does not run the backfill.
- Record the go/no-go result, per-tenant smoke evidence, monitoring owner and backup
  retention period. Keep the backups through the agreed stability period.

## Rollback

There is no down-migration, and the drops are irreversible without the backup.

- **Before any schema preparation, migration or label remediation:** revert the merge on `main` (or leave the
  rollout held), start the old code again. Nothing was lost.
- **After schema preparation, label remediation, or a failed/completed migration:** keep everything stopped, restore the
  tenant from the step 3.1 backup, redeploy the previous images (the mutable
  `:prod` tag has moved — pin the previous `production-sha-*` tag) and the previous
  plugin jars, and put the previous signing secret back. Any transaction made on
  the new code after go-live is lost by the restore, so decide quickly: once
  players have been let back in, fixing forward is almost always cheaper.
- Fix the migration on `develop` as a **new** version where possible; only edit an
  existing file if it has not been applied to any persistent database.
