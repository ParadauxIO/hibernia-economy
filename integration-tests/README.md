# integration-tests

End-to-end integration tests for Paper plugins, plus the standalone mock-player library that drives them.

Four Gradle modules:

| Module | Purpose | Reusable? |
|---|---|---|
| **`mock-client`** | A minimal Minecraft Java-edition client. Connects in offline mode, sends slash-commands and chat, places blocks, edits signs, captures responses. No domain dependencies. | **Yes** — designed to be published to Maven and consumed by anyone integration-testing a Paper plugin. |
| **`tests`** | DemocracyCraft economy tests built on top of `mock-client`. Asserts via direct MariaDB queries against the live ledger and via captured chat. Uses RCON for admin-side seeding. | No — tied to this workspace's plugins. |
| **`diag-plugin`** | Disposable-server diagnostics, fixture setup and completion barriers. | Test support only. |
| **`nexo-stub`** | Minimal custom-item substitute for tests. | Does not certify licensed Nexo behavior. |

## `mock-client` — the library

A thin wrapper over [GeyserMC's MCProtocolLib](https://github.com/GeyserMC/MCProtocolLib). The wrapper exists primarily for ergonomics — `MockPlayer` is a `try-with-resources`-friendly class that hides the protocol layer behind methods like `chatCommand`, `useBlock`, `awaitMessage`. Underlying MCProtocolLib types are reachable via the `api` scope of the Maven module if your test needs to drop down to raw packets.

### Public API

```java
import io.paradaux.mockclient.*;
import java.time.Duration;

try (MockPlayer alice = MockPlayer.connectLocal("Alice")) {
    // chat
    alice.chatCommand("/business firm create Acme");
    String response = alice.awaitMessageContaining("created", Duration.ofSeconds(2));
    alice.chat("hello world");

    // position
    Position pos = alice.getPosition().orElseThrow();
    Position teleported = alice.awaitPositionNear(
        new Position(100, 80, 100, 0, 0), 2.0, Duration.ofSeconds(5));

    // blocks / signs
    BlockPos chestSpot = BlockPos.of((int) pos.x(), (int) pos.y() - 1, (int) pos.z());
    alice.useBlock(chestSpot, BlockFace.UP);    // right-click on top face
    alice.updateSign(chestSpot.offset(BlockFace.UP),
        new String[] {"Line 1", "Line 2", "Line 3", "Line 4"}, true);
    alice.breakBlock(chestSpot);

    // generic packet observation
    var loginPkt = alice.awaitClientbound(
        org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket.class,
        p -> p.getEntityId() > 0,
        Duration.ofSeconds(2));
}
```

| Group | Method | Purpose |
|---|---|---|
| **Connect** | `connectLocal(username)` | Connect to `127.0.0.1:25565` (default dev server). Blocks until play state. |
| | `connect(username, host, port, loginTimeout)` | Connect to an arbitrary host. |
| **Chat (send)** | `chatCommand(cmd)` | Send a slash-command. Leading `/` optional. |
| | `chat(message)` | Send a regular chat message (offline-mode only — no signature). |
| **Chat (receive)** | `awaitMessage(predicate, timeout)` | Block until a chat line matches, preserving unrelated replies. Throws `AssertionError` on timeout. |
| | `awaitMessageContaining(substring, timeout)` | Convenience — substring match. |
| | `drainMessages()` | Discard buffered chat lines. |
| | `awaitMessageAfter(trigger, predicate, timeout)` | Run `trigger` (e.g. send a command) and wait for a matching line, leaving every other buffered line in place. |
| **Position** | `getPosition()` | Best-known current position (populated by the time `connect()` returns). |
| | `awaitPositionMatching(predicate, timeout)` | Block until position matches. |
| | `awaitPositionNear(target, radius, timeout)` | Block until within `radius` of `target`. |
| **Blocks / signs** | `selectHotbarSlot(0..8)` / `setSneaking(boolean)` | Select the held item and shift-place against containers. |
| | `useBlock(pos, face)` | Right-click a block face. Triggers `PlayerInteractEvent`. |
| | `breakBlock(pos)` | START_DIGGING + FINISH_DIGGING. Works for instabreak blocks. |
| | `updateSign(pos, lines[4], frontText)` | Set sign text. Sign block must already exist. |
| **Containers** | `quickMoveContainerSlot(containerId, stateId, slot, changedSlots)` | Shift-click using IDs from actual server contents and explicit predicted slot changes; vanilla `HashedStack` values are supplied by the test. |
| | `closeContainer(containerId)` | Close the actual window and trigger normal inventory-close handling. |
| **Generic** | `awaitClientbound(class, predicate, timeout)` | Wait for any clientbound packet of a given type. Escape hatch when the higher-level helpers don't cover what you need. |
| | `awaitClientboundAfter(trigger, class, predicate, timeout)` | Same, but listens *before* running `trigger`, so an immediate answer can't be missed. |
| | `collectClientboundDuring(action, class, timeout)` | Capture packets from synchronous gameplay through a packet barrier, including an empty result for denied window opening. Does not cover async commands. |
| **Barrier** | `sync(timeout)` | Round trip on the connection: returns once the server has handled every packet sent before it (clicks, digs, moves — and the synchronous plugin code they ran) and the client holds everything the server sent meanwhile. Does not order against slash-commands; see the javadoc. |
| **Identity** | `getUsername()` / `getUuid()` | UUID is the offline-mode derivation Paper uses (UUID v3 over `OfflinePlayer:<name>`). |
| **Lifecycle** | `isConnected()` / `close()` / `openPlayers()` | `try-with-resources`-friendly; `openPlayers()` lists the connected players. |

### Helper types

| Type | Purpose |
|---|---|
| `Position` | Record of `(x, y, z, yaw, pitch)`. Includes `distanceTo` and `within` helpers. |
| `BlockPos` | Integer block coordinates. `offset(face)` to step in a direction; `toVector3i()` for raw lib access. |
| `BlockFace` | Enum of the six cardinal faces. Maps onto MCProtocolLib's `Direction`. |

### What this client does NOT do

- **No login authentication.** The server must be in offline-mode (`server.properties: online-mode=false`). The mock client validates usernames against Mojang's regex (`[A-Za-z0-9_]{3,16}`) on `connect()` to fail fast.
- **No client-side inventory prediction, chunk handling, or rendering.** `containerContent(id)` exposes the latest server-sent container contents/state ID, including slot updates. `sendPacket(packet)` drives inventory interactions and stale/forged packet cases; live tests assert authoritative state through DcDiag rather than trusting client predictions.
- **No pathfinding or physics simulation.** `sendPosition(Position)` sends position/rotation packets; fixtures use RCON teleportation for setup.
- **No auto-reconnect.** A disconnect is final; create a new `MockPlayer` if you need a fresh session.

### Using it in your own tests

Settings.gradle.kts:

```kotlin
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // MCProtocolLib SNAPSHOTs
        maven("https://repo.opencollab.dev/maven-snapshots") { mavenContent { snapshotsOnly() } }
        // CloudburstMC math + NukkitX fastutil (transitive deps)
        maven("https://repo.opencollab.dev/maven-releases")  { mavenContent { releasesOnly() }  }
        // plus wherever you publish mock-client
    }
}
```

Build.gradle.kts:

```kotlin
dependencies {
    testImplementation("io.paradaux:mock-client:0.1.0-SNAPSHOT")
}
```

The mock-client module has `withSourcesJar()` / `withJavadocJar()` enabled. No publishing target is wired in this repo — set one in your fork.

## `tests` — DemocracyCraft economy tests

Live integration tests that exercise the Treasury / Business / TreasuryAPI / ChestShop stack against a throwaway server that `ops/integration/run.sh` builds and boots (see [wiki/build-and-test.md](../wiki/build-and-test.md#live-integration-tests)).

### Acceptance scope

[PAR-499's source index and matrix](acceptance/matrix.md) track verified journeys,
remaining reviews, disabled tests and unavailable integrations. Refresh the index
with `python3 integration-tests/acceptance/inventory.py`; use `--check` to detect drift.

The runner executes `:tests:preflight` before selected tests. It requires offline
Paper 26.3, enabled economy/protection plugins, a real login and starting balance,
and an authenticated REST boundary. It prints plugin versions. The protection
profile also checks that both startup integration flags are enabled. Core Nexo coverage
uses **a stub**; the opt-in `nexo` profile requires a real supplied plugin. Same-stack
browser acceptance uses `explorer`; Realty, CarbonChat and Bedrock remain unverified.
The isolated harness targets the same Paper 26.3 version as staging. Passing it
does not certify the separately deployed staging server. Live tests always execute, even with unchanged class files,
because Gradle cannot fingerprint external server/database state.

### Test catalogue

| Test | What it proves |
|---|---|
| `FirstJoinSeedsBalanceTest` | Mock player connects → Treasury fires `FirstPlayerJoinEvent` → seeds `economy.starting-balance` ($10,000) → DB triggers materialise the balance. End-to-end smoke covering protocol, plugin event handlers, ledger logic, and DB triggers. |
| `ChatRoundTripTest` | Chat-command → response round-trip. Demonstrates op'ing the mock player via RCON for privileged commands, then capturing the chat response. |
| `PositionTrackingTest::initialPositionIsPopulatedOnJoin` | After connect, `getPosition()` returns the player's spawn coordinates. |
| `PositionTrackingTest::rconTeleportIsObservedOnTheClient` | Teleport via RCON → client observes new position via `awaitPositionNear`. Combines RCON + position tracking. |
| `MultiPlayerTest` | Two concurrent mock players each see the other's join broadcast. Foundation for cross-player tests (firm hires, transfers, auction bids). |

Those are the foundations; the last full acceptance covered 94 classes (532 cases, including parameterized invocations and explicit skips) across
`business/`, `chestshop/`, `treasury/`, `restapi/` (including the SERVICE-scoped admin API,
`AdminApiTest`) and `ingest/`.

**Runtime** (measured October 2026 on a shared 16-core box, `IT_SKIP_BUILD=1`): about
4 minutes for the `:tests:test` task and 6 minutes for the whole of `run.sh` (stack
boot, migration, tests, teardown); the tests themselves sum to roughly 230 seconds.
A cold run adds the plugin build. Most of the remaining time is real waiting — webhook
delivery and the transaction feed's settlement lag.

### Test infrastructure

```
tests/src/test/java/net/democracycraft/inttests/
├── <area>/…Test.java
└── support/
    ├── Await.java            — the one polling primitive (failure names the last value seen)
    ├── Barrier.java          — positive barriers before "nothing changed" assertions
    ├── Perms.java            — LuckPerms grants that are live when the call returns
    ├── Ops.java              — op + admin grants around an action
    ├── ChestShopFixture.java — shop placement and self-verifying clicks
    ├── Ledger.java / BusinessLedger.java / Market.java / Inv.java — state reads and waiters
    ├── ApiTokens.java / JwtForge.java / RestApi.java / WebhookReceiver.java — REST side
    ├── DevServer.java        — host/port/db connection details, env-var overridable
    └── RconClient.java       — pure-Java RCON client for admin-side seeding
```

### Waiting for the server

Never `Thread.sleep` for the server: a fixed sleep is too long on a fast machine and too
short on a loaded one, and before a "nothing happened" assertion it passes vacuously
whenever the server is slow.

- **State that converges** (a row an async handler writes, a balance a trigger updates):
  poll it with `Await.until(what, probe, condition, timeout)` or a `waitFor…` helper built
  on it. A timeout names what was awaited and the last value observed.
- **Asserting that something did *not* change**: first prove the server has finished
  processing, then assert once. Polling "still unchanged" proves nothing.
  - After clicks, digs or moves: `Barrier.sync(player…)` — a protocol round trip that
    shares Paper's FIFO packet queue with the interaction. `ChestShopFixture.clickBuy` /
    `clickSell` already end with it, and also check (via DcDiag's `PlayerInteractEvent`
    counter) that the click reached Bukkit at all, so a dropped packet can't fake a
    rejection. ChestShop settles a trade synchronously in the click handler, so after the
    click returns balances and stock are final.
  - After slash-commands: `Barrier.settle()` — each connected player's `/dcsync` echo
    proves its commands were dispatched, `/diag asyncidle` proves no economy-plugin
    scheduler task is still queued or running, and a final `sync` delivers their chat.
    Use it before `drainMessages()` too, so the next reply can't be a late line.
- **Permissions:** `lp …` over RCON returns before LuckPerms applies it. Use `Perms.grant`
  / `Perms.revoke` (or `Ops.run`), which wait until `Player#hasPermission` agrees.
- **Product rules, not latency:** ChestShop drops a player's trade clicks closer together
  than `SHOP_INTERACTION_INTERVAL` (250 ms); `ChestShopFixture` spaces them by exactly that.

The test task prints the ten slowest classes and tests at the end and writes every
duration to `tests/build/reports/test-timings.txt`.

`RconClient.send("...")` returns the server's response string. Used in tests for:

- Op'ing / deop'ing players
- Teleporting players (`teleport <player> x y z` or `execute as <player> run teleport ...`)
- Triggering tax cycles (`tax trigger daily`)
- Setting balances during seed phase (`eco set <player> <amount>`)
- Anything else the console can do

### Running

From the repository root:

```bash
bash ops/integration/run.sh                               # everything
bash ops/integration/run.sh -- --tests '*PayCommandTest'  # a subset
bash ops/integration/run.sh --keep                        # leave the stack running afterwards
bash ops/integration/run.sh --profile protection          # WorldGuard + GriefPrevention startup hooks
```

For repeated clean acceptance with retained, JWT-redacted reports:

```bash
python3 ops/integration/acceptance.py                      # two core runs
python3 ops/integration/acceptance.py --profile protection  # two protection runs
```

Each run records commit/working-tree state, Java/plugin versions, test and skip counts,
wall time, JUnit XML, timing report and server/API/DB logs under
`build/integration-acceptance/<timestamp>/run-N/`. A failed run stops the sequence;
there are no test retries. `IT_*` settings work as with `run.sh`. `IT_SKIP_BUILD=1`
reuses the existing plugin jars and is recorded in the summary; use it only after
building the checkout. `python3 ops/integration/test_acceptance.py` checks evidence
counting and redaction without starting a server.

The script builds the plugins from the checkout, boots Paper (offline mode, RCON), a
migrated MariaDB and the REST API, runs `:tests:test` and tears it all down.

To run against a server you already have up (for example one left by `--keep`),
use the root wrapper and point the tests at it:

```bash
./gradlew -p integration-tests :tests:test \
  -PgriefPreventionJar=$HOME/.cache/hibernia-it/GriefPrevention-16.18.6.jar \
  -Ddevserver.host=127.0.0.1 -Ddevserver.port=25565 \
  -Ddevserver.db.url=jdbc:mariadb://127.0.0.1:33306/economy \
  -Ddevserver.db.user=root -Ddevserver.db.password=dev \
  -Drcon.port=25575 -Drcon.password=dev
```

`RESTAPI_BASE` and `RESTAPI_JWT_SECRET` (environment variables) point the REST tests
at the API and its signing secret. `WEBHOOK_RECEIVER_PORT` is the local port the
webhook tests listen on; start the API with
`--treasury.webhook.insecure-test-targets=127.0.0.1:<that port>` so it may deliver there.

### Why JDBC for assertions, not chat scraping

Most assertions go through `Ledger.personalBalance(uuid)` or similar SELECT helpers rather than parsing chat output. Two reasons:

1. **Determinism.** DB state is the ground truth; chat formatting may change without underlying behaviour changing.
2. **Race-free.** Issuing a chat command then reading the response over the wire involves timing assumptions that get flaky under load. A `SELECT` doesn't.

Chat assertions still happen for **command response correctness** ("did the user see the right message?") — that's what `awaitMessageContaining` is for.

### Per-test isolation

`Usernames.fresh` uses an atomic monotonic timestamp suffix, so rapid and concurrent calls remain distinct within a test JVM. The integration runner recreates its disposable database for each clean run. Direct Gradle runs against an existing server can leave fixture rows; usernames are not a substitute for test isolation.

## Compatibility notes

- **Targets Paper 26.3 build 206.** MCProtocolLib pins protocol version, so changing the dev-server MC version may require bumping the `org.geysermc.mcprotocollib:protocol` snapshot version in `mock-client/build.gradle.kts`. The 26.3-SNAPSHOT version lives in OpenCollab's snapshot repo.
- **Java 25 toolchain.** Tests JVM-fork at runtime — they'll use whatever JDK is in `JAVA_HOME`. Set `JAVA_HOME` to a Java 25 JDK before running the harness.
- **Online mode must be disabled** on the target server. The mock client does no authentication.
- **RCON must be enabled** on the target server. Used by `RconClient` for admin-side seeding from inside tests.

Focused clean evidence can be retained without claiming a full suite run:
`python3 ops/integration/acceptance.py --test '*FindDialogTest' --test '*InformationCommandsTest'`.
The per-run summary records the exact filters. `Dialogs` submits the callback IDs
from real server packets and waits for observed world ticks before clicking, since
Paper registers newly created dialog callbacks on its tick queue. It supports
plain-text component inspection and string/float dialog inputs.

The runner exports `IT_SERVER_DIR` for file-backed integration fixtures. Import
tests write and restore files only under that disposable server, and are explicitly
skipped when running against an external server without that capability. Core runs
through `run.sh` provide it automatically. `MockPlayer.collectedMessages()` is a
buffer snapshot for negative chat assertions after a processing barrier.

The dedicated lifecycle profile is `bash ops/integration/run.sh --profile restart`
(or `python3 ops/integration/acceptance.py --profile restart` for two clean runs).
It selects `ServerRestartTest`, which gracefully terminates only the runner-owned
Paper PID, relaunches the pinned Paper jar in the same disposable directory, and
keeps MariaDB/REST running. It checks saved money, posting counts, account IDs,
player inventory, shop state and a subsequent trade. The restarted process is
terminated in a finally block; `server-restart.log` is retained with other evidence.
The test is disabled outside this explicit profile. This covers graceful Paper
restart, not crash recovery, database outage, or REST-process restart.

`bash ops/integration/run.sh --profile tax` runs the isolated corporate tax-cycle
journey. It changes Business's real config file and reloads it, then checks daily/
monthly non-collection, exact weekly debits/credits and posting counts, subsequent
manual cycles, exemption changes, and disabling collection by reload. It restores
configuration afterward. Manual triggers use distinct period starts; this is not
same-period deduplication or wall-clock schedule acceptance. The test is disabled
outside this profile to avoid taxing unrelated fixtures.

The tax profile also runs real weekly Bukkit tasks at explicit accelerated due
periods via DcDiag. It verifies same-period no-op, distinct-period collection and
next-run registration. Natural calendar boundaries and catch-up remain untested.

### Same-stack Explorer browser profile

Run `bash ops/integration/run.sh --profile explorer`, or retain two clean runs with
`python3 ops/integration/acceptance.py --profile explorer`. This profile starts the
same disposable Paper/MariaDB/REST stack, uses real mock players to make a payment,
and launches Explorer against that database. It does not run the standalone
Explorer global setup or reset/reseed the database. `ExplorerFixture` checks exact
ledger outcomes and writes only disposable IDs/memo to `explorer-fixture.json`.

Explorer dependencies are installed from the lockfile when absent. A matching
Playwright Chromium must be installed (from `economy-explorer`, run
`npx playwright install chromium`). The profile builds Explorer by default;
`IT_SKIP_EXPLORER_BUILD=1` reuses an existing verified build. `IT_EXPLORER_PORT`
defaults to 3100, bound to loopback. Browser counts/logs are retained separately;
stale Java JUnit reports cannot inflate an Explorer result.

The browser journey uses signed Auth.js test sessions for the actual game UUIDs,
not the legacy `e2e_viewer` role shim. External OAuth is the substituted seam.
It asserts public balances, owner/recipient history, private history/direct
transaction denial for anonymous and unrelated viewers, and persistence on reload.
It also checks page errors and same-origin HTTP 5xx responses. This is not yet a
complete Explorer page/role/browser matrix or a real Keycloak login test.

### Local export capture

The runner substitutes a loopback Bytebin HTTP service for Treasury's external
paste endpoint. `IT_BYTEBIN_PORT` defaults to 8098; `BYTEBIN_URL` is passed to
tests. Real export code gzips and uploads CSV over HTTP, and tests fetch the
returned link, inspect headers/rows, and force HTTP 503 before recovery. The
runner owns service cleanup; acceptance retains `bytebin.log` and
`bytebin-captures.jsonl`. Captures contain only the disposable test ledger.
This does not certify hosted Bytebin availability, expiry or one-read enforcement.

### Scoped database failures

`PartialDisbandRecoveryTest` uses `LedgerFaults.rejectDebits(accountId)` to inject
a real SQL failure into one fixture account's debit posting. The helper requires
the disposable runner's `IT_SERVER_DIR` marker and a loopback MariaDB URL. Its
AutoCloseable scope drops the uniquely named trigger even when assertions fail.
Expected `PAR499 injected debit failure` warnings are retained in the server log.
The tests distinguish successful admin recovery from the currently failing
proprietor retry; see `acceptance/known-defects.md`.

### Webhook failure profile

`python3 ops/integration/acceptance.py --profile webhook-failure` runs
`WebhookExhaustionTest` twice on disposable stacks. It sets REST webhook
max-attempts=3, failure-threshold=2, retry-base-seconds=1 and retry-max-seconds=2.
The profile covers actual repeated HTTP failures, terminal delivery state,
automatic subscription disabling and admin reactivation followed by successful
health reset. It does not certify the default five-attempt/15-failure thresholds
or default backoff duration. Core-profile retry tests retain default settings.
The test is gated by `IT_WEBHOOK_FAILURE_PROFILE=true`, set by this runner only;
selecting it in another profile reports a skip, not a pass.

The `tax` profile now also selects `TaxConfigPublicationTest` by default. It
changes Treasury's tax configuration on the disposable server, exercises console
reload and reads exact public `/config/tax` snapshots, then restores the original
file. It covers publication and rejected-reload preservation, separately from
Business tax collection in `TaxCycleLifecycleTest`.

### Finder menu regression tests

`FindMenuE2ETest` drives `/find` through its real dialog callback into Curator, using
46 isolated registry rows. It checks the compact title, footer summary, page boundary,
preview item, Back/Escape return, and stale container IDs. Inventory attacks use actual
container packets (click, shift, hotbar/offhand, drop, double-click, drag, creative grab,
and forged carried/changed-slot hashes). DcDiag observes the final server-side
inventory/cursor/menu hashes and dropped-item count at MONITOR priority. Each valid
attack must reach a cancelled Bukkit event; a real transfer from an ordinary chest
is the positive control. Shift-left/right navigation is checked with commands that
target the clicking player. The fixture disables navigation for preview/protection
tests and restores the default navigation commands afterward.

```bash
bash ops/integration/run.sh -- --tests '*FindMenuE2ETest' --tests '*FindCommandTest'
```

These are Java protocol tests on the harness's pinned Paper 26.3 build. They do
not render screenshots or simulate a Bedrock/Geyser client.

### Scheduled Explorer group profile

`python3 ops/integration/acceptance.py --profile groups` runs two clean stacks
with TreasuryAPI's real reconciliation interval set to one second. The test drives
Business offers, role permissions and disband through player commands, then waits
for the production scheduler to publish the expected Explorer membership. It checks
fresh publication for offline staff, revocation after permission removal/archival,
manual-grant preservation and isolation from an unavailable optional Realty source.
SQL configures operator-owned group bindings and observes the results; it does not
invoke reconciliation or synthesize membership. This does not certify Realty or
browser authorization rendering. The test is explicitly gated outside this profile.

The runner validates the four staged plugin ZIP archives before starting services.
If a release artifact is incomplete, rebuild it with `sh ./gradlew release --rerun`.
Avoid running a separate root build while the live runner assembles its server.


### Real Nexo profile

Supply your own compatible Nexo plugin locally; no licensed JAR or existing server
configuration is downloaded from the clone or redistributed by this repository:

```sh
IT_NEXO_JAR=/absolute/path/to/nexo.jar python3 ops/integration/acceptance.py --profile nexo
```

This opt-in profile replaces `nexo-stub` with the supplied artifact, records its
SHA-256 and observed plugin version, and rejects a stub at capability preflight.
It creates one synthetic `test_ruby` item and disables resource-pack generation,
imports and distribution. `RealNexoCustomItemTest` proves that ordinary diamonds
cannot satisfy the custom-item shop, then buys four actual custom items through
a player click and checks exact balances, posting increments and remaining stock.
Item setup/identity observation uses the installed plugin's API; it does not
replace ChestShop's parsing or trading path. Default core tests retain their stub.

Verified runtime is recorded in `acceptance/results.md`. This narrow purchase and
identity-rejection journey does not certify custom-item selling, metadata/dyes,
resource-pack rendering, reloads, other providers or staging runtime parity.
Missing `IT_NEXO_JAR` fails this profile; the real test is explicitly skipped in
core and is not advertised as covered by ordinary CI.
