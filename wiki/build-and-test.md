# Build & test

## One build to rule them all

Every JVM project is part of a **single root Gradle build**. There is one Gradle
wrapper (Gradle **9.6.0**) and one settings file at the repo root; the individual
projects have no wrapper or settings of their own.

- **Group/version:** `io.paradaux` · Java toolchain **21**.
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

## Convention plugins (`build-logic/`)

Shared plugin-build boilerplate lives in **`build-logic/`**, an included build wired
in via `pluginManagement { includeBuild("build-logic") }` in the root
`settings.gradle.kts`. It publishes two precompiled convention plugins that
subprojects apply by id (no version):

- **`io.paradaux.jvm-conventions`** — applied by all four Paper plugins
  (treasury, business, treasury-api-plugin, chestshop): the Java toolchain (21)
  and `JavaCompile` options (UTF-8, `--release 21`).
- **`io.paradaux.paper-server-conventions`** — applied by the three shading
  *server* plugins (treasury, business, treasury-api-plugin); applies
  `jvm-conventions` transitively and adds the common repositories (with
  `-PuseMavenLocal`-gated `mavenLocal`), `plugin.yml`/`messages` resource
  expansion, the base test setup, the shaded-jar defaults (`archiveClassifier=""`,
  `mergeServiceFiles`, `:jar` disabled), and the `copyPlugin` dev-server staging.

Each project still owns what genuinely differs: its **shadow relocations**, its
**dependencies**, and (treasury/business) its **jacoco coverage gate**. ChestShop
applies only `jvm-conventions` and keeps its bespoke repos/shadow/resources —
they diverge too far to share. `build-logic` compiles on the JDK 21 toolchain
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
1.21.11** API and shaded straight to `chestshop-<version>.jar` (standardised with
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
  more — the single 1.21.11 core replaced them.
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
./gradlew test                 # all tests, all projects
./gradlew :treasury:test       # one project
./gradlew test -PskipIT        # fast loop: unit tests only (Treasury & Business)
```

- **Treasury & Business** enforce a **≥ 95 % line coverage** gate on their in-scope
  set (`jacocoTestCoverageVerification`, wired into `check`). Bukkit/I-O glue is
  excluded from the in-scope set.
- **Integration tests** (`*IT.java`, JUnit tag `integration`) hit a **real
  MariaDB**:
  - **Locally:** an embedded MariaDB is unpacked and run by **MariaDB4j** — no
    Docker needed. `-PskipIT` skips these for a fast unit-only loop.
  - **In CI:** the workflows start a **MariaDB service container** and point tests
    at it via `TREASURY_TEST_JDBC_URL` / `BUSINESS_TEST_JDBC_URL`, bypassing
    MariaDB4j (whose bundled binary needs system libs CI runners don't ship).

> **Heads-up (Windows):** MariaDB4j can be flaky under heavy local build load —
> `mysqld` installs and initializes but occasionally times out reaching
> "ready for connections" within its 30 s window. It's environmental, not a code
> issue. Use `-PskipIT` for fast iteration, or point the tests at a real MariaDB
> the way CI does.

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

## Adding a new module

1. Create the project directory with its own `build.gradle.kts`.
2. `include(":your-module")` in the root `settings.gradle.kts` (map `projectDir`
   if it lives under a non-default path).
3. Apply plugins **without** versions (they're centralized in the root build); add
   any external plugin to the root `plugins { … apply false }` block first.
4. For new plugins that shade, add `tasks.jar { enabled = false }` — with Shadow 9
   both `:jar` and `:shadowJar` target `build/libs/<name>.jar` and `:jar` would
   otherwise overwrite the fat jar.
