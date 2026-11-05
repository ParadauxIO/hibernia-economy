# Business async Bukkit audit (ADT-70)

The remaining scope of [ADT-70](https://app.taskl.ie/w/paradaux/issues/ADT-70)
is the Business plugin's Bukkit access from worker threads. The creation lock,
removed hard-delete mapper and invitation authorization fixes remain unchanged.

## Thread boundaries

`MainThreadAccess.read` executes small live-state reads inline on the server
thread, or submits them through `callSyncMethod` and waits on the worker. It
restores interrupt status and cancels an interrupted or timed-out read. The
five-second timeout prevents a stopped/stalled scheduler from holding a worker
indefinitely. Never put JDBC, HTTP, or a wait for another worker inside this
boundary. Returned `Player` references are recipients, not permission to mutate
Bukkit state from a worker.

`BusinessMessages` schedules the entire framework message send, including UUID
resolution, recipient locale and PlaceholderAPI rendering. Collections and
placeholder arrays are copied before scheduling. Delivery is deferred until a
server tick; an offline UUID receives nothing. Disk-based message reload stays
on its caller. All Business command handlers and firm notifications use this
adapter. The framework's command-dispatch internals are outside this plugin's
audit scope.

## Call-site evidence

| Path | Entry/callers | Resolution |
| --- | --- | --- |
| `FirmPlayerServiceImpl.isOnline/getPlayer` | Async `StaffCommands` and service consumers | Live player lookup inside `MainThreadAccess.read`; invalid/null model UUIDs return offline/null. |
| `FirmStaffServiceImpl.getOnlineEmployees/toOnline` | Async `FirmCommands`, `RequestCommands`, `FirmNotificationServiceImpl`, Carbon chat, public `StaffApi` | Query roster and proprietor on caller, then resolve/filter players in one server-thread read. Public callers must still run DB-backed service operations off-thread. |
| `RequestCommands` target checks and names | All request/transfer routes are `@Async` | Resolve the target once on the server thread, null-check that reference, schedule delivery. Offline profile name reads run on the server thread. Existing transfer broadcast condition and target resolver remain unchanged. |
| `AccountCommands` member names | Async account routes | Existing `FirmPlayerService` directory reads; no Bukkit offline-player lookup. |
| `FirmCommands.adminSetProprietor` | Async route | `hasPlayedBefore`, online status and profile name read through the boundary; DB mutation stays on worker. |
| `FirmChatServiceImpl` spy checks and recipients | Async chat commands and Carbon channel callbacks | Spy lookup plus Bukkit permission check run together on server thread; extra spy recipients also resolve there. Database membership checks remain on caller. |
| `MenuCommands` | Async menu routes | Bukkit permissions read on server thread, data assembled on worker, UI opened in existing `runTask` callback. |
| `FirmMenu` / `FirmDashboard` | Curator view/action callbacks | Server-thread UI lifecycle; framework `MenuManager` enforces its server-thread entry and dispatches callbacks there. |
| `RealtyRegionValidator` | Async HQ update through area-shop service | Service registration resolves on server thread; existing world snapshot already uses `callSyncMethod`. Reflective `getRegionState` is a database query and stays on caller. Reflective method handle is volatile. |
| Startup, join/quit listeners | `Business.onEnable`, `OnlineRosterListener` | Bukkit lifecycle/main-thread callbacks. Resolver uses concurrent UUID roster, not live online-player enumeration. |
| Jobs | Expiry, membership reconciliation, sales notifications | Bukkit calls only register async jobs; notification delivery reaches `BusinessMessages`. |
| Help | `HelpCommands`, without `@Async` | Synchronous command rendering and direct send. |

Player UUID and login name reads on an already-resolved command sender are
identity values; these remain on the caller. No inventory, world, location,
entity mutation or Bukkit permission reads are permitted there. Firm-role
`hasPermission` methods are database authorization, distinct from Bukkit
`Player.hasPermission`.

## Verification

`MainThreadAccessTest` drives real worker threads and a controlled server task
queue. Off-thread player lookup throws; roster queries assert they stay on the
worker. It checks proprietor inclusion, offline filtering, inline reads,
interrupt restoration and timeout cancellation. `BusinessMessagesTest` verifies
that no framework delivery occurs before the scheduled task runs and that
queued recipients/arguments survive caller mutation. Existing player, staff,
chat, command and startup tests cover surrounding behavior.

`JAVA_HOME=<installed Java 25> bash gradlew build -Pci=true --console=plain`
passed across the repository (104 tasks; unchanged tasks could use the build
cache). Plugin staging was disabled by `-Pci=true`.

The completed Business build ran 788 tests with no failures or skips, including
MariaDB integration tests. Coverage passed at 95.75% lines and 89.47% branches;
Spotless and startup suites without CarbonChat or PlaceholderAPI also passed.
The seven new boundary tests passed. Their thread assertions detect a direct
worker lookup or premature delivery; mutation testing was not run.

These checks do not substitute for a live Paper run with CarbonChat, Realty and
PlaceholderAPI installed. They establish the plugin-owned scheduling boundary;
third-party callbacks and plugin shutdown should also be exercised in staging.
