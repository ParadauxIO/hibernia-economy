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
| `JVM` | `./gradlew build --parallel --continue -Pci=true` — every project's `check` plus `assemble` — against one **MariaDB 10.11** service with a database per suite. Publishes JUnit results as a check. | always |
| `Coverage comment` | Comments Treasury's and Business's JaCoCo coverage on the PR. Advisory: the 95 % gates are `jacocoTestCoverageVerification` in their builds and already ran in `JVM`. | PRs |
| `Explorer` | `explorer-check.yml`: typecheck, lint, vitest with coverage thresholds (MariaDB 10.11), then the Playwright e2e suite. | always; e2e only if the PR touches `economy-explorer/**` |
| `Migrations` | The migration guard, the upgrade test, money invariants and the kysely schema-drift check (below). | always |
| `Integration (live server)` | `ops/integration/run.sh`: a real Paper server with this commit's plugins, the REST API and a migrated MariaDB, driven by the suite in `integration-tests/` (~15 min). | always, except PRs that touch none of the plugins, REST API, `common`, migrations, build files or the suite |
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

## Other workflows

| Workflow | Trigger | Purpose |
|---|---|---|
| `jvm-check` / `explorer-check` | `workflow_call` only | The reusable JVM and explorer test jobs. `ci.yml` calls them, and so do the publish/image workflows, so a release is gated on the same job definition as a merge. |
| `treasury-publish-api` / `business-publish-api` | push to `main`/`develop` | `jvm-check` on that API's projects, then publish the `*-api` jar (snapshot or release) and tag/release on `main`. |
| `treasury-rest-api-docker` | push to `main`/`develop` | `jvm-check` on `:treasury-rest-api:check`, then build and push the image to **Harbor**. |
| `economy-explorer-docker` | push to `main`/`develop`, manual | `explorer-check`, then build and push the image to **Harbor**. |
| `economy-flyway-info` / `-migrate` / `-staging-clean` | manual (`workflow_dispatch`) | Gated schema operations (see [database.md](database.md)). Info's `flywayValidate` ignores pending migrations and fails on anything else. |
| `dev-server-publish` | push to `develop` on a plugin or `economy-flyway/**` | Build the plugin bundle and replace the rolling `dev-latest` prerelease, which the dev server deploys (below). |

The publish/image workflows keep **`paths:` filters**, which follow the Gradle
`project(...)` edges, not just the module's own folder (`common/**`, the
version catalog, `build-logic/**`…). When you add a `project(...)` dependency to
a published module, add its path there. `ci.yml` needs no such list: it runs
`check` on everything.

### Conventions

- **Every `uses:` is pinned to a full commit SHA** with the release in a trailing
  comment (`uses: actions/checkout@<sha> # v4.4.0`). Dependabot bumps both
  together (`.github/dependabot.yml`).
- JVM jobs share [`.github/actions/setup-jvm`](../.github/actions/setup-jvm/action.yml)
  after `actions/checkout`: wrapper validation, Temurin 21, `setup-gradle`.
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
- A release is the **`develop` → `main`** pull request. Its body enumerates every
  `PAR-…` (Tesks ref) shipping in the release; merging tags/releases the API jars
  and promotes the `production` images.
- Merging does **not** migrate the database or deploy the Paper plugins. A release
  that changes the schema follows the [release runbook](release-runbook.md).
- `main` exists only for these release tags — no day-to-day work happens there.

See [conventions.md](conventions.md) for the branch model and how Tesks issues move
through their workflow.

## Root-build invocation

The build is a **single root Gradle build**. Every JVM job invokes the root wrapper —
`sh ./gradlew build` in CI, or a project-scoped task such as
`sh ./gradlew :treasury-rest-api:bootJar` — rather than `cd`-ing into a project
directory. Only the `economy-explorer` npm steps set `working-directory:`, which is
correct (the explorer is a Node project outside the Gradle graph). New workflows
should follow the `:<project>:<task>` form.
