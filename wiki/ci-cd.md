# CI / CD

All automation lives at the repo root in **`.github/`** — GitHub only reads
workflows from the root, which is the natural home in a monorepo. One workflow,
**`ci.yml`**, gates every pull request and every push to `develop`/`main`; the
rest publish, deploy or operate on the database.

## The CI pipeline (`ci.yml`)

Runs on pull requests to `develop`/`main`, pushes to them, nightly (03:23 UTC)
and on demand (*Run workflow*, with an optional live-suite test filter and
migration base ref).

| Job | What it runs | When |
|---|---|---|
| `JVM` | `./gradlew build --parallel --continue -Pci=true` — every project's `check` plus `assemble`, including Error Prone/NullAway, Spotless, ArchUnit and the coverage ratchets — against one **MariaDB 10.11** service with a database per suite. Full-history checkout so Spotless can ratchet against `origin/main`. Publishes JUnit results as a check. | always |
| `Coverage comment` | Comments Treasury's and Business's JaCoCo coverage on the PR. Advisory: the line + branch ratchets are `jacocoTestCoverageVerification` in their builds and already ran in `JVM`. | PRs |
| `Mutation testing` | PIT (`:common`, `:treasury`, `:business`, `:treasury-api-plugin`, `:chestshop` `pitest`), each module failing under its `mutationThreshold`. History is cached per commit, so PR and push runs only re-analyse changed code; the nightly run starts cold. Reports are uploaded as `pitest-reports`. | as `Integration`; nightly always cold |
| `REST API contract` | `oasdiff breaking` between the base branch's and this PR's `treasury-rest-api/openapi/openapi.json`; fails on breaking changes unless the PR has the `api-breaking-change` label. | PRs |
| `Explorer` | `explorer-check.yml`: typecheck, lint, vitest with coverage thresholds (MariaDB 10.11), then the Playwright e2e suite including the axe accessibility scan. Flaky tests (pass only on retry) fail the run. | always; e2e only if the PR touches `economy-explorer/**` or the migrations |
| `Migrations` | The migration guard, the upgrade test, money invariants and the kysely schema-drift check (below). | always |
| `Integration (live server)` | `ops/integration/run.sh`: a real Paper server with this commit's plugins, the REST API and a migrated MariaDB, driven by the suite in `integration-tests/` (~6 min). | always, except PRs that touch none of the plugins, REST API, `common`, migrations, build files or the suite |
| `ci-gate` | Fails if any job above failed or was cancelled; skipped jobs are fine. | always |

**`ci-gate` is the one required status check** for branch protection. Required
checks can't be path-filtered, so the expensive jobs skip themselves instead and
`ci-gate` treats a skip as a pass. When you add a job to `ci.yml`, add it to
`ci-gate`'s `needs`, or it gates nothing.

PR runs are cancelled when a newer commit is pushed to the PR; runs on
`develop`/`main` never are.

### `./gradlew check` is the contract

CI doesn't pick tasks per module: whatever `check` covers gates the merge — the
plugin and API-module tests, `:business:testWithoutCarbon` /
`testWithoutPlaceholderApi`, `:common`'s tests, and the coverage verifications.
To gate on something new, wire it into that project's `check` in Gradle; no
workflow change needed. `build` adds `assemble`, so a broken `shadowJar` or
`bootJar` also fails the PR.

The **build cache** is on (`org.gradle.caching=true` in `gradle.properties`), and
`setup-gradle` saves it from `develop` runs, so an untouched module's tests are
restored rather than re-run. The **configuration cache** is not enabled: the
`copyPlugin` staging task (`io.paradaux.paper-server-conventions`) and ChestShop's
`processResources` aren't compatible with it yet.

### Database-backed tests in CI

