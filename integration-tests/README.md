# integration-tests

End-to-end integration tests for Paper plugins, plus the standalone mock-player library that drives them.

Two Gradle modules:

| Module | Purpose | Reusable? |
|---|---|---|
| **`mock-client`** | A minimal Minecraft Java-edition client. Connects in offline mode, sends slash-commands and chat, places blocks, edits signs, captures responses. No domain dependencies. | **Yes** — designed to be published to Maven and consumed by anyone integration-testing a Paper plugin. |
| **`tests`** | DemocracyCraft economy tests built on top of `mock-client`. Asserts via direct MariaDB queries against the live ledger and via captured chat. Uses RCON for admin-side seeding. | No — tied to this workspace's plugins. |

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
| **Chat (receive)** | `awaitMessage(predicate, timeout)` | Block until a chat line matches. Throws `AssertionError` on timeout. |
| | `awaitMessageContaining(substring, timeout)` | Convenience — substring match. |
| | `drainMessages()` | Discard buffered chat lines. |
| **Position** | `getPosition()` | Best-known current position (populated by the time `connect()` returns). |
| | `awaitPositionMatching(predicate, timeout)` | Block until position matches. |
| | `awaitPositionNear(target, radius, timeout)` | Block until within `radius` of `target`. |
| **Blocks / signs** | `useBlock(pos, face)` | Right-click a block face. Triggers `PlayerInteractEvent`. |
| | `breakBlock(pos)` | START_DIGGING + FINISH_DIGGING. Works for instabreak blocks. |
| | `updateSign(pos, lines[4], frontText)` | Set sign text. Sign block must already exist. |
| **Generic** | `awaitClientbound(class, predicate, timeout)` | Wait for any clientbound packet of a given type. Escape hatch when the higher-level helpers don't cover what you need. |
| **Identity** | `getUsername()` / `getUuid()` | UUID is the offline-mode derivation Paper uses (UUID v3 over `OfflinePlayer:<name>`). |
| **Lifecycle** | `isConnected()` / `close()` | `try-with-resources`-friendly. |

### Helper types

| Type | Purpose |
|---|---|
| `Position` | Record of `(x, y, z, yaw, pitch)`. Includes `distanceTo` and `within` helpers. |
| `BlockPos` | Integer block coordinates. `offset(face)` to step in a direction; `toVector3i()` for raw lib access. |
| `BlockFace` | Enum of the six cardinal faces. Maps onto MCProtocolLib's `Direction`. |

### What this client does NOT do

- **No login authentication.** The server must be in offline-mode (`server.properties: online-mode=false`). The mock client validates usernames against Mojang's regex (`[A-Za-z0-9_]{3,16}`) on `connect()` to fail fast.
- **No inventory tracking, no chunk handling, no rendering.** If a test needs to assert "alice received an emerald in slot 0," extend the client by listening for `ClientboundContainerSetSlotPacket` via `awaitClientbound`.
- **No movement (yet).** The client *receives* position updates but doesn't *send* them. To move a player, use `RconClient.send("teleport <player> x y z")` from the test side. Active movement (`ServerboundMovePlayerPosPacket`) can be added when needed.
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

Live integration tests that exercise the full Treasury / Business / TreasuryAPI / Realty / ChestShop stack against a throwaway server that `ops/integration/run.sh` builds and boots (see [wiki/build-and-test.md](../wiki/build-and-test.md#live-integration-tests)).

### Test catalogue

| Test | What it proves |
|---|---|
| `FirstJoinSeedsBalanceTest` | Mock player connects → Treasury fires `FirstPlayerJoinEvent` → seeds `economy.starting-balance` ($10,000) → DB triggers materialise the balance. End-to-end smoke covering protocol, plugin event handlers, ledger logic, and DB triggers. |
| `ChatRoundTripTest` | Chat-command → response round-trip. Demonstrates op'ing the mock player via RCON for privileged commands, then capturing the chat response. |
| `PositionTrackingTest::initialPositionIsPopulatedOnJoin` | After connect, `getPosition()` returns the player's spawn coordinates. |
| `PositionTrackingTest::rconTeleportIsObservedOnTheClient` | Teleport via RCON → client observes new position via `awaitPositionNear`. Combines RCON + position tracking. |
| `MultiPlayerTest` | Two concurrent mock players each see the other's join broadcast. Foundation for cross-player tests (firm hires, transfers, auction bids). |

All tests pass in **~3 seconds** total against a healthy dev server.

### Test infrastructure

```
tests/src/test/java/net/democracycraft/inttests/
├── FirstJoinSeedsBalanceTest.java
├── ChatRoundTripTest.java
├── PositionTrackingTest.java
├── MultiPlayerTest.java
└── support/
    ├── DevServer.java     — host/port/db connection details, env-var overridable
    ├── Ledger.java        — JDBC assertion helpers (read account state)
    └── RconClient.java    — pure-Java RCON client for admin-side seeding
```

`RconClient.send("...")` returns the server's response string. Used in tests for:

- Op'ing / deop'ing players
- Teleporting players (`teleport <player> x y z` or `execute as <player> run teleport ...`)
- Triggering tax cycles (`tax trigger daily`)
- Setting balances during seed phase (`eco set <player> <amount>`)
- Anything else the console can do

### Running

From the repository root:

```bash
ops/integration/run.sh                               # everything
ops/integration/run.sh -- --tests '*PayCommandTest'  # a subset
ops/integration/run.sh --keep                        # leave the stack running afterwards
```

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
at the API and its signing secret.

### Why JDBC for assertions, not chat scraping

Most assertions go through `Ledger.personalBalance(uuid)` or similar SELECT helpers rather than parsing chat output. Two reasons:

1. **Determinism.** DB state is the ground truth; chat formatting may change without underlying behaviour changing.
2. **Race-free.** Issuing a chat command then reading the response over the wire involves timing assumptions that get flaky under load. A `SELECT` doesn't.

Chat assertions still happen for **command response correctness** ("did the user see the right message?") — that's what `awaitMessageContaining` is for.

### Per-test isolation

Tests use unique mock-player usernames (e.g. `Mock_<epoch-millis>`) so each run hits the first-join code path even against a non-empty DB. There's no global teardown today; if test data leaks become a problem, wrap each test in a `flywayClean + flywayMigrate` cycle (the `economy-flyway` module supports this) — but that's heavy and shouldn't be the default.

## Compatibility notes

- **Built and tested against Paper 1.21.11.** MCProtocolLib pins protocol version, so changing the dev-server MC version may require bumping the `org.geysermc.mcprotocollib:protocol` snapshot version in `mock-client/build.gradle.kts`. The 1.21.11-SNAPSHOT version lives in OpenCollab's snapshot repo.
- **Java 21 toolchain.** Tests JVM-fork at runtime — they'll use whatever JDK is in `JAVA_HOME`. Java 21 is fine; Java 25 also works.
- **Online mode must be disabled** on the target server. The mock client does no authentication.
- **RCON must be enabled** on the target server. Used by `RconClient` for admin-side seeding from inside tests.
