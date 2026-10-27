# Build & test

## One build to rule them all

Every JVM project is part of a **single root Gradle build**. There is one Gradle
wrapper (Gradle **9.8.1**) and one settings file at the repo root; the individual
projects have no wrapper or settings of their own.

- **Group/version:** `io.paradaux` · Java toolchain **25**.
- **Plugin versions are centralized** in the root `build.gradle.kts` (declared with
  `apply false`); each subproject applies them **without** a version. A single
  build rejects the same versioned plugin being requested twice, so don't re-add
  versions in subprojects.
- **Cross-project deps are `project(...)` deps** (e.g. Business depends on
  `project(":treasury:treasury-api")`). Nothing resolves a sibling from a published
  snapshot, so there's no "publish to mavenLocal first" dance for in-repo work.

```bash
./gradlew build          # compile + test every project
./gradlew :treasury:build
./gradlew projects       # see the full project tree
```

On Windows use `.\gradlew.bat`.

## Minecraft runtime baseline

Paper plugins compile against `26.3.build.206-stable` and declare `api-version: 26.3`.
Use **Java 25** for Gradle, the plugins, the REST container and the live harness;
Paper 26.1 and later require Java 25 ([Paper requirements](https://docs.papermc.io/paper/getting-started/)).
CI installs the same JDK through `.github/actions/setup-jvm`.

The isolated harness pins Paper **26.3 build 206** by SHA-256 in
`ops/integration/run.sh`, uses MCProtocolLib `26.3-SNAPSHOT`, and rejects other
Minecraft versions before acceptance tests. MockBukkit uses the matching 26.3
line. Historical results from 1.21.11 remain historical evidence; a successful
source build alone does not certify live plugins, licensed integrations or Bedrock.

Roll out plugin artifacts through the server coordinator after an isolated run
and a database compatibility check. Keep the previous plugin set and configuration
backup together for rollback on the existing Java 25/Paper 26.3 staging runtime.
Rolling back plugins does not roll back database migrations or upgraded worlds;
those require their own verified backup/restore procedure.

## Convention plugins (`build-logic/`)

Shared plugin-build boilerplate lives in **`build-logic/`**, an included build wired
in via `pluginManagement { includeBuild("build-logic") }` in the root
`settings.gradle.kts`. It publishes two precompiled convention plugins that
subprojects apply by id (no version):

- **`io.paradaux.jvm-conventions`** — applied by all four Paper plugins
  (treasury, business, treasury-api-plugin, chestshop): the Java toolchain (25)
  and `JavaCompile` options (UTF-8, `--release 25`).
- **`io.paradaux.paper-server-conventions`** — applied by the three shading
  *server* plugins (treasury, business, treasury-api-plugin); applies
  `jvm-conventions` transitively and adds the common repositories (with
  `-PuseMavenLocal`-gated `mavenLocal`), `plugin.yml`/`messages` resource
  expansion, the base test setup, the shaded-jar defaults (`archiveClassifier=""`,
  `mergeServiceFiles`, `:jar` disabled), and the `copyPlugin` dev-server staging.
- **`io.paradaux.static-analysis-conventions`** — applied by `jvm-conventions`
  (so by every JVM project) and by the root project: Error Prone, NullAway and
  Spotless. See [Static analysis](#static-analysis).

Each project still owns what genuinely differs: its **shadow relocations**, its
**dependencies**, and (treasury/business) its **jacoco coverage gate**. ChestShop
applies only `jvm-conventions` and keeps its bespoke repos/shadow/resources —
they diverge too far to share. `build-logic` compiles on the JDK 25 toolchain
like everything else.

## Build the deployable artifacts

| Artifact | Task | Output |
|---|---|---|
| Treasury plugin | `:treasury:shadowJar` | `treasury/build/libs/` |
| Business plugin | `:business:shadowJar` | `business/build/libs/` |
| API-key plugin | `:treasury-api-plugin:shadowJar` | `treasury-api-plugin/build/libs/` |
| ChestShop | `:chestshop:shadowJar` | `chestshop/build/libs/chestshop-<version>.jar` |
| REST API | `:treasury-rest-api:bootJar` | `treasury-rest-api/build/libs/` |
| API jars | `:treasury:treasury-api:jar`, `:business:business-api:jar` | each `build/libs/` |

A successful `:treasury:shadowJar` / `:business:shadowJar` also **stages** the jar
into `server/plugins/` (via a `copyPlugin` task) for a local dev server. Pass
`-Pci=true` to skip that copy (CI, or to avoid disturbing a running server).

### The release bundle

```bash
./gradlew release        # build every Paper plugin into a clean release/ folder
```

The root `release` task gathers the shaded jar of every deployable Paper plugin
(`:treasury`, `:business`, `:treasury-api-plugin`, `:chestshop`) into `release/`
at the repo root. It's a `Sync`, so the folder is **mirrored** to exactly the
current set of jars — stale artifacts from a prior run are removed, so you always
get a clean folder. `release/` is git-ignored (the `*.jar` rule).

`treasury-rest-api` is intentionally excluded: it ships as a container image
(`bootJar` → Docker → Harbor/Argo CD), not as a server plugin.

### Publishing an API jar

For consumers **outside** this repo:

```bash
./gradlew :treasury:treasury-api:publishToMavenLocal
# or publish to the remote (needs REPO_USER / REPO_PASS):
./gradlew :treasury:treasury-api:publish
```

## ChestShop specifics

ChestShop is a **single module** (`:chestshop`) compiled against the **Paper
26.3** API and shaded straight to `chestshop-<version>.jar` (standardised with
the other plugins — base name = project name, version = the pinned monorepo
version). It was ported from Maven with
upstream's multi-version adapter matrix — a 1.13.2-baseline core plus seven
`Spigot_*`/`Paper_*` adapter modules and an `assemble` module — but since DC runs a
single modern server version, the adapters were folded into the core and the extra
modules removed (including the former `:chestshop:plugin` nesting — the code now
lives at `chestshop/src` like every other project).

Things worth knowing if you touch it:

- **Shading & relocations.** `:chestshop`'s shadowJar bundles HiberniaFramework,
  Guice, Reflections and MyBatis, and relocates only `com.google.inject` /
  `javax.inject` / `org.aopalliance` under `io.paradaux.chestshop.Libs.*`
  (`mergeServiceFiles()`). Adventure is **provided natively by Paper** — `compileOnly`,
  not bundled or relocated (the old bundled+relocated Adventure and the de.themoep
  MineDown/lang libs were removed — MineDown broke against a now-sealed Adventure
  interface). The server/soft-depend APIs are `compileOnly` and never bundled.
- **Persistence is MyBatis over SQLite (PAR-282).** The old ORMLite layer was
  migrated to the same service→mapper/MyBatis annotation-SQL layer the other plugins
  use — just against the existing SQLite files (`users.db`/`items.db`), not MariaDB.
  The SQLite JDBC driver (`org.sqlite.JDBC`) is server-provided at runtime, as it was
  for ORMLite. There are no version-adapter modules or a runtime adapter jar-scan any
  more — the single 26.3 core replaced them.
- **Soft-depend APIs are resolved non-transitively.** Gradle's `compileOnly` pulls
  transitives that Maven's `provided` didn't, dragging in dead/incompatible
  artifacts; the build pins them off. WorldEdit is force-pinned to **7.3.9** (the
  snapshot chain otherwise resolves a build that needs Java 25).
- **Tests** get the provided APIs at *compile* time but only the server API on the
  *runtime* classpath — mixing two Bukkit APIs at runtime breaks Bukkit-backed
  static initializers under test.
- **Vendored jars** live in `chestshop/repo/` (a Maven-layout local repository) and
  are committed.

## Testing

```bash
./gradlew check                # everything CI gates on, all projects
./gradlew test                 # all tests, all projects
./gradlew :treasury:test       # one project
./gradlew test -PskipIT        # fast loop: unit tests only (Treasury & Business)
```

- **`check` is the CI contract.** CI runs `./gradlew build` (every project's
  `check` plus `assemble`), so anything wired into a project's `check` gates
  merges with no workflow change. See [ci-cd.md](ci-cd.md#the-ci-pipeline-ciyml).
- The **build cache** is enabled in `gradle.properties`; `--no-build-cache` forces
  a task to re-run when you suspect a stale cached result.
- **Treasury & Business** enforce a **≥ 95 % line coverage** gate on their in-scope
  set (`jacocoTestCoverageVerification`, wired into `check`). Bukkit/I-O glue is
  excluded from the in-scope set.
- **Integration tests** (`*IT.java`, JUnit tag `integration`) hit a **real
  MariaDB**:
  - **Locally:** an embedded MariaDB is unpacked and run by **MariaDB4j** — no
    Docker needed. `-PskipIT` skips these for a fast unit-only loop.
  - **In CI:** the `JVM` job starts a **MariaDB 10.11 service container** and
    points each suite at its own database via `TREASURY_TEST_JDBC_URL`,
    `BUSINESS_TEST_JDBC_URL`, `TREASURYAPI_TEST_JDBC_URL` and
    `TREASURY_REST_TEST_JDBC_URL` (plus `_DB_USER` / `_DB_PASS`), bypassing
    MariaDB4j (whose bundled binary needs system libs CI runners don't ship).
    Set the same variables to run against a local MariaDB.

> **Heads-up (Windows):** MariaDB4j can be flaky under heavy local build load —
> `mysqld` installs and initializes but occasionally times out reaching
> "ready for connections" within its 30 s window. It's environmental, not a code
> issue. Use `-PskipIT` for fast iteration, or point the tests at a real MariaDB
> the way CI does.

## Static analysis

Every JVM project runs **Error Prone** (+ **NullAway**) inside `javac` and a
ratcheted **Spotless** check in `check`. Config lives in
`build-logic/src/main/kotlin/io.paradaux.static-analysis-conventions.gradle.kts`.

### Formatting (Spotless)

```bash
./gradlew spotlessApply        # format everything you've changed
./gradlew spotlessCheck        # what CI runs (part of ./gradlew check)
```

- Java: **palantir-java-format**. Build scripts: **ktlint** (`intellij_idea`
  style). `*.md`/`*.yml`/`*.sql`: no trailing whitespace, newline at EOF.
- **Ratcheted from `origin/develop`**: only files that differ from it are checked
  or formatted, so the tree is converted one touched file at a time instead of
  in one reformat that would conflict with every open branch. Touch a file, and
  the *whole* file must be formatted — run `spotlessApply` before committing.
- The ref must exist locally (`git fetch origin develop`; in CI, check out with
  `fetch-depth: 0`). If it's missing, Spotless is skipped with a warning — and
  `check` fails when `CI=true`, so a shallow CI checkout can't pass silently.
  Ratchet from something else with `-PspotlessRatchetFrom=<ref>`.

### Error Prone

Findings at ERROR fail compilation (main **and** test sources). On top of Error
Prone's defaults these are promoted to ERROR: `BigDecimalEquals`,
`BigDecimalLiteralDouble`, `MissingCasesInEnumSwitch`, `StringCaseLocaleUsage`,
`DefaultCharset`, `FutureReturnValueIgnored`, `CatchAndPrintStackTrace`,
`OperatorPrecedence`, `UnusedVariable`, `UnusedMethod`. Everything else is a
warning. Fix the finding; the message links the check's docs and usually a fix.

If the check is genuinely wrong at that spot, suppress it on the **narrowest**
element with a one-line reason — never file- or module-wide:

```java
// Identity is the point: a cause chain that loops back on itself.
@SuppressWarnings("ReferenceEquality")
private static Throwable root(Throwable t) { ... }
```

To promote another check, or to understand why one is left as a warning, see
the comments next to `promotedChecks` in the convention plugin.

### NullAway

NullAway is an ERROR, main sources only, and **scoped per project** to code that
has been annotated:

| Project | Null-checked packages |
|---|---|
| `:common` | `io.paradaux.common` |
| `:test-support` | `io.paradaux.hibernia.testsupport` |
| `:treasury:treasury-api` | `io.paradaux.treasury` |
| `:business:business-api` | `io.paradaux.business` |

Everything else (the plugins, `treasury-rest-api`, all tests) is unchecked.
Inside the scope, an unannotated type means *non-null*; mark anything that can be
null with `org.jetbrains.annotations.Nullable` (the annotation the published APIs
already use). Lombok `@Data`/`@NoArgsConstructor` beans are treated as
externally initialised (MyBatis/Jackson fill them), but a nullable column still
needs `@Nullable` on its field. To widen the scope, add the project to
`nullAwayScope` in the convention plugin and fix what it reports.

### Layering (ArchUnit)

Each plugin module and `treasury-rest-api` has an `ArchitectureTest` (plain
JUnit, runs with `test`) enforcing the
[architectural layering](conventions.md#architectural-layering) on the compiled
main classes: entrypoints (commands, listeners, tasks/jobs, dialogs, controllers)
never depend on mappers, mappers never depend on services or entrypoints, and
services never depend on entrypoints. Known, documented exceptions are spelled out
in each test's javadoc rather than frozen silently.

## Live integration tests

The end-to-end suite lives in [`integration-tests/`](../integration-tests/README.md):
a mock Minecraft client, RCON and direct MariaDB assertions against a **real Paper
server** running the plugins built from this checkout. It is its own Gradle build
(not part of the root one, so `./gradlew check` never needs a server). One script is
the whole environment:

```bash
ops/integration/run.sh
ops/integration/run.sh -- --tests '*PayCommandTest'   # a subset
ops/integration/run.sh --keep                          # leave the stack up
```

It builds the plugin jars and the REST API, downloads Paper, LuckPerms, WorldEdit,
WorldGuard and GriefPrevention (cached in `~/.cache/hibernia-it`), migrates an empty
database with `economy-flyway`, starts Paper (offline mode, RCON) and the REST API,
runs the tests and tears everything down. Work files and logs go to
`build/integration/`.

- **Needs:** JDK 25, `curl`, and either a local `mariadbd` (a scratch
  instance is started on port 33306) or `IT_DB_EXTERNAL=1` plus `IT_DB_*` pointing at
  a running server whose `IT_DB_NAME` database may be dropped.
- **Ports:** 25565 (game), 25575 (RCON), 8080 (REST) and 8099 (the tests' webhook
  receiver) by default; override with `IT_MC_PORT`, `IT_RCON_PORT`, `IT_REST_PORT`,
  `IT_WEBHOOK_PORT`, `IT_DB_PORT` to run several stacks side by side, each with its
  own `IT_WORK_DIR`.
- **Webhooks:** the REST API is started with
  `--treasury.webhook.insecure-test-targets=127.0.0.1:<IT_WEBHOOK_PORT>`, which lets
  it deliver to the receiver the tests run on that port. Only that exact `host:port`
  skips the SSRF guard, and the API refuses to start with the setting under the
  `prod` or `uat` profile.
- **Iterating:** `IT_SKIP_BUILD=1` reuses the jars from the last run;
  `IT_GRADLE_ARGS` passes extra arguments to the plugin build (for example
  `-PuseMavenLocal` to test against a locally published hibernia-framework).
- **Timings:** the test task ends by printing the ten slowest classes and tests, and
  writes every duration to `integration-tests/tests/build/reports/test-timings.txt`.
- **Waiting in tests:** never `Thread.sleep` for the server. Poll converging state with
  `Await`, and before asserting that something did *not* happen use a positive barrier
  (`Barrier.sync` / `Barrier.settle`) — see the suite README.
- **In CI:** the `Integration (live server)` job in `ci.yml` runs the same script
  on every push to `develop`/`main`, nightly, and on pull requests that touch a
  plugin, the REST API, `common`, the migrations, the build files or the suite.

## The web app (`economy-explorer`)

Not part of the Gradle build — it has its own npm toolchain (Node **22**):

```bash
cd economy-explorer
npm ci
npm run dev        # dev server at http://localhost:3000
npm run build      # production build
npm run typecheck && npm run lint
npm run coverage   # tests (uses a MariaDB service container in CI)
```

Typed routes are enabled (ADT-151). Run `npm run typecheck` to generate Next's
route declarations before checking navigation. Application navigation models use
`AppHref` from `lib/routes.ts`; shared Next adapters preserve `Route<T>` and
framework-neutral tables carry their caller's href type. Keep literal path types
in URL builders rather than asserting `as Route`. Compile-only negative cases in
`test/types/routes.tsx` ensure misspelled destinations fail the check. The docs
index has an explicit page alongside its catch-all so `/docs` appears in Next's
generated route union.

`npm run typecheck` uses the native TypeScript 7 `tsc` executable, installed as
the `@typescript/native` npm alias. The `typescript` package name aliases
`@typescript/typescript6` so ESLint, Next.js, and code generation can still use
the compiler API that TypeScript 7 does not provide. Keep both aliases when
updating dependencies; `tsc --version` reports 7.x while
`node -p 'require("typescript").version'` reports 6.x.

The SQL integration suite and the Playwright end-to-end suite both need a
**disposable** MariaDB — every table in it is dropped, rebuilt by applying the
real `economy-flyway` migrations in version order (from Node; no JVM needed), and
reseeded from `test/integration/seed.sql`:

```bash
export DB_HOST=127.0.0.1 DB_PORT=3306 DB_NAME=economy_test DB_USERNAME=... DB_PASSWORD=...
RUN_INTEGRATION=1 npm test          # unit + SQL integration (skipped without RUN_INTEGRATION=1)
npx playwright install chromium     # once
npm run test:e2e                    # seeds the DB, builds + starts the app, runs every spec
```

The e2e suite includes `test/e2e/a11y.spec.ts`, an axe-core scan of every
top-level route (logged out and as the shim roles) that fails on serious or
critical violations.

`test:e2e` refuses a `DB_NAME` that doesn't contain `test`. Set `E2E_BASE_URL` to
run only the public specs against an instance that is already running; nothing is
seeded or started in that mode. CI runs both suites in the `Explorer` job of `ci.yml`
(e2e only when the explorer changed, on PRs).

## Adding a new module

1. Create the project directory with its own `build.gradle.kts`.
2. `include(":your-module")` in the root `settings.gradle.kts` (map `projectDir`
   if it lives under a non-default path).
3. Apply plugins **without** versions (they're centralized in the root build); add
   any external plugin to the root `plugins { … apply false }` block first.
4. For new plugins that shade, add `tasks.jar { enabled = false }` — with Shadow 9
   both `:jar` and `:shadowJar` target `build/libs/<name>.jar` and `:jar` would
   otherwise overwrite the fat jar.

### ChestShop recovery audit

`TradeRecoveryService` writes failed goods reversals through `TradeRecoveryMapper`
to `users.db/trade_recovery` (ADT-135). Like the existing ChestShop SQLite tables,
its additive schema is initialized by `DatabaseModule`; it is not part of the
shared MariaDB/Flyway schema. Each attempt is append-only, keyed for correlation by
the transaction's existing trade UUID. The serialized evidence is committed before
returning from the reversal failure path, including for unlimited admin shops.
A failed audit write is logged with its payload and does not escape cancellation.

### Optional shop-search integrations (PAR-173)

ChestShop owns the `/find` presentation and its optional adapters. WorldEdit
cleanup wraps `BEFORE_CHANGE` (including actor-less edits and undo), collects
accepted sign changes and publishes them only after the delegated commit.
The FAWE adapter (compiled against 2.13.1) uses an `IBatchProcessor` postprocessor instead: bulk queue paths can
bypass per-block extents. Old and new tile positions are copied from completed
chunk snapshots; no Bukkit/world/registry calls run on FAWE worker threads.
Both feed a bounded main-thread reconciliation queue. Unloaded positions are
retained until chunk load, and shutdown unregisters the WorldEdit event bus.
All optional APIs are compile-only and remain outside the shaded jar.

WorldGuard's region-name provider is isolated behind `ShopDisplayService` and a
nested integration class so absent plugins do not prevent startup. It supplies
plain-text location and optional preview-name templates; see the admin guide.