The `JVM` job starts a **MariaDB 10.11 service** (production's major) and points
each suite at its own database, which makes the harness skip MariaDB4j (whose
bundled binary needs system libs the runner image doesn't ship):

| Suite | Variables |
|---|---|
| `treasury` | `TREASURY_TEST_JDBC_URL`, `TREASURY_TEST_DB_USER`, `TREASURY_TEST_DB_PASS` |
| `business` | `BUSINESS_TEST_JDBC_URL`, `BUSINESS_TEST_DB_USER`, `BUSINESS_TEST_DB_PASS` |
| `treasury-api-plugin` | `TREASURYAPI_TEST_JDBC_URL`, `TREASURYAPI_TEST_DB_USER`, `TREASURYAPI_TEST_DB_PASS` |
| `treasury-rest-api` | `TREASURY_REST_TEST_JDBC_URL`, `TREASURY_REST_TEST_DB_USER`, `TREASURY_REST_TEST_DB_PASS` |

The same variables let you run the suites against any local MariaDB.

### Migration safety

The `Migrations` job compares this commit against a **base ref** — the target
branch for a PR, the previous tip for a push, `HEAD^` (or the `migration_base`
input) for nightly/manual runs:

1. [`economy-flyway/scripts/check-migrations.mjs <base>`](../economy-flyway/scripts/check-migrations.mjs)
   fails if a `V*__*.sql` that exists on the base was modified, renamed or
   deleted (Flyway checksums applied scripts — write a new migration instead),
   if a file isn't named `V<n>__<snake_case>.sql` / `R__<snake_case>.sql`, if two
   share a version, or if a new version isn't the next one after the base's
   highest. Repeatable `R__` scripts may change.
2. [`economy-flyway/scripts/upgrade-test.sh <base>`](../economy-flyway/scripts/upgrade-test.sh)
   migrates a scratch database with the base's migrations, then this commit's,
   runs `flywayValidate`, and requires [`ops/money-invariants.sql`](../ops/money-invariants.sql)
   to report `OK`.
3. The explorer's kysely types are regenerated from that database and the job
   fails if `economy-explorer/lib/db.generated.ts` is stale (`npm run db:codegen`
   against a migrated DB, then commit it).

Both scripts run locally the same way, e.g.
`node economy-flyway/scripts/check-migrations.mjs origin/develop` and
`DB_PORT=3306 DB_PASSWORD=… economy-flyway/scripts/upgrade-test.sh origin/develop`
(it drops and recreates `DB_NAME`, default `economy_upgrade`).

The pre-cutover `main` migration tree at `36d7d90` is a pinned exception:
production's release baseline is **V15**, although that tree contains pending
V16–V18. The guard permits only the exact V17/V18 corrections documented in the
[release runbook](release-runbook.md#0-before-the-window), and the upgrade test
applies that base through V15 before migrating to HEAD. Both the old tree and
the old/new migration blobs are pinned; later edits, deletions and renames still
fail. Other bases upgrade through their full migration history. No validation
is disabled and no Flyway repair is performed. A database that applied the old
V17/V18 is **not** covered by this rehearsal; the runbook's stop condition still
applies. Guard regression tests run in the Migrations job.

## Other workflows

| Workflow | Trigger | Purpose |
|---|---|---|
| `jvm-check` / `explorer-check` | `workflow_call` only | The reusable JVM and explorer test jobs. `ci.yml` calls them, and so do the publish/image workflows, so a release is gated on the same job definition as a merge. |
| `treasury-publish-api` / `business-publish-api` | push to `develop` | `jvm-check` on that API's projects, then publish the `*-api` snapshot. Manual runs are restricted to `develop`. |
| `plugin-release` | every push to `main`, manual on `main` | Wait for the exact commit’s full main-push CI and `ci-gate`, publish all four plugins and both API libraries, then SFTP-stage enabled targets for their next restart. |
| `treasury-rest-api-docker` | push to `main`/`develop` | `jvm-check` on `:treasury-rest-api:check`, then build and push the image to **Harbor**. |
| `economy-explorer-docker` | push to `main`/`develop`, manual | `explorer-check`, then build and push the image to **Harbor**. |
| `economy-flyway-info` / `-migrate` / `-staging-clean` | manual (`workflow_dispatch`) | Gated schema operations (see [database.md](database.md)). Info's `flywayValidate` ignores pending migrations and fails on anything else. |
| `dev-server-publish` | push to `develop` on a plugin or `economy-flyway/**` | Build the plugin bundle and replace the rolling `dev-latest` prerelease, which the dev server deploys (below). |

Except for `plugin-release` (which releases every main push), the publish/image
workflows keep **`paths:` filters**, which follow the Gradle
`project(...)` edges, not just the module's own folder (`common/**`, the
version catalog, `build-logic/**`…). When you add a `project(...)` dependency to
a published module, add its path there. `ci.yml` needs no such list: it runs
`check` on everything.

### Conventions

- **Every `uses:` is pinned to a full commit SHA** with the release in a trailing
  comment (`uses: actions/checkout@<sha> # v4.4.0`). Dependabot bumps both
  together (`.github/dependabot.yml`).
- JVM jobs share [`.github/actions/setup-jvm`](../.github/actions/setup-jvm/action.yml)
  after `actions/checkout`: wrapper validation, Temurin 25, `setup-gradle`.
  Invoke the wrapper as `sh ./gradlew` — it is committed without the exec bit.
- Every job has a `timeout-minutes`, and permissions are least-privilege: the
  workflow default is `contents: read`, and jobs that write (checks, PR comments,
  releases) ask for it themselves.
- Workflow inputs and event fields go into scripts through `env:`, never by
  `${{ }}` interpolation inside `run:`.
- Validate locally with [actionlint](https://github.com/rhysd/actionlint) (with
  shellcheck on `PATH`).

### Dependency updates

`.github/dependabot.yml` opens weekly PRs for Gradle (root build and version
catalog, `build-logic`, `integration-tests`), npm (`economy-explorer`), GitHub
Actions (keeping the SHA pins) and the two Dockerfiles. Minor and patch updates
are grouped into one PR per ecosystem; majors come one by one. They go through
`ci-gate` like any other PR.
### Container images & deployment

`treasury-rest-api` and `economy-explorer` ship as Docker images to a **Harbor**
registry and are rolled out by **Argo CD Image Updater**. Images are tagged
`<env>-sha-<short>` per overlay, where the env prefix (`development` /
`production`) is what Argo watches:

- push to **`develop`** → `development-sha-…` (+ `:latest`)
- push to **`main`** → `production-sha-…` (+ `:prod`)

Registry credentials and Harbor coordinates come from repo/organization secrets
(`HARBOR_REGISTRY`, `HARBOR_USERNAME`, `HARBOR_PASSWORD`).

### Dev server

The sandboxed copy of the production server on the paseo machine
(`~/democracycraft-clone`, started by its `start-local.sh`) runs `develop`.
CI never connects to it: `dev-server-publish` publishes the jars and a
`manifest.json` (commit, jar names) as the `dev-latest` prerelease, and
[`ops/dev-server/agent.mjs`](../ops/dev-server/agent.mjs), run by the
`hibernia-dev-server` systemd unit on that machine, polls it every minute.
On a new commit the agent stops the server, runs `:economy-flyway:flywayMigrate`
at that commit against the sandbox database, swaps the plugin jars and starts
the server again. If the server does not reach `Done`, or one of our plugins
fails to enable, it puts the previous jars back (migrations stay applied). Each
deploy shows up as a GitHub deployment to the `democracycraft-dev` environment.
The agent also restarts the server if it crashes.

- Console and agent logs: `journalctl -u hibernia-dev-server -f`
- `node ~/democracycraft-clone/dev-deploy/agent.mjs ctl status | console <cmd> | deploy | pause | resume`
- Pause before running `clone.sh`, then `ctl deploy` after resuming to put the
  `develop` jars back over the production ones it downloads.
- Installing the agent: see the header of
  [`hibernia-dev-server.service`](../ops/dev-server/hibernia-dev-server.service).
  To update it, copy the new `agent.mjs` over the installed one and run
  `ctl restart`.

The development `treasury-rest-api` and `economy-explorer` in the cluster use
the same sandbox database, so the dev API, explorer and server all see the same
data. `start-local.sh` relays the database to srv3's `cilium_host` address,
`10.0.1.37:33306` (pods only), which the gitops development overlays use
directly (a selectorless Service would need an EndpointSlice, and Argo CD
excludes those from sync). The `economy-dev-server` Secret holds the
`economy_dev` login, the dev server plugin's JWT secret and the explorer's
admin token.
[`setup-cluster-secret.sh`](../ops/dev-server/setup-cluster-secret.sh) creates
or refreshes that Secret; run it on srv3 when the token nears expiry (180
days) or the dev server's secrets change.

## Release flow

- Work lands on **`develop`**. The published API snapshots and the development
  container images track `develop`.
- Merge to **`main`** whenever a change is ready to release. Every push runs
  `plugin-release.yml`, which gates publication on the full reusable JVM check.
- CI generates `YEAR.MONTH.RUN.ATTEMPT-gCOMMIT`, for example
  `2026.10.142.1-gabc123def456`. The calendar uses UTC; RUN is the release
  workflow's incrementing run number, ATTEMPT distinguishes retries, and COMMIT
  is the first 12 characters of the main commit. Keep this workflow as the sole
  owner of production versions; independent workflows have independent counters.
- The four plugin filenames and embedded versions, plus the Maven versions of
  `treasury-api` and `business-api`, share this value via Gradle's `-Pversion`.
  No version-bump commit is needed. Local builds keep `2.3.0-SNAPSHOT`; API
  snapshot publishing on develop is unchanged.
- GitHub release `economy-v<VERSION>` contains the four plugins, API jars, and
  `manifest.json`: full commit SHA, version, run ID, attempt, build time, plugin
  filenames and their SHA-256 checksums. API sources/Javadoc jars are included.
  Consumers use the manifest's `jars` list to select the deployable plugins.
- Releases are uploaded as drafts and published only after all assets arrive.
  Existing assets/tags are never overwritten. A failed publish may leave a
  draft or partial Maven publication; rerun failed jobs to get a new attempt
  version. Deploy a completed release, never a draft or partial publication.
- Builds may finish out of order. They deliberately do not update GitHub's
  `latest` pointer. Select an explicit release tag/commit for deployment; do not
  infer deployment order from completion timestamps.
- Develop plugin builds use `YEAR.MONTH.RUN.ATTEMPT-dev-gCOMMIT`. The existing
  `dev-latest` prerelease remains a rolling pointer for the dev deploy agent;
  the manifest's `jars` array remains compatible with that agent.
- Production deployment should download the selected release, verify its
  checksums, and install those exact jars without rebuilding. Retain old
  releases for rollback when the database schema remains compatible.
- Merging stages production plugins for configured SFTP targets. Activation and
  bundled migrations wait for the next server startup; container image workflows
  still run independently on main. See [production staging](#production-plugin-staging-on-merge)
  and use the [release runbook](release-runbook.md) for destructive schema changes.

See [conventions.md](conventions.md) for the branch model and how Tesks issues move
through their workflow.

## Root-build invocation

The build is a **single root Gradle build**. Every JVM job invokes the root wrapper —
`sh ./gradlew build` in CI, or a project-scoped task such as
`sh ./gradlew :treasury-rest-api:bootJar` — rather than `cd`-ing into a project
directory. Only the `economy-explorer` npm steps set `working-directory:`, which is
correct (the explorer is a Node project outside the Gradle graph). New workflows
should follow the `:<project>:<task>` form.

## Production plugin staging on merge

`plugin-release.yml` waits for the exact commit's `ci.yml` **push to main** run
and its `ci-gate` job to succeed before publishing an immutable release. This
covers the full main CI pipeline, including JVM, mutation, explorer, migration
and live integration suites. It replaces the release workflow's duplicate JVM-only
check. PR runs, develop runs and filtered manual CI runs cannot authorize a release.
Manual release runs and retries use the same gate. Failed/cancelled CI, missing or
skipped gates, API errors and a 100-minute wait timeout block publication and
staging. After fixing/rerunning CI, retry the release workflow for that commit.

Its `stage` jobs download that exact release and stage the four economy plugins via
SFTP. They do not restart the server or connect to its database. Treasury applies
its bundled `economy-flyway` migrations when the server next starts, before
creating its application pool, injector, accounts or API registrations.

### Configure targets once

Set repository variable `ECONOMY_DEPLOY_TARGETS` to a JSON list of GitHub
Environment names, for example `["democracycraft", "statecraft"]`. Leaving it
unset (or `[]`) publishes releases without contacting servers. If a target is
listed but required deployment settings are unset or empty, staging exits
successfully with a notice listing the missing setting names and makes no SFTP
connection. `SFTP_PORT` defaults to `22`. Invalid configured values, authentication
failures, checksum failures and transfer errors still fail the staging job.
Each named Environment needs:

| Setting | Kind | Value |
|---|---|---|
| `SFTP_HOST` | secret | Server DNS name or IPv4 address |
| `SFTP_USER` | secret | Deployment SFTP username |
| `SFTP_PASS` | secret | SFTP password (same credential name as the existing Infisical-backed SFTP tools) |
| `SFTP_PRIVATE_KEY` | secret | Optional unencrypted SSH private key instead of a password; authorize its public key on the server |
| `SFTP_KNOWN_HOSTS` | secret | Trusted host key, required unless host-key checking is disabled; use `[host]:port` for nonstandard ports |
| `SFTP_STRICT_HOST_KEY_CHECKING` | variable | `yes` by default; set `no` for targets with rotating Pterodactyl host keys |
| `SFTP_PORT` | variable | SFTP port, default `22` |
| `SFTP_UPDATE_DIR` | variable | Absolute path as seen by SFTP, e.g. `/plugins/update` or `/home/container/plugins/update` |

Create the parent `plugins` directory beforehand. The update path must match
Paper's `settings.update-folder` in `bukkit.yml`. The deployment account needs
read/write/rename access there, including reading uploaded files back for hashing.
Authentication supports the existing password-based SFTP setup or an SSH key.
Host-key checking defaults to strict for both. DemocracyCraft uses `no` because
its Pterodactyl host keys rotate; this disables server identity verification and
requires no `SFTP_KNOWN_HOSTS` secret. Supply deployment-scoped credentials through
the Environment secrets; no local Infisical credentials are copied by this job.

Before enabling automation, install the first complete release during a controlled
restart and configure Treasury's database/migration credentials. Remove any old
pending economy JARs from `plugins/update`: automation owns only `treasury.jar`,
`business.jar`, `chestshop.jar` and `treasury-api-plugin.jar`, and never deletes
unrelated plugins or historical version-named pending files. Keep a database
backup and the previous complete release for recovery. The first upgrade from a
Treasury version without the release check must not overlap an upload.

### Staging and activation

The job validates the release SHA, version, four-plugin manifest and local hashes.
It uploads into a hidden per-release subdirectory of `plugins/update`, reads each
file back and checks its SHA-256, then promotes the files to stable names in the
update directory. Treasury is promoted last. Repeated merges replace the pending
bundle rather than accumulating multiple versions. Abandoned hidden staging
directories can be removed after their jobs finish; Paper does not load them.

Staging jobs serialize per environment. Each checks that its commit is still the
`main` tip before uploading and again before promotion, so a slower superseded
build cannot overwrite a newer staged release. A newer merge that fails CI leaves
the previously staged release in place.

SFTP cannot atomically replace four files with respect to server startup. Treasury
therefore checks all four installed plugin versions before migration and requests
server shutdown if a plugin is missing or has a different version. Migration or
other Treasury startup failures also request shutdown. An interrupted promotion
can require restaging the complete current release before restarting. Avoid
restarting during promotion to avoid this recoverable startup failure. This is
staging for the next normal restart, not a hot reload or an automatic restart.

After restart, check the four plugin versions and Treasury's `Economy schema ready`
startup message. Workflow success means **staged**, not that the server has
activated or health-checked the release. Production SFTP and real restart checks
must be exercised when provisioning the target; unit tests cannot prove a host's
SFTP permissions, update-folder behavior or startup configuration.

### Shared schema and recovery

Treasury bundles the SQL from the existing `economy-flyway` module unchanged and
uses the existing `flyway_schema_history` table. The normal startup path never
baselines, repairs or cleans. Applied checksum mismatches and databases newer than
the bundled migrations fail startup. Supply both `database.migrations.username`
and `database.migrations.password` for a separate migration user; leaving both
empty uses the application credentials. Both connections target the same database.
The migration user needs the DDL/trigger privileges required by the checked-in SQL.

REST API and explorer deployments continue independently. Add schema before
requiring it in those services; keep migrations compatible with their running
versions and deploy destructive changes in a coordinated maintenance window.
Do not automatically roll back JARs after a migration failure: MariaDB DDL can
already have committed. Inspect schema/history, recover from a compatible backup
when needed, and deploy a corrected complete release. The administrative Flyway
workflows remain available for deliberate recovery and rehearsal, not as a second
automatic migration owner.
