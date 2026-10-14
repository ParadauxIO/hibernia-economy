# CI / CD

All automation lives at the repo root in **`.github/workflows/`** — GitHub only
reads workflows from the root, which is the natural home in a monorepo. Each
workflow is scoped to one component with **`paths:` filters**, so a change to
Treasury doesn't rebuild ChestShop and vice versa.

## Workflows

| Workflow | Trigger | Purpose |
|---|---|---|
| `treasury-build` / `business-build` / `chestshop-build` / `treasury-api-plugin-build` | push/PR on the project | Build the plugin shaded jar (`treasury-api-plugin-build` also runs its tests). |
| `treasury-test` / `business-test` | push/PR | Run tests + JaCoCo against a **MariaDB service container**; post a coverage comment on PRs. |
| `treasury-publish-api` / `business-publish-api` | push to `main`/`develop` | Publish the `*-api` jar (snapshot or release) and tag/release on `main`. |
| `chestshop-test` | push/PR | Build + test ChestShop through the root build (so `treasury-api`/`business-api` resolve locally). |
| `treasury-rest-api-test` | PR; reused by the Docker build | Run the REST API `*IT` suite against a MariaDB service container. |
| `treasury-rest-api-docker` | push | Run the tests, then build and push the Docker image to **Harbor**. |
| `economy-explorer-ci` | push/PR | Typecheck, lint, test (MariaDB service container), then build + push the Docker image. |
| `economy-explorer-schema-drift` | PR touching `economy-flyway/**` | Regenerate the explorer's kysely types from the migrated schema and fail if `lib/db.generated.ts` is stale. |
| `economy-flyway-info` / `-migrate` / `-staging-clean` | manual (`workflow_dispatch`) | Gated schema operations (see [database.md](database.md)). |
| `dev-server-publish` | push to `develop` on a plugin or `economy-flyway/**` | Build the plugin bundle and replace the rolling `dev-latest` prerelease, which the dev server deploys (below). |

**Path filters follow the Gradle `project(...)` edges**, not just the module's own
folder: a workflow must also list every in-repo project its module depends on
(`common/**`, `treasury/treasury-api/**`, `business/business-api/**`,
`test-support/**`, and `economy-flyway/**` where the tests stage the migrations).
When you add a `project(...)` dependency, add its path to that module's workflows.

**Release-path workflows pin actions to a full commit SHA** with the release in a
trailing comment (`uses: actions/checkout@<sha> # v4.4.0`) — the image builds, the
`*-publish-api` workflows, the test workflows they call, and the Flyway workflows
(which hold database credentials). Bump the SHA and the comment together.

### Integration tests in CI

The Java test workflows start a **MariaDB service container** and point the suites
at it via `TREASURY_TEST_JDBC_URL` / `BUSINESS_TEST_JDBC_URL`, which makes the test
harness skip its embedded MariaDB4j path. This is more reliable than MariaDB4j on
CI runners (whose system libs don't match the bundled binary).

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
data. `start-local.sh` relays the database to srv3's `cilium_host` address
(pods only), the gitops `dev-server-db` Service in `development` points there,
and the `economy-dev-server` Secret holds the `economy_dev` login, the dev
server plugin's JWT secret and the explorer's admin token.
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

The build is a **single root Gradle build**. Every JVM workflow already invokes the
root wrapper with a project-scoped task — e.g. `./gradlew --no-daemon :treasury:shadowJar
-Pci=true` — rather than `cd`-ing into a project directory. The only workflows that set
`working-directory:` are the two `economy-explorer` npm jobs, which is correct (the
explorer is a Node project outside the Gradle graph). New workflows should follow the
`:<project>:<task>` form.
