# PAR-499 execution evidence

Initial focused runs used checkout `1f523442ce7fa63571171187e71126355f7ef89e`.
The branch was subsequently fast-forwarded to develop
`b7dfa4a67f964e83bbeebc1edee49c0f82ea923b` (PAR-448 business account scope).
No application production behavior was changed by this task.

Environment: Temurin Java 21.0.12.1, Paper 1.21.11 build 132, Treasury/Business/
TreasuryAPI/ChestShop 2.3.0-SNAPSHOT, LuckPerms 5.5.87, WorldEdit 7.4.2,
WorldGuard 7.0.15, GriefPrevention 16.18.6, Nexo 1.15.0-stub.
MariaDB: 11.8.6 (snapshot isolation explicitly disabled by the existing runner).
All runs use disposable worlds/MariaDB on isolated ports 26565/26575/34306/8180/8199.
They do not exercise or modify the Paper 26.3 staging server.

## Focused execution and fault detection

- Existing `PayCommandTest`: 3 passed before framework changes.
- Typed payments, reconnect, shop controls and mock-client regression checks:
  30 passed including that existing baseline, 21.5 seconds test wall time.
- Real sign placement/editing/trading: 1 passed, 3.8 seconds test wall time.
- Controlled omitted-credit fault: a temporary BEFORE INSERT trigger on the
  **disposable** ledger replaced only positive 12.34 postings whose transaction
  memo ended in `invoice with several words` with zero. All 7 typed target variants
  failed their exact destination-balance assertion. The trigger was dropped in
  a shell EXIT trap. No production source was altered for this experiment.
- After removing the fault: all 28 new cases passed, zero skipped. This run includes
  the 21 typed-payment cases, reconnect, 3 shop lifecycle cases, real placement,
  and 2 mock-client regression cases. XML and timings are preserved locally in
  `.context/par-499/restored-green-results` and `restored-green-timings.txt`.
- Fault XML is retained in `.context/par-499/credit-fault-results`. This demonstrates
  payment-credit fault detection; it is not evidence that every new authorization
  or gameplay assertion has undergone mutation testing.

## Defects found in the framework

1. `MockPlayer.connect` treated latches released by a disconnect as a successful
   login. A GriefPrevention cooldown rejection returned a player object with no
   position; subsequent commands timed out. It now throws at connect with the
   server's reason. `MockClientLifecycleTest` exercises an actual rejected login
   and checks that no phantom player remains registered.
2. `awaitMessage` discarded unrelated replies received while waiting. Competing
   payment responses arrived rejection-first, success-second; awaiting success
   consumed the rejection. Unmatched replies are now restored in a finally block.
   A live deterministic two-message test protects this behavior.
3. Live Gradle tests could be UP-TO-DATE despite replacement external state. The
   test task now always executes, and the runner runs a capability preflight first.

No application money-moving defect has been established by these runs. Same-name
GOVERNMENT creation after a known player is intentionally rejected (PAR-144); the
ambiguity fixture creates the government first, then the player joins naturally.

## Full-suite execution

An initial whole-suite test task on `b7dfa4a` passed: 93 classes, 525 cases,
518 passed, 7 skipped, zero failed/error; 188 seconds for the Gradle test invocation.
The shell runner then failed because its source was edited while Bash was still
reading it. This run is **not** counted as a successful clean acceptance run.
The finalized runner is being executed twice without changes during either run.

Finalized core acceptance, run 1: **525 cases, 518 passed, 7 skipped, zero failed/error**,
runner exit 0, 239.32 seconds including clean stack boot and teardown.
Finalized core acceptance, run 2: **525 cases, 518 passed, 7 skipped, zero failed/error**,
runner exit 0, 246.74 seconds. Both runs were consecutive, from new databases/worlds,
without retries. Production jars were rebuilt from `b7dfa4a` before these repeated
runs; repeats used the recorded `IT_SKIP_BUILD=1` setting. All 29 added cases passed.

Core artifacts: `build/integration-acceptance/20261021T074959Z-2650588/run-1/`
and `run-2/` (including `summary.json`, 93 JUnit reports each, timings, logs).
Protection profile also passed twice: **4 passed, zero skipped/failed/error** each,
62.97 and 72.23 seconds including fresh stack boot and teardown. Both required startup
flags were checked by preflight before running the tests. Artifacts:
`build/integration-acceptance/20261021T075824Z-3878862/run-1/` and `run-2/`.

Across the core and protection profiles, **522 distinct cases passed twice**;
three standalone-ingestion cases remain disabled for the absent TreasuryIngest
plugin. These are execution counts, not a claim that all product behavior is covered.
The two fast evidence/redaction tests also pass; shell syntax, source-index drift
and `git diff --check` checks pass.

The seven core skips are the four startup-hook protection cases (run separately)
and three TreasuryIngest cases requiring the absent standalone plugin. The first attempt's evidence is under `.context/par-499/full-1`.
Final acceptance evidence is retained by `ops/integration/acceptance.py` under
`build/integration-acceptance/`, with per-run summaries and JWT-redacted logs/XML.
See [matrix.md](matrix.md) for all remaining acceptance limitations. PAR-499 remains
open: a green disposable suite does not certify the unsupported integrations,
Paper 26.3, Bedrock, restart/recovery, or same-stack Explorer browser flows.

## Continued command coverage

Added six `GiveCommandTest` invocations and one bypass lifecycle case in
`ShopLifecycleCommandsTest`. These cover exact self/recipient inventories,
default quantity, a 65-item grant, invalid quantity/item rejection, online
permission revocation, and admin bypass opt-out/restoration against another
owner's registry row. No application behavior was changed.

The expanded core suite passed twice consecutively from fresh databases/worlds,
without retries: **94 classes, 532 cases, 525 passed, 7 skipped, zero failures/errors**
on each run. Runner exits were both zero; durations including boot and teardown
were **242.02 seconds** and **239.01 seconds**. The same `b7dfa4a` production jars
and pinned runtime above were used with `IT_SKIP_BUILD=1`.

Evidence: `build/integration-acceptance/20261021T133919Z-540172/run-1/` and
`run-2/`, including summaries, 94 JUnit XML files per run, timings and redacted logs.
Protection-profile code was unchanged and was not rerun in this increment; its
previous two successful runs remain recorded above. The seven core skips are
unchanged. The six gift cases also passed in an earlier focused live run.

The [ChestShop command review](chestshop-commands.md) now records direct assertion
evidence and missing branches for all 29 annotated command routes. This remains
a partial behavioral expansion, not completion of PAR-499.

## Dialog actions, information commands and legacy aliases

Added 13 invocations: two real finder dialog journeys, seven information-command
cases, and four additional owner/outsider alias variants. The focused selection
includes four existing shop lifecycle cases, for 17 total. Both clean focused
runs passed **17/17, zero skipped/failed/error**, runner exit zero, in **69.24s**
and **63.24s**, with no action/test retries. Evidence is under
`build/integration-acceptance/20261021T151230Z-2750186/run-{1,2}/`.
Summaries explicitly record the three test filters; these are not full-suite runs.
The last complete core run remains the 532-case execution above; the expanded
96-class, 545-case suite still needs full repeated acceptance after further work.

`MockPlayer.clickDialogAction` sends the real custom action packet. `Dialogs`
reads callback IDs/additions from the received NBT, supplies string input values,
and decodes component text. Search assertions cover `/find` and `/csdb find`,
visible/hidden registry filtering, exact count/price/stock/quantity, showing a shop
then re-querying, cheapest-first ordering, item inspection and Back navigation.
Information commands exercise all aliases and assert stock/owner/terms without
money or inventory movement. Visibility aliases test owner changes and outsider
rejection through `/find` and `/csdb find`.

Initial immediate callback submission timed out on the second search. Inspection
of the actual pinned Paper bytecode showed `addCallback` queues registrations and
`handleQueue` drains them on a server tick, while callback consumption reads only
the registered map. The helper now observes two advancing world ticks using
`time query gametime` before sending a click; it does not resend an ignored click.
Both subsequent clean runs passed. The dialog helper currently supports string
inputs, not numeric input payloads or Bedrock forms. Advanced filters, pagination,
notifications, imports and restart/recovery remain open.

## Pagination, filters, notifications and preference import

Six additional invocations cover a nine-shop paginated search, notification
persistence over reconnect, and four real preference JSONL import scenarios.
The focused selection also included the two existing finder journeys. Both clean
runs passed **8/8, zero skips/failures/errors**, exit zero, in **70.51s**
and **66.05s**, without retries. Evidence:
`build/integration-acceptance/20261021T151847Z-3062445/run-{1,2}/`.

Pagination checks exact ordered prices on both pages, Previous/Next controls,
saved descending sort with a float priority input, and exclusion of an empty
shop after saving filters. Notification checks prove sale messages suppressed
across reconnect and restored via `/cs notify`, while four trades still produce
exact final balances and item counts. Import tests prove replay preserves choices,
malformed-line stop/corrected resume, missing-file recovery, and permission denial.
The runner supplies `IT_SERVER_DIR`; external-server execution without that
capability explicitly skips file import tests.

An initial pagination attempt completed its assertions but fixture cleanup hit
vanilla command-spam limits. Teardown now uses DcDiag's synthetic break hook to
deactivate registry rows before removing fixture blocks; this is cleanup only,
not evidence of real player digging. Notification passed on the initial attempt.
The full expanded suite (99 classes, 551 cases) still requires repeated complete
acceptance after further coverage work. Legacy shop import, wider filter matrices,
stock/seller notifications, restart/recovery and cross-service journeys remain open.

## Legacy shop import and graceful server restart

Four new `LegacyShopImportTest` cases use real JSONL exports and Bukkit-serialized
diamond items from DcDiag. They assert personal/admin ownership, exact prices,
quantity/stock/display flags, replay preservation, malformed-row rejection,
missing-world stop/corrected resume, missing-file recovery and permission denial.
The two clean focused import runs included the four preference cases: **8/8 passed,
zero skips/failures/errors**, in **65.58s** and **67.30s**, no retries. Evidence:
`build/integration-acceptance/20261021T152304Z-3290132/run-{1,2}/`.
The import does not create a live sign; it intentionally backfills snapshots at
unloaded coordinates. These cases do not certify legacy business/custom-item exports.

The new `restart` profile passed twice: **1/1 passed, zero skips/failures/errors**
each, in **76.68s** and **76.01s**, no retries.
Evidence: `build/integration-acceptance/20261021T152547Z-3519240/run-{1,2}/`.
Both runs retain pre/post-restart server logs. The test performs a real trade,
flushes world saves, gracefully terminates the runner-owned Paper process, and
launches the same jar/world while MariaDB and REST stay up. Reconnected players
retain UUID/account IDs, exact balances and posting counts without reseeding,
saved item inventory and shop registry/stock. A second trade succeeds with exact
money/item changes and one new posting per account. The restarted process is
terminated in finally; all isolated ports are checked after completion.

This covers graceful Paper restart only. Crash recovery, database outage, REST
restart, scheduled processing across downtime, plugin reload configuration, and
Explorer same-stack browser flows remain open. The expanded 101-class/556-case
suite needs repeated full core acceptance after further coverage; the restart
case is intentionally skipped in core and executed by its dedicated profile.

## Observable reload effects and manual corporate tax cycles

`ReloadAliasesTest` adds three invocations through `/chestshop`, `/cs`, and `/csdb`:
a real itemAliases.yml mapping resolves diamond, revoked reload permission leaves
that mapping active despite a changed file, and an authorized reload resolves
emerald. Exact inventory counts distinguish both mappings; original file and
runtime mapping are restored. `TaxCycleLifecycleTest` runs only in the isolated
`tax` profile because its real config reload affects every firm. It checks no
collection on daily/monthly cycles, weekly debits of 100 then 90, exemption skip,
clearing exemption followed by an 81 debit, and disabled-config skip. Government
credits and firm posting counts are exact after each step.

Both fresh focused runs passed **4/4, zero skips/failures/errors**, in
**58.01s** and **58.91s**, no retries. Evidence:
`build/integration-acceptance/20261021T153234Z-3851987/run-{1,2}/`.
An earlier attempt passed all reload cases and tax assertions through clearing
exemption, then the rapid admin sequence hit vanilla command-spam limits. The
administrative fixture now remains operator-enabled for the sequence and is
explicitly de-opped during cleanup. No application behavior was changed.

These manual triggers intentionally use distinct period starts; same-period
idempotency, actual wall-clock firing and scheduler catch-up remain unproven.
The full expanded suite is now 103 classes / 560 invocations (including the two
dedicated lifecycle cases skipped in core), pending repeated full acceptance.

## Explicit-period scheduled tax tasks

The isolated tax journey now additionally enqueues the real `TaxCycleTask.weekly`
on Bukkit's asynchronous scheduler with an explicit due period. The diagnostic
fixture obtains live Treasury services, sets only the due period and schedules
execution one tick later. The production task dispatches the event, collects tax,
and registers its next future run. After the manual 100/90/81 collections, the
first scheduled period charges 72.90, its replay adds no balance/posting changes,
and the next period charges 65.61. Exact firm and government balances and posting
counts prove same-period deduplication and distinct-period progression.

Both clean tax runs passed **1/1, zero skips/failures/errors**, in
**53.79s** and **56.6s**, without retries. Evidence:
`build/integration-acceptance/20261021T153621Z-4035466/run-{1,2}/`.
This proves real scheduled-task execution and re-scheduling, with an accelerated
initial due time. It does not certify waiting for natural calendar boundaries,
DST transitions, or scheduler catch-up after downtime.

The first 560-case full core run after these additions had **550 passed, 1 failed,
9 skipped** (264.47s). The sole failure was FindPaginationTest reopening a dialog:
Paper's log proves the player was kicked for vanilla command spam after nine
fixture-setup commands plus repeated searches. Evidence is preserved under
`build/integration-acceptance/20261021T153831Z-11828/run-1/`. The non-authorization
pagination fixture now stays operator-enabled for its journey and explicitly
de-ops in finally, without changing server spam limits or authorization tests.
Two new complete clean runs are required after this fixture correction.

Explorer preparation: locked dependencies installed with `npm ci --ignore-scripts`,
production `npm run build` passed, and matching Chromium 1248 is already cached.
A no-reset Playwright config and shared-ledger browser journey are drafted under
`economy-explorer/test/live-server/`; they are **not yet executed or wired to the
integration runner** and do not constitute browser acceptance evidence.

## Full-core recheck and countdown assertion correction

After the pagination fixture correction, run 1 passed **551/560, 9 skipped,
zero failures/errors**, in **249.93s**. Run 2 had **550 passed, 1 failed,
9 skipped**, in **263.72s**. Its only failure was the rejected-login test:
GriefPrevention correctly rejected reconnect with 58 seconds remaining, while the
test required the literal text “60 seconds”. The assertion now requires the exact
login-failure/reconnect-cooldown message with a remaining value from 1 through 60,
plus the existing no-phantom-player assertion. It does not loosen successful-login
or unrelated-error handling. This pair is **not** counted as clean acceptance.
Evidence: `build/integration-acceptance/20261021T154322Z-661319/run-{1,2}/`.
Two complete clean core runs are still required after this correction.

## Verified same-stack Explorer journey

The no-reset `explorer` profile passed twice from clean disposable stacks:
**1 browser journey passed, zero skipped/failed/errors**, in **79.37s**
and **59.15s**, including stack startup, player fixture and browser.
Evidence: `build/integration-acceptance/20261021T155235Z-2047789/run-{1,2}/`.
Each includes the browser JUnit report, same-stack fixture IDs, app/server logs
and explicit profile/build flags. Browser retries are zero. The production
Explorer build passed before these runs, which recorded `IT_SKIP_EXPLORER_BUILD=1`.
Chromium 1248 was used through the project's existing Playwright 1.64.1 runner.

`ExplorerFixture` creates sender, recipient and outsider via real Minecraft
logins, sends `/paya player ... 23.45 ...`, and checks exact balances and paired
postings before exporting account/transaction IDs. The browser sees the sender's
9976.55 and recipient's 10023.45 balances in that same database. Signed Auth.js
sessions for the actual UUIDs grant sender/recipient history and sender transaction
access, while anonymous and unrelated viewers see privacy gates without the memo,
including direct transaction URLs after an authorized viewer has warmed caches.
Recipient history persists on browser reload. No page errors or same-origin HTTP
5xx responses occurred. External OAuth is substituted; no role-shim cookie is used.

The evidence collector now accepts Playwright's nested JUnit suites and excludes
stale Java reports/timings for the browser profile. A third fast evidence test
checks that browser failures retain their diagnostics without counting old Java
results. Same-stack business/shop views, live role revocation and wider pages/roles
remain open; this journey does not certify all Explorer behavior.

## Expanded core clean pair after cooldown correction

Both full core runs passed: **560 total, 551 passed, nine expected skips,
zero failures/errors**, in **245.54s** and **262.98s**, without retries.
Evidence: `build/integration-acceptance/20261021T155551Z-2260113/run-{1,2}/`.
The skips are four dedicated protection cases, three unavailable ingest cases,
and the separately executed restart/tax profile cases. This verifies the
countdown assertion correction together with the expanded import/dialog suite.
The subsequent preview/held-item/metrics additions are not included in this pair.

## Fixture identity collision found by preview isolation coverage

The first preview/held-item/metrics selection passed nine of twelve cases but
failed three preview cases at the second player's **initial** login. The server
log showed both intended identities had the same name. `Usernames.fresh()` used
only epoch milliseconds; two rapid calls with the same prefix collided and
GriefPrevention correctly applied its reconnect cooldown. This was not a
preview persistence failure or a missing cooldown bypass.

`UsernamesTest` demonstrates the defect with 5,000 rapid calls and 5,000
concurrent calls: both tests failed on the old generator, then passed with an
atomic monotonic timestamp suffix. The generation contract remains one test
JVM; it does not promise uniqueness across independent processes. No production
plugin or spam protection was changed. Live repetition follows separately.

## Preview, held-item search and metrics clean pair

Both focused runs passed **14 cases, zero failures/errors/skips**, in
**72.41s** and **84.57s**, without retries. Evidence:
`build/integration-acceptance/20261021T160716Z-3731903/run-{1,2}/`.
Twelve cases exercise the live server; two are fast username regressions.

- Preview preferences persist false across reconnect, roundtrip true, and remain
  isolated between players through all four roots. Revoking finder permission
  blocks `/find` and `/csdb find` writes while the owner-facing route remains usable.
- Both held-item routes reject an empty hand with guidance and use the selected
  Diamond slot instead of the Emerald slot, preserving exact inventory quantities.
  Both explicit-code routes report an unknown item without consuming inventory.
- Both metrics aliases report deltas of two transactions (one buy, one sell),
  five items (three bought, two sold), with exact balances and stock; revoked
  admin access cannot read the counters. The 30-minute rollover is not covered.

The shared username correction requires renewed full-suite verification; the
previous 560-case full pair predates it and these fourteen additions.

## Full suite after username fix and command additions

Two clean full runs passed **574 total, 565 passed, nine expected skips,
zero failures/errors**, in **279.6s** and **270.29s**,
without retries. Evidence:
`build/integration-acceptance/20261021T161017Z-3883663/run-{1,2}/`.
This includes the shared identity-generator repair, its two regression checks,
and all twelve new live preview/search/metrics cases. The skip set is unchanged.
The subsequent packet protection cases are verified separately.

## Packet protection fixture diagnosis

The initial real-dig cases incorrectly expected the BlockBreak listener's
access-denied reply. Production `PlayerInteractListener` cancels outsider
sign/chest clicks earlier: creative sign clicks report trade rejection and
chest clicks show read-only shop information to the default shopinfo role.
The checks now assert those dispatch replies, surviving sign/chest, unchanged
registry/stock/balances, and a subsequent exact purchase. This is gameplay
protection evidence, not isolated BlockBreak listener acceptance.

The next run exposed a fixture timing omission: raw sign digging updates
ChestShop's trade throttle, but the fixture tracked only its buy/sell helpers.
The protected sign survived, yet the immediate purchase was correctly throttled
(stock remained 36 instead of 33). `ChestShopFixture.digBlock` now proves Bukkit
interaction delivery, records the handled time, and shares the existing live
configured interval with later trades. It does not retry digging or alter the
server's throttle. The separate nearby stone break is a positive control for
range and creative digging capability.

## Packet shop protection clean pair

Both clean focused runs passed **15 cases, zero failures/errors/skips**, in
**77.39s** and **77.31s**, without retries. Evidence:
`build/integration-acceptance/20261021T162344Z-1287883/run-{1,2}/`.
Selection includes two new real digging cases, eight lifecycle cases, three
existing shop protection cases and two business shop protection cases.
Both new cases break an unprotected control block, attempt the protected sign
or chest, verify exact surviving world/registry/stock/ledger state, then buy
three items for 37: owner 10037, visitor 9963, chest 33 and visitor three dirt.

These two additions and the new dig helper were checked with the affected
protection/lifecycle tests. The preceding full 574-case pair predates them.
No application code or production permission/configuration was changed.

## Resync and live hopper-minecart acceptance

Two clean runs passed **four cases, zero failures/errors/skips**, in
**70.03s** and **67.02s**. Evidence:
`build/integration-acceptance/20261021T163034Z-1684138/run-{1,2}/`.

`ResyncCommandsTest` drives all three roots (`chestshop`, `cs`, `csdb`), refreshes
price/quantity/stock from 37/3/36 to 91/5/60 while preserving identity and hidden
visibility, deactivates a removed sign, preserves an active row in an ungenerated
chunk, and repeats without duplication. Revoked admin permission cannot even
queue a scan or refresh deliberately stale stock; restored permission refreshes
it to seven. No balance or sales mutation occurs. Zero/negative/clamped budgets,
concurrent requests and database-failure recovery remain open.

`HopperMinecartProtectionTest` uses a real rail/cart/container and twenty world
ticks: a protected shop retains all 36 dirt with an empty cart. Removing only the
sign lets that same cart collect all 36, proving an operational transfer path.
The owner balance stays unchanged. This covers enabled minecart protection, not
block-hopper placement prevention or all automation/configuration branches.

## Real container ownership and stock refresh

Two clean combined runs passed **six cases, zero failures/errors/skips**, in
**74.5s** and **70.7s**. Evidence:
`build/integration-acceptance/20261021T163617Z-2140649/run-{1,2}/`.
The selection contains the three resync aliases, live hopper protection,
`ContainerRestockTest` and `OutsiderContainerAccessTest`.

The owner opens the actual chest window, submits quick-move packets using the
server's container/state IDs and explicit slot predictions, deposits five dirt
into the existing 36, then closes the window. Registry stock becomes 41. A
second open/quick-move/close withdraws all 41 and records stock zero, preserving
shop identity and terms without a payment or sale.

The outsider journey proves a positive owner opening produces exactly one
OpenScreen packet, while a non-owner with shopinfo permission receives only
read-only owner/stock/item text and zero OpenScreen packets. Chest stock,
registry, both balances and customer sales remain unchanged. Packet observation
is registered before the action and bounded by the existing synchronous gameplay
barrier; it does not infer denial from an observation timeout.

The mock-client now exposes explicit quick-move/close actions and bounded packet
collection for synchronous gameplay. No optimistic inventory simulator or
substitute Bukkit inventory event was added. Broader click modes, business
roles, bypass/container configuration and server-disconnect-inventory cases remain.

## Full core pair with container and automation coverage

Both clean full runs passed **582 total, 573 passed, nine expected skips,
zero failures/errors**, in **271.77s** and **260.17s**,
without retries. Evidence:
`build/integration-acceptance/20261021T163907Z-2424988/run-{1,2}/`.
This includes the real digging, hopper, resync and container journeys, plus
quick-move/close and packet collection framework methods. The skip set is unchanged.
The subsequent help/version checks are verified separately.

## ChestShop help/version clean pair

Both clean focused runs passed **four cases, zero failures/errors/skips**, in
**55.49s** and **51.95s**, without retries. Evidence:
`build/integration-acceptance/20261021T164820Z-3914824/run-{1,2}/`.
Both roots expose ordinary help without administrative routes, two admin pages
with give/metrics/bypass and reload/resync/version/toggle routes, clamp page zero
to the first page and an excessive page to the last, then restore restricted
help after live permission revocation. Version output matches the loaded plugin
metadata, and revoked admin permission prevents the response.

All 29 indexed ChestShop route rows now have at least one direct live behavioural
case in the command review. This does **not** mean every optional argument, alias,
permission/configuration combination or in-game feature is accepted; the explicit
remaining column still applies. The latest full pair predates these four cases.

## Treasury history, audit and actual CSV export

Both clean focused runs passed **ten cases, zero failures/errors/skips**, in
**68.59s** and **67.87s**, without retries. Evidence:
`build/integration-acceptance/20261021T170007Z-339727/run-{1,2}/`.
Eight cases are new behavioural journeys and two are the existing history/export
regressions, now using the local export endpoint.

- `TransactionHistoryPaginationTest`: both aliases display twelve distinguishable
  receipts newest-first across ten-row and three-row pages (including starting
  funds), exact transaction IDs/signed amounts/memos, correct navigation and
  zero-page clamp, empty extreme page, and no rows after permission revocation.
  Balances remain 10012.78/9987.22 with exactly 24 payment postings.
- `TransactionAuditTest`: both aliases and both player/account-ID forms display
  the other player's exact debit and starting funds without account membership.
  Default/explicit first page and empty second page write exact durable
  actor UUID/name/role, target, method, path/page and outcome records. Revoked
  audit permission yields no rows or additional audit records.
- `TransactionExportTest`: real in-game API key issuance and REST transfer preserve
  a comma/quote memo; in-game own/explicit-own exports POST gzip CSV to the local
  capture service. Downloaded CSV contains exactly the debit and starting grant,
  correct account/transaction/posting IDs, amounts, quoted memo/message and actor.
  Content-Type, Content-Encoding and Bytebin-Max-Reads headers are asserted.
  Audit permission does not authorize exporting another player's account;
  revoked export permission also makes no upload. Forced HTTP 503 produces no
  success response; restored HTTP 201 produces the same CSV with unchanged money.

Two fixture failures preceded this pair. Twelve rapid setup commands followed by
de-op triggered vanilla's accumulated spam limit on the history viewer's next
command. Payments now originate from the fixture counterparty; the actual viewer
remains non-op throughout. Separately, a chat-command memo lost commas/quotes
through intentional argument sanitization, so it could not test CSV escaping.
The export fixture now creates that memo through real REST, asserting its response
before checking CSV. Neither correction changed production behaviour or server
spam settings. Failed evidence remains in `20261021T165444Z-43316/run-1` and
`20261021T165801Z-226694/run-1`.

The Bytebin boundary is a disposable loopback HTTP substitute; real plugin
serialization/upload/download and failure handling run normally. Hosted-service
availability, retention and one-read enforcement are not certified. Captured
requests and service logs are retained. The subsequent full pair also includes
two missing-target audit cases, absent from this focused count.

## Missing-target audit assertion correction

The first expanded core attempt (`20261021T170305Z-541905/run-1`) failed only
the two new missing-player audit cases. They expected a Treasury handler reply,
but the framework rejects names absent from the player cache first, returning
`Invalid target: <name>` (the existing balance regression documents the same
resolver contract). The check now requires that exact rejection including the
name and retains the no-account/no-posting/no-successful-audit assertions.
This failed attempt is not counted as clean full acceptance.

## Personal sales, economy overview and missing-target audit pair

Both clean focused runs passed **eight cases, zero failures/errors/skips**, in
**66.87s** and **63.21s**, without retries. Evidence:
`build/integration-acceptance/20261021T170836Z-1414901/run-{1,2}/`.
Six cases are the expanded audit journeys (including missing player/account),
plus one sales journey and one economy overview journey.

`PersonalSalesCommandTest` exercises root/explicit-page sales, default/explicit
summary days, help, empty page and live permission revocation. Two real shop
trades render owner-side Sold 3 dirt/$37 and Bought 2 dirt/$11, newest first, with
zero tax; summary reports two trades/five units/$48 and correct direction split
and top item. The customer has no owned-shop sales. Money ends at 10026/9974,
stock at 33/2 and customer inventory at one; reporting creates no extra sale.

`EconomyOverviewTest` creates real business/government targets, then moves 91
personal→business, 37 personal→government and 23 personal→personal. Displayed
categories change by −128/+91/+37 while total supply remains unchanged and equals
the category sum. Help and revoked read permission are checked; exact balances
and six postings show reporting did not mutate money. These tests do not yet
cover archived-account exclusions, large/multi-page sales or all summary windows.

## Full core pair after Treasury history/export/reporting expansion

`build/integration-acceptance/20261021T171155Z-1646509/run-{1,2}`:
598 total, **589 passed, 9 skipped**, zero failures/errors on both fresh stacks;
277.42s / 278.28s. Includes both transaction help aliases. Nine skips remain four
protection-profile cases, three absent ingest-plugin cases, restart and tax profiles.
The local Bytebin capture service and failure/recovery assertions ran in both.

## Leaderboard coverage exposed a product defect

`build/integration-acceptance/20261021T172428Z-3287147/run-1`: two tests,
one passed and one failed (57.39s). `BaltopRankingTest` verified twelve real
player balances, descending ranks/names/amounts across pages, default/explicit/zero/negative
pages, help, live permission revocation for all three routes and unchanged money.
`BaltopOverflowProbeTest` failed its intended empty-page contract: `/baltop 2147483647`
returned `An internal error occurred. Please contact an administrator.` The Paper
log records MariaDB error 1064 near `-20` in AccountMapper.getTopBalances.
`BaltopCommand.showBaltop` calculates `(page - 1) * 10` as int, which overflows
to -20 for this input. This is a demonstrated product defect; the regression
remains active and failing, not skipped or changed to expect the bug. Thus the
earlier 589-pass clean pair does **not** establish a green final suite after this
addition. Production code has not been changed; scope clarification is pending.

The preceding probe failed during setup because two username prefixes exceeded
the helper's five-character limit. Shortening those prefixes allowed the actual
behavioural checks above to execute; the setup failure was not defect evidence.

Normal leaderboard/pagination verification: `build/integration-acceptance/20261021T172618Z-3373161/run-{1,2}`,
17 passed, zero failures/errors/skips each; 67.17s / 63.4s.
Selected `BaltopRankingTest`, existing `BaltopCommandTest` and `PaginationEdgeTest`.
This deliberately focused run excludes the separately demonstrated failing overflow
regression and is not a full-suite green claim. Acceptance collector checks (3),
source index drift check (588 entries) and `git diff --check` also passed.

## Government history and direct-viewer lifecycle

`build/integration-acceptance/20261021T173210Z-3730832/run-{1,2}`: two alias cases passed in each clean run,
zero failures/errors/skips; 58.15s / 67.71s.
`GovernmentHistoryAccessTest` creates a real department and twelve incoming
payments of 1.01 through 1.12. Both `gov` and `government` show exactly ten/two
credits with the expected transaction IDs, amounts and memos, newest first,
correct page headers and footer presence/absence. Zero clamps to page one;
maximum int page returns empty. A direct viewer reads the exact 12.78 balance
but cannot pay out; removing the viewer immediately denies history and balance.
Final balances are 12.78 department, 9987.22 funder and 10000 viewer; 24 funding
postings are unchanged and the transaction checkpoint does not advance.

Initial attempt `20261021T173012Z-3590780/run-1` failed at fixture creation: op
alone does not grant explicitly false government permissions. The established
Ops convention grants `treasury.gov.*`; applying that grant fixed setup without
changing assertions or product behavior.

## Government group viewer and neighboring member authorization pair

`build/integration-acceptance/20261021T173647Z-4020691/run-{1,2}`: **8 passed each**, no failures/errors/skips;
75.37s / 74.05s. Includes both aliases for direct-history
and group-viewer journeys, plus four existing `GovMemberTransferTest` cases.
Group viewer reads exact 23.45 balance/history but cannot spend or list viewers.
Removing its LP parent immediately denies reading; restoring it restores access;
removing the department's group grant denies both read routes without reconnect.
Admin viewer lists contain the exact direct UUID name and typed group spelling;
removing the group leaves the direct viewer; removing the latter produces an
empty list. Department/funder/viewer/direct-player balances remain
23.45/9976.55/10000/10000, with no new ledger transactions during access changes.

The prior attempt `20261021T173454Z-3913157/run-1` passed six cases and failed
two list assertions: the test assumed lowercase display, while the mapper stores
and renderer displays the entered group spelling. Corrected the display assertion
to the typed name; the mixed-case access checks and revocation assertions remain.
This is a display characterization, not a product fix. See `government-reads.md`
for eight reviewed routes and explicit remaining branches. The known failing
`BaltopOverflowProbeTest` was outside this focused selection; no full-suite green
claim is made.

## Government role transition pair

`build/integration-acceptance/20261021T174110Z-76718/run-{1,2}`: **6 passed each**, no failures/errors/skips;
74.2s / 69.93s. Includes the existing four viewer cases and
two `GovernmentRoleTransitionTest` alias cases. Lower viewer/member grants and
viewer removal preserve an active AUTHORIZER. The authorizer adds a viewer and
pays 7; demotion yields MEMBER, management is denied and payout of 3 still works.
Viewer removal preserves MEMBER; member removal revokes read/spend. Removing
membership from a pure VIEWER leaves that view access intact. Exact persisted
access levels and actual commands are checked at every transition. Four money
postings are exactly -7/+7/-3/+3, final department 90, funder 9900, actor 10000,
recipient 10010. These are live individual-UUID transitions; group demotion and
reactivation after soft deletion remain separate gaps.

## Government permissions and neighboring command suites

`build/integration-acceptance/20261021T174422Z-285630/run-{1,2}`: **41 passed each**, zero failures/errors/skips;
89.31s / 89.59s. Selection includes four role-transition/permission
alias cases, four direct/group viewer cases, `GovCommandTest`,
`GovCommandRejectionsTest` and `GovMemberTransferTest`.

An AUTHORIZER with revoked view permission cannot read balance/history; restoring
it restores the exact balance. Revoked manage permission prevents adding a viewer
and leaves no target access row; restoring it allows the real grant. Revoking
`treasury.gov` hides history and prevents payout despite the persisted AUTHORIZER
role. The 23.45 government balance, 9976.55 funder and both 10000 personal balances
remain exact, with no new transaction IDs. No production changes were made.

This affected-area selection does not include the known failing
`BaltopOverflowProbeTest`; it is not evidence of a green full core suite.

## Archived typed-payment defect reproduced

`build/integration-acceptance/20261021T174921Z-577206/run-1` exercised
`ArchivedTypedPaymentTest` with both `government <name>` and `account <id>`
targets. Both correctly failed all four rejection assertions: payer replied Paid,
balance fell from 10000 to 9999, archived destination rose from 23.45 to 24.45,
and exactly -1/+1 postings were created. The account's archive flag was verified
before each attempt. This is real player command → actual ledger behavior.

The archive guard is a command-layer obligation: `PayCommand` rejects archived
governments, `GovCommandTest.archivedTransferDestinationIsRejectedEvenForConsole`
pins the government-transfer rejection, and `LedgerServiceBreadthIT` explicitly
documents that the ledger service itself permits archived transfers because callers
enforce archival. `AccountResolver` resolves government/numeric accounts without
checking archived state, and `PayAccountCommand` passes that ID to the ledger.
No production fix has been made; these two regressions remain active and failing
alongside `BaltopOverflowProbeTest`. Full acceptance is therefore not green.

The two normal archive cases in the same run failed at a test text expectation:
`treasury.general.unknown-player` is configured as `That player has never joined
this server.`, not `Unknown player`. That assertion was corrected to the actual
configured response; money/state assertions were retained for the next run.

## Funded government archive lifecycle pair

`build/integration-acceptance/20261021T175050Z-668468/run-{1,2}`: **31 passed each**, no failures/errors/skips;
70.79s / 72.09s. Selection: `GovernmentArchiveLifecycleTest`,
`GovCommandTest`, `GovCommandRejectionsTest`. Both aliases list exact account IDs,
names, balances and relative name ordering before archive. Archiving the funded
23.45 department removes its list row while preserving the other 11 account.
Balance/history, government transfer in/out, payout and ordinary `/pay` reject
the archived target. Recreating its name is rejected without unarchiving it.
The original account ID still has 23.45 and its one original posting; active
account 11, personal funder 9965.55 and the transaction checkpoint are unchanged.

This passing selection excludes `ArchivedTypedPaymentTest`, whose two cases
expose a distinct live archive bypass. See `known-defects.md`. No full-suite
green or complete government acceptance claim is made.

## Shared-account exports and neighboring audit pair

`build/integration-acceptance/20261021T175723Z-977007/run-{1,2}`: **14 passed each**, no failures/errors/skips;
92.21s / 81.17s. Six `TransactionExportTest` cases (personal,
government and business through both aliases), six audit cases and two existing
transaction-command cases ran against each clean stack and local Bytebin capture.

Government viewer access is insufficient for export even with audit permission;
member and authorizer grants export identical CSV, while member removal denies
export with no upload. Exact missing-account response is `Account not found.`,
also with no upload. Business Employee is denied; two actual promotions reach
Manager and enable export; the owner receives identical CSV; demotion to
Supervisor immediately denies export without an upload. Shared CSVs have exactly
the header plus one credit, exact posting/transaction/account IDs and 23.45 amount,
memo and initiator UUID. Both journeys leave the two funding postings intact,
funder at 9976.55, reader at 10000 and target at 23.45.

The first focused attempt passed personal/business cases but government setup
failed because its username prefix exceeded the helper's five-character limit.
The prefix was shortened; an unexecuted missing-ID assertion was also aligned to
the configured export response before this clean pair. Product behavior was not
changed. Group export roles, non-default accounts, archived exports and CSV size/
metadata/network boundaries remain open. Known failing balance-page/archive-payment
regressions were not selected, so this is not a full acceptance pass.

## Funded Business disband lifecycle pair

`build/integration-acceptance/20261021T180429Z-1275708/run-{1,2}`: **19 passed each**, zero failures/errors/skips;
70.12s / 63.84s. Selection: five `FundedDisbandTest` aliases plus
existing `FirmCommandsTest` and `FirmCommandsRejectionsTest`. Two accounts funded
with 23.45/67.89 stay intact after an unarmed confirm or prompt alone. The prompt
shows exact aggregate 91.34. Confirm returns that amount to the proprietor through
exactly two transactions/four postings, archives both zero-balance accounts and
the firm, clears the default account, leaves zero active links and preserves two
historical links. Repeat confirmation creates no extra payout.

The initial run failed all five new cases because the existing `firmAccountCount`
helper counts historical links; the test expected physical deletion. Mapper/schema
inspection established soft removal. Added `activeFirmAccountCount` and now assert
both zero active and two historical rows. No production change or financial
assertion weakening was needed. The original money assertions ran and passed in
both clean runs. Source/assertion review: `business-firm-lifecycle.md`.

This is not a complete Business or full-suite acceptance result; authorization,
confirmation isolation/expiry, partial drain recovery and concurrency remain open,
and the three known failing regression cases were outside this selection.

## Disband confirmation authorization pair

`build/integration-acceptance/20261021T180840Z-1456863/run-{1,2}`: **12 passed each**, no failures/errors/skips;
73.28s / 72.22s. Includes five new `DisbandAuthorizationTest`
alias cases, five funded-disband cases and two existing ownership-theft checks.
The new cases use ordinary owners/other players with explicit disband permission.
Non-proprietor prompt/confirm, confirmation for a different firm, and confirmation
after node revocation leave both firms, account links, balances and the global
transaction checkpoint unchanged.

A real admin proprietor reassignment after the prompt removes the original
owner's disband authority. The new proprietor cannot consume that old prompt,
but their own prompt/confirm returns 23.45 to the correct new proprietor via
exactly -23.45/+23.45 postings. The old owner remains at 10000, funder 9908.66,
new proprietor 10023.45, second firm 67.89; only the first firm is archived and
unlinked. No production changes were required.

This verifies caller/firm/permission/ownership boundaries, not real-time expiry,
concurrent confirmation, or interruption and recovery mid-drain. Known failing
regressions were outside this focused selection.

## Real-time disband expiry pair

`build/integration-acceptance/20261021T181231Z-1679906/run-{1,2}`: **11 passed each**, no failures/errors/skips;
133.66s / 140.02s. Selection includes `DisbandExpiryTest`, five
authorization cases and five funded-disband aliases. The expiry journey waits
61 real seconds after the delivered 60-second prompt (first-run test timing
61.8s). Confirming once after expiry returns the expired/no-pending response;
firm, default pointer, active link, 23.45 account, 9976.55 proprietor and transaction
checkpoint remain unchanged. A fresh prompt and confirm through different aliases
returns exactly 23.45 in two postings and archives/unlinks the firm.

The production clock and TTL were not altered. This deliberately adds about a
minute to the core suite. Boundary-at-exact-expiry, clock jumps, reconnect and
partial-drain recovery remain open. The known failing regression cases were not
selected; this is not a full-suite pass.

## Partial disband fault injection and administrator recovery

A new `LedgerFaults.rejectDebits(accountId)` AutoCloseable helper installs a unique
BEFORE INSERT trigger on the disposable loopback database, rejecting only negative
postings to the specified fixture account. It raises actual SQLSTATE 45000 and
is dropped on scope exit. The rest of the command/service/transaction path is real.

`20261021T181924Z-2294749/run-1` passed five funded-disband cases but failed two
new recovery cases: the first partial drain correctly retained the failed account
and drained the other, then the proprietor was denied when retrying.
`20261021T182159Z-2387013/run-1` separated the paths: two proprietor cases failed
after the fault was removed, while two administrator cases passed. Root cause
is the command's active-only name-based proprietor check; see `known-defects.md`.
These are product failures, not fixture corrections; both proprietor regressions
remain enabled alongside the prior three failing cases.

`build/integration-acceptance/20261021T182329Z-2481240/run-{1,2}`: **12 passed each**, no failures/errors/skips;
73.26s / 82.64s. Selection: two administrator recovery positions,
five funded-disband aliases and five disband authorization aliases. Under the fault,
one account retains 23.45 or 67.89 and its sole funding posting/link/unarchived state;
the other drains to zero and is archived/unlinked. Owner balance is exactly 10000
minus the retained amount, proving the failed transfer did not leave a credit.
Admin retries under the fault create no further postings. After fault removal,
only the retained balance is paid: owner 10000, both accounts zero/archived/unlinked,
two historical links retained, and exactly four successful postings in two
transactions (-23.45/+23.45/-67.89/+67.89).

Five active regression cases now represent three unresolved product defects. This
focused pass is not complete disband acceptance or a green full suite.

## Independent transaction-record rollback assertions and numeric disband probe

`Ledger.initiatedTransactionCountAfter` counts ledger transaction records without
joining postings. Partial-disband checks now require exactly one successful record
after the fault/repeated failed recovery and two after successful recovery, in
addition to exact balances/postings. This catches orphan transaction records.

`20261021T182744Z-2755779/run-1`: two administrator recovery cases passed with
the stronger assertions; two new `NumericFirmDisbandTest` cases failed. The actual
proprietor receives no-permission for a numeric prompt and for numeric confirmation
after a valid name-based prompt. In the latter, the firm/link stay active, balance
23.45 and owner 9976.55 remain, and no payout posting/transaction exists. The handler
resolves the numeric firm ID but then passes its raw token to the name-based
ownership lookup. This is another manifestation of the disband ownership-check
defect, documented in `known-defects.md`; both regressions remain enabled.

`build/integration-acceptance/20261021T183002Z-2822521/run-{1,2}`: **7 passed each**, no failures/errors/skips;
71.45s / 63.45s. Selection: two strengthened administrator
recovery cases plus five funded-disband aliases. No production changes were made.
Seven active failing cases now cover the three known defect areas; this focused
verification does not establish a green full suite.

## Business aggregate leaderboard pair and overflow failure

`build/integration-acceptance/20261021T183713Z-3232994/run-{1,2}`: **12 passed each**, zero failures/errors/skips;
70.32s / 71.3s. Selection: five `FirmBalanceRankingTest` aliases
and existing `FirmCommandsTest`. The leading firm has a lower default balance
but a higher two-account total, so exact rank/amount assertions detect omitted
secondary accounts. Default/explicit/zero/negative pages agree, a distant safe
page is empty, disband excludes the leading firm, and permission revocation blocks
both read forms. Read checkpoints stay unchanged; exact proprietor/viewer balances
are checked. Distinct proprietors avoid violating the real creation cooldown.

Initial `20261021T183436Z-2995891/run-1` had five ranking setup failures because
one proprietor tried creating a second firm within 300 seconds; chat explicitly
reported the cooldown. Separate proprietors fixed that fixture without changing
server configuration. The sixth test, `FirmBalanceOverflowTest`, demonstrated a
real product failure: max-int page wraps to -20, then `ranked.subList` throws
IndexOutOfBoundsException. Its rejection/state assertions show no ledger change
but an internal-error reply. It remains enabled and failing. See the defect report.
Eight active failing cases are retained; this focused green pair is not full
acceptance. Ties, populated second pages and zero/negative totals remain open.

## Business information pair and defunct-list rendering defect

`build/integration-acceptance/20261021T184703Z-3776199/run-{1,2}`: **12 passed each**, zero failures/errors/skips;
67.24s / 72.79s. Selection: all five aliases of
`FirmReadCommandsTest.infoShowsExactMetadataAggregateBalanceAndDefunctState`
plus existing `FirmCommandsTest`. Exact info fields include ID, owner, aggregate
$34.45 across two accounts, initial N/A metadata, changed HQ/Discord, identical
numeric lookup and archived status with zero balance. Permission revocation hides
info; read checkpoints and exact personal balances are checked.

Both `20261021T184239Z-3464247/run-1` and
`20261021T184539Z-3618185/run-1` ran the full new class plus existing commands:
**12 passed, 5 failed**. Each list alias exposes literal strikethrough/red tags
in defunct rows. The second run moves that assertion last without weakening it:
real hire/fire transitions, exact member/owner/other rows, newest-first global
list, explicit page one, global exclusion of archived firms, permission revocation,
unchanged balances and transaction checkpoint all pass first. The five rendering
regressions remain enabled. Thirteen active failures now cover five defect areas;
the focused green pair does not establish full-suite acceptance.

No production behavior was changed. `inventory.py --check` still indexes 588
source entry points; this count is not a behavioral coverage claim. Populated
list pagination, offline owner names and clickable row targets remain open.

## Populated Business list pagination

`build/integration-acceptance/20261021T185037Z-4003829/run-{1,2}`: **8 passed each**, zero failures/errors/skips;
74.86s / 69.05s. Selection: `FirmListPaginationTest` plus existing
`FirmCommandsTest`. Twenty firms are created through commands with independent
proprietors, who disconnect before reads. All five aliases return exact two full
pages of names, IDs and stored owner names, in descending creation order with
page-local ordinals. Default/explicit first pages match, lower pages clamp, a
distant safe page is empty, and revoked permission denies both route forms.
Ledger checkpoints and reader balances remain unchanged. This closes the normal
populated-pagination and stored-offline-name gaps noted above; missing player
records and click targets remain unverified. The known defunct rendering failures
are separate and remain enabled.

`build/integration-acceptance/20261021T185311Z-77077/run-1`: the separate
`FirmListOverflowTest` fails its expected-empty-page assertion. Max-int page
overflows to SQL offset -20 in `FirmServiceImpl.listAllFirms`, producing MariaDB
1064 and an internal-error reply. Grouped balance/checkpoint assertions pass.
The regression remains enabled; fourteen active cases now track six defect areas.
No full-suite green claim follows from the focused pagination pair.

## Firm metadata authority and validation

`build/integration-acceptance/20261021T185745Z-255075/run-{1,2}`: **19 passed each**, zero failures/errors/skips;
74.87s / 99.34s. Selection: twelve new `FirmAttributeAuthorizationTest`
cases plus seven existing `FirmCommandsRejectionsTest` cases. Five aliases cover
ordinary proprietor writes, numeric references, outsider role rejection despite
server nodes, admin override with explicit permission, admin permission revocation
and proprietor node revocation. Exact stored metadata is checked after every
denied write, alongside unchanged ownership, balances and transaction checkpoint.

Seven URL cases exercise both setters with insecure/wrong/javascript schemes,
markup, multiple URL tokens and invite length bounds. Valid 2- and 32-character
codes succeed first; each invalid write preserves the existing URL and reports
Invalid URL. No production code or validation configuration was changed.

Initial `20261021T185545Z-150666/run-1`: 14 passed, five failed because the test
expected the Business role-denial text for the framework's server-permission
denial. The received text was `You don't have permission to do that.`. Separate
exact framework and Business-role assertions corrected this test expectation;
state-preservation assertions were retained, and a fresh clean pair passed.
Realty plot validation remains unavailable; employee ADMIN-role transitions,
archived metadata targets and link-click behavior are not certified here. The
fourteen known product regression failures elsewhere remain active.

## Metadata access follows employee promotion and demotion

`build/integration-acceptance/20261021T190218Z-431335/run-{1,2}`: **17 passed each**, zero failures/errors/skips;
81.74s / 87.79s. Selection: five new `FirmAttributeRoleTransitionTest`
aliases plus twelve `FirmAttributeAuthorizationTest` cases. Real hiring and
promotion establish a Manager, who cannot change HQ or Discord despite holding
the server nodes. Promotion to Co-Proprietor enables both writes via numeric firm
ID. Demotion to Manager immediately denies both again, without relogging and with
server nodes still granted. Exact metadata is checked after each rejected write;
ownership, firm/personal balances and transaction checkpoint remain unchanged.
No fixture correction, permission configuration shortcut or production change was
needed. Direct role-permission edits and archived targets remain separate gaps;
the existing fourteen product regression failures are unaffected.

## Direct role permission edits propagate to metadata and both accounts

`build/integration-acceptance/20261021T190905Z-1002451/run-{1,2}`: **10 passed each**, zero failures/errors/skips;
82.71s / 87.76s. Selection: five new `FirmRolePermissionPropagationTest`
aliases plus five promotion/demotion cases. An Employee remains assigned to that
role and connected while ADMIN is added then removed. HQ/Discord writes change
from denied to allowed to denied; exact metadata survives rejection. Role permission
counts follow 1/2/1 while server nodes stay granted.

Both default and Reserve accounts verify exact owner and employee access:
the owner retains member/authorizer privileges, the employee gains then loses
both, and total counts follow 1/2/1. Account/personal balances and the transaction
checkpoint remain unchanged. No manual sync, reconnect or production change was
used. Initial count-only assertions also passed twice at
`20261021T190608Z-716688` (79.02s / 87.15s); per-player assertions were added to
detect wrong-user grants, then verified in the fresh pair above. Multi-holder
roles, FINANCIAL-only edits, repeated grants and rollback remain unverified here.

## Funded firm renaming and rejection paths

`build/integration-acceptance/20261021T191313Z-1302718/run-{1,2}`: **12 passed each**, zero failures/errors/skips;
89.95s / 77.89s. Selection: five new `FirmRenameLifecycleTest` aliases
plus seven existing `FirmCommandsTest` cases. An explicit server permission grants
a nonmember rename access; revocation denies it. Numeric-ID rename, same-name
no-op and case-only rename succeed with exact stored casing. Collision with
another firm's name in different casing and a digit-prefixed name are rejected.
Old-name lookup disappears after the successful rename, while ID, proprietor,
default/Reserve account links, metadata, exact 23.45/11 balances and the transaction
checkpoint remain intact. No production changes or fixture corrections were
needed. Name release/reuse and downstream labels are not certified by these tests;
the fourteen known failing product regression cases remain enabled elsewhere.

## Disbanded name release and reuse preserve history

`build/integration-acceptance/20261021T191725Z-1552271/run-{1,2}`: **10 passed each**, zero failures/errors/skips;
85.17s / 108.60s. Selection: five new `FirmNameReleaseTest` aliases
plus five funded-rename cases. A funded active firm rejects release. After disband,
its name stays reserved until an explicitly authorized release; revocation denies
the command and repeated release reports the existing state. A new proprietor
can then create the same name with distinct firm/account IDs. Name-based info
selects the replacement, numeric info retains the defunct record, and a real
7.89 payment by name reaches the replacement account only. The original account's
funding/refund postings and original proprietor's posting snapshot remain intact.
Exact flags, active/historical links, ownership and balances are checked. No
production changes, cooldown shortcuts or fixture corrections were needed.
Console, missing IDs, races and release during partial drain remain unverified.

## Partial-drain name reuse: wrong payment destination, isolated numeric recovery

`build/integration-acceptance/20261021T192158Z-1773933/run-1`: five normal
name-release cases passed; the new partial-drain reuse scenario failed because
named payment left the replacement account at zero. Further grouped assertions
at `20261021T192419Z-1843670/run-1` prove the payer lost 11 and the old retained
account rose from 23.45 to 34.45. The replacement stayed zero. This active
`ReleasedNameRecoveryTest.paymentByReusedNameMustReachReplacementWhileOldFirmRetainsFunds`
regression detects ambiguous Treasury display-name resolution after name reuse.
That run's independent numeric-account-funded recovery case passed.

`build/integration-acceptance/20261021T192542Z-1908647/run-{1,2}`: **6 passed each**, zero failures/errors/skips;
69.35s / 66.63s. Selection: the numeric recovery case plus five normal
release/reuse cases. Removing the scoped debit fault and disbanding the old firm
by numeric ID pays its original owner exactly 23.45 once, with one transaction
and two exact postings. The replacement retains its 11, active firm/account,
proprietor and posting history. Repeat recovery creates no transaction. The
new per-ID archive assertions avoid an ambiguous name helper after reuse.

Partial-name release is characterized as existing policy, not endorsed as a
requirement. Funding by exact account ID separates recovery verification from the
retained failing name-payment test; it is not a production fix. Fifteen active
regressions now track seven defect areas. No full-suite acceptance is claimed.

## REST firm profile contracts and invalid-URL persistence

`build/integration-acceptance/20261021T192942Z-2132346/run-1`: two passes
(profile patch journey and existing account scope) and three failures. Every
invalid Discord value in `FirmProfileTest` receives HTTP 200 and replaces the
stored valid invite: javascript scheme, embedded MiniMessage tag and non-Discord
host. Grouped balance/checkpoint assertions pass. The three regressions remain
enabled; no browser-execution claim follows from persisted unsafe input.

`build/integration-acceptance/20261021T193057Z-2210440/run-{1,2}`: **6 passed each**, zero failures/errors/skips;
58.50s / 65.92s. Selection: the new positive/negative profile patch journey,
existing account-scope case and four BUSINESS token cases. Exact profile/DB fields
verify omitted/null preservation, trim/clear semantics, 64/65 HQ boundaries,
overlength Discord and empty-body rejection, and no partial update when one field
is invalid. PERSONAL, account-scoped and anonymous callers cannot mutate metadata.
An unrelated authenticated caller sees the expected public profile/default account.
The funded 23.45, exact personal balances and transaction checkpoint remain intact.

No fixture corrections or production changes were needed. The new `rest-firms.md`
records eight controller routes with evidence and remaining gaps. Eighteen active
regressions now cover eight defect areas; focused passes are not full acceptance.

## REST account presentation and duplicate-name bypass

`build/integration-acceptance/20261021T193436Z-2416900/run-1`: two passing
account-scope/presentation journeys and one duplicate-name regression failure.
REST renaming Reserve to its sibling default account's existing name returns 200
and persists the duplicate. The original account name and transaction checkpoint
stay unchanged; downstream misrouting is not asserted by this test.

`build/integration-acceptance/20261021T193603Z-2513637/run-{1,2}`: **3 passed each**, zero failures/errors/skips;
59.72s / 63.07s. Selection: the new account-list/rename journey,
existing scoped transfer/history case and the positive/negative profile-patch
journey. Exact ordered account rows include ID, name, type, 23.45/11 decimal balances
and archive flag. Whole/scoped/foreign tokens have distinct expected lists;
PERSONAL and anonymous callers are denied. Scoped own-account renaming succeeds,
while sibling/foreign/PERSONAL/anonymous writes are denied without mutation.
Trim, missing/null/blank bodies and 255/256 length limits are verified against
response, database and list readback. Links, default pointer, balances and the
transaction checkpoint remain unchanged. No fixture or production changes were
needed. The duplicate-name regression remains enabled, bringing the known active
failures to nineteen across nine defect areas.

## REST employee and role lists follow live mutations

`build/integration-acceptance/20261021T194004Z-2711149/run-{1,2}`: **6 passed each**, zero failures/errors/skips;
67.23s / 65.65s. Selection: new `FirmStaffReadTest` plus five direct
role-permission propagation aliases. Employee reads check exact UUID, current name,
role and DB join timestamp for two real hires, promotion-driven order, single-row
removal after firing and final emptiness. Role reads check all five default names,
ranks and permission sets, then custom-role creation, FINANCIAL add/remove, rename
and soft-delete exclusion. A foreign firm remains isolated; account-scoped,
PERSONAL and anonymous readers are denied. Personal/account balances, active
link count and transaction checkpoint remain unchanged.

No fixture correction or production changes were needed. Role boolean flag types
are checked, but their intended semantics remain unverified; missing player rows,
rehire/tie ordering and archived-firm behavior remain separate gaps in the REST
review. The nineteen existing failing regressions are unaffected.

## REST balance and account filtering through archive/disband

`build/integration-acceptance/20261021T194349Z-2970359/run-{1,2}`: **4 passed each**, zero failures/errors/skips;
62.74s / 62.61s. Selection: new `FirmBalanceLifecycleTest` plus three
existing `SoftDeleteAuthTest` cases. Public totals carry exact ID/name/decimal sum
for whole/scoped/unrelated PERSONAL tokens, case-insensitive names, missing 404
and anonymous 401. Actual account archive removes the funded Reserve link: total
34.45 → 23.45, whole list contains default only, scoped list is empty, and both
keys cannot rename the old account. Funding postings and retained 11 stay intact.

Actual disband refunds the linked 23.45 only. Public profile is archived with null
default pointer, total 0.00 and account list empty; owner 9989 plus retained 11
accounts for the original funds. Historical links/postings remain and read phases
do not advance the transaction checkpoint. No production changes or fixture
corrections were needed. After verification, comments in `SoftDeleteAuthTest`
were corrected to distinguish its isolated SQL unlink from the now-covered real
archive command; executable test code was unchanged. Retention is characterized
as current archive behavior, not a claim that it is the desired lifecycle policy.

## REST admin disband atomicity under database debit failure

`build/integration-acceptance/20261021T194737Z-3168681/run-{1,2}`: **3 passed each**, zero failures/errors/skips;
62.37s / 63.56s. Selection: both `AdminDisbandAtomicityTest` fault
positions plus existing admin rename/update/disband. A real SQL debit failure on
either funded account produces 500 and rolls back all money, archive flags,
default pointer and link changes. Owner stays 9908.66, accounts retain 23.45/67.89,
and no payout posting or transaction header survives. The second position proves
earlier account processing is also rolled back.

After fault removal, the exact three-account response reports two sweeps to the
proprietor and null amount/destination for the empty account. Four exact postings
in two transactions restore proprietor 10000, while the distinct service operator
stays 10000. All accounts/firm archive, default clears, active links drop to zero
and three historical links remain. Repetition returns an empty archived receipt
without a new transaction. No fixture corrections or production changes were
needed. Expected injected-error logs remain in the evidence bundle. Concurrent
operations, missing personal-account handling and Paper partial-state recovery via
REST remain unverified by this selection; existing nineteen defects remain active.

## REST disband without a proprietor personal account

`build/integration-acceptance/20261021T195135Z-3361034/run-{1,2}`: **4 passed each**, zero failures/errors/skips;
60.28s / 67.22s. Selection: two `AdminDisbandMissingProprietorTest`
branches and two existing atomicity fault positions. The new fixture assigns a
firm to a never-joined offline UUID to model imported ownership. Empty disband
archives both accounts with null payout receipts, without creating any personal
account or transaction. A funded second account instead produces 422
PROPRIETOR_NO_PERSONAL_ACCOUNT and rolls back the first empty account's tentative
archive/removal, preserving defaults, links, 23.45 and all ledger state.

After the proprietor actually joins, the normal starting grant creates one account.
Retrying disband pays that proprietor exactly 23.45 with one transaction and two
postings: new proprietor 10023.45, original funder/service operator 9976.55. Both
accounts/firm archive, default clears and historical links remain. All personal
account rows are counted, including archived ones, to detect accidental creation.
No production changes or fixture corrections were needed. The ownership SQL
setup is explicit fixture preparation, not evidence for the transfer workflow.

## REST retries against completed and partial Paper disband

`build/integration-acceptance/20261021T195506Z-3556814/run-1`: three passes
and one new failure. Completed Paper disband is a safe REST no-op; the two API
atomicity cases also pass. Partial Paper disband is not recovered by REST after
the fault is removed: HTTP 200/archived/empty account receipt, but the account
remains linked with 23.45, owner 9976.55 and no payout transaction. Seven grouped
assertions in `PaperDisbandRecoveryTest` expose the incomplete result. The early
archived guard assumes completion without inspecting retained accounts. That
regression remains enabled.

`build/integration-acceptance/20261021T195638Z-3652211/run-{1,2}`: **5 passed each**, zero failures/errors/skips;
60.84s / 60.62s. Selection: completed-Paper no-op control, both API
atomicity fault positions and both missing-proprietor branches. No production
changes or fixture corrections were needed. The passing control pair does not
accept REST recovery from Paper's partial state. Twenty active failing cases now
track ten defect areas, with evidence in `known-defects.md`.

## Concurrent REST disband calls conserve refunds

`build/integration-acceptance/20261021T200016Z-3864453/run-{1,2}`: **3 passed each**, zero failures/errors/skips;
61.79s / 62.10s. Selection: `ConcurrentAdminDisbandTest` and both
atomicity fault positions. Eight callers released from a shared barrier all return
200/archived. Aggregated receipts report exactly one 23.45 and one 67.89 refund
to the proprietor. Four exact postings in two transaction records leave owner
and separate operator at 10000, sources zero, default null, active links zero and
historical links two. No retries, fixture corrections or production changes were
used. This executes concurrent HTTP calls without forcing a specific DB schedule;
it does not establish exhaustive interleaving or concurrent-credit coverage. The
twenty existing failing regressions remain enabled.

## Funded personal account archive/unarchive through REST

`build/integration-acceptance/20261021T200508Z-4158299/run-{1,2}`: **2 passed each**, zero failures/errors/skips;
57.35s / 87.97s. Selection: new `AdminAccountArchiveTest` and existing
admin account rename/archive/unarchive. Exact five-field summaries and DB state
preserve account identity while trim/rename, archive/repeat and restore/repeat
execute. Non-SERVICE writes are denied without mutation. Archived account transfer
attempts in both directions return 404 ACCOUNT_NOT_FOUND, preserving balances and
transaction checkpoint. The original keys then transfer 23.45 out and 11 back
after restoration: exact receipts, four postings and two transaction records
produce balances 9987.55/10012.45.

Initial `20261021T200358Z-4076904/run-1` (REST port 8190) had one pass and one
fixture assertion failure: BIN_TO_UUID returned uppercase text while the test
compared lowercase strings. Parsing the DB value as UUID corrects representation
without relaxing identity; the fresh pair above used the normal 8180 port. No
production change was made. New `rest-admin-accounts.md` records four routes and
their remaining owner-change/type/concurrency gaps. Existing defects are unchanged.

## Personal account ownership and credential authority

`build/integration-acceptance/20261021T200913Z-133883/run-1`: **2 passed, 2 failed**.
The two former-owner key regressions remain enabled: after confirmed reassignment,
transfer still returns 200 and moves 7.89, and history still returns 200. See
`known-defects.md` for exact balances and source analysis.

`build/integration-acceptance/20261021T201038Z-220565/run-{1,2}`: **3 passed each**,
zero failures/errors/skips; 57.04s / 61.83s. Selection: new-owner first join,
existing admin owner guard, and funded personal archive/unarchive. The new owner
joins with the same funded account and no second grant, then issues a key and
transfers 23.45 with two postings and one transaction. This positive control does
not establish revocation of the former owner's key. Twenty-two failing cases
across eleven defect areas remain documented; no production changes were made.

## Account owner name resolution and rejection atomicity

`build/integration-acceptance/20261021T201601Z-423502/run-{1,2}`: **2 passed each**,
zero failures/errors/skips; 59.59s / 54.44s. Selection: `AccountOwnerResolutionTest`
and the new-owner first-join control from `PersonalOwnershipKeyTest`. A funded
personal account moves to a fresh UUID and back through a padded known player
name. Self-assignment by UUID/name succeeds without creating a second account.
Duplicate-personal-account conflicts by either identifier, invalid body fields,
unknown player/account and unauthorized writes return their expected errors and
preserve both owners, types, archive flags, 10000 balances and ledger checkpoint.
No fixture corrections, retries or production changes were needed. Sensitivity
of this new passing test was reasoned about, not mutation-tested. The existing
old-key regressions are outside this positive selection and remain enabled.
Inventory check remains 588 entry points; `git diff --check` passes.

## Admin key invalidation on money and history routes

`build/integration-acceptance/20261021T201910Z-587024/run-{1,2}`: **3 passed each**,
zero failures/errors/skips; 58.09s / 53.08s. Selection: new
`AdminKeyLifecycleTest` and both existing admin API-key tests. Real PERSONAL
issue, SERVICE force-rotation, Paper reissue, repeated SERVICE revoke and refused
rotation of a revoked key execute with live REST transfers/history. Old tokens
return 401 TOKEN_SUPERSEDED or TOKEN_REVOKED with unchanged balances/checkpoint.
Reissue restores the same key ID/account with a new JTI; independent-key payments
continue. Exact transfer receipts and final balances 9992/10008 verify the
successful paths. No fixture corrections, retries or production changes were
needed. Sensitivity was reasoned about, not mutation-tested. New
`rest-admin-keys.md` records remaining type, ownership and concurrency gaps.
Inventory check (588 entry points) and `git diff --check` pass. Existing failing
regressions remain enabled and were not part of this positive selection.

## Business key scope survives admin rotation and Paper reissue

`build/integration-acceptance/20261021T202220Z-768559/run-{1,2}`: **4 passed each**,
zero failures/errors/skips; 59.74s / 54.62s. Selection: both
`BusinessKeyLifecycleTest` modes, `BusinessAccountScopeTest` and
`AdminKeyLifecycleTest`. Firm-wide and account-scoped credentials first spend
1.25 legitimately, then lose money/history access after admin force-rotation.
Paper reissue preserves key/firm/owner/optional account binding and permits 7.89;
the restricted key still cannot spend/read its sibling. Admin revocation blocks
both routes and a subsequent rotate returns 409 without JTI mutation. Exact
receipts and balances (23.45 primary, 58.75 Reserve, 10009.14 recipient, 9908.66
proprietor), unchanged default/link identity and rejection checkpoints protect
the ledger effects. No fixture corrections, retries or production changes were
needed. Sensitivity was reasoned about rather than mutation-tested. Inventory
check (588) and `git diff --check` pass; existing failing regressions remain
active outside this positive selection. Ownership changes and concurrency are
not established by these stable-membership cases.

## SERVICE token scope and self-invalidation

`build/integration-acceptance/20261021T202524Z-951770/run-{1,2}`: **5 passed each**,
zero failures/errors/skips; 61.75s / 58.73s. Selection: two
`ServiceKeyLifecycleTest` modes, `AdminKeyLifecycleTest` and both
`BusinessKeyLifecycleTest` modes. Normal auth rotation retains SERVICE scope for
admin rename and a 23.45 transfer while invalidating the original token. Admin
self-force-rotation or self-revocation then blocks subsequent rename, transfer
and auth rotation with exact 401 errors; persisted name, balances and checkpoint
remain unchanged. Separate SERVICE credentials still rename and transfer 11
back, ending at 9987.55/10012.45 with one transaction/two postings attributed to
the second service owner. JTI/revoked-state assertions distinguish the two modes.
No fixture corrections, retries or production changes were needed. Sensitivity
was reasoned about, not mutation-tested. Inventory (588) and diff whitespace
checks pass. This is sequential invalidation; concurrent/in-flight requests and
ownership-change authority remain separate gaps. Existing failing regressions
were not part of this selection and remain enabled.

## Admin webhook mutations affect actual delivery

`build/integration-acceptance/20261021T202848Z-1146551/run-{1,2}`: **4 passed each**,
zero failures/errors/skips; 85.10s / 82.11s. Selection: new
`AdminWebhookDeliveryTest` and existing `WebhookDeliveryTest`. The receiver now
records request paths. Admin-created subscriptions deliver exact debit payloads
with independently recomputed HMAC. Wrong-owner URL/secret/active/delete writes
report zero affected and preserve the original delivery destination/signature.
Real URL/secret changes affect the next delivery. Pause, resume, deletion and
repeat deletion execute; recipient control events establish dispatcher progress
for bounded non-delivery checks. Five transfers leave balances 9995/10005.
No fixture corrections, retries or production changes were needed. Sensitivity
was reasoned about rather than mutation-tested. The selected neighboring tests
also pass with the receiver extension. Inventory (588) and `git diff --check`
pass. See `rest-admin-webhooks.md` for queued-event, retry, concurrency and firm
scope gaps. Existing failing regressions remain active outside this selection.

## Real HTTP failure followed by webhook retry

`build/integration-acceptance/20261021T203305Z-1508436/run-{1,2}`: **5 passed each**,
zero failures/errors/skips; 123.13s / 129.16s. Selection: `WebhookRetryTest` and
both public/admin delivery classes matched by `*WebhookDeliveryTest`. The
receiver's path-scoped fault rule returns 503 once per delivery ID, then 200.
The live dispatcher records PENDING/attempts=1/HTTP 503 with future retry time,
then DELIVERED/attempts=2/HTTP 200 and cleared error under normal retry settings.
Two actual requests have identical raw payloads, delivery IDs and signatures.
A 23.45 payment leaves balances 9976.55/10023.45 with only one transaction and
two sender-initiated postings. Neighboring signature, pause, SSRF and admin
lifecycle tests also pass with the receiver extension. No fixture corrections,
production changes or retry-delay overrides were used. The receiver Javadoc was
clarified during the run; executable sources remained unchanged. The real 503
fault demonstrates recovery; no production mutation experiment was performed.
Inventory (588) and `git diff --check` pass. Exhaustion, auto-disable and restart
recovery remain gaps; known failing regressions remain enabled outside selection.

## Queued retry picks up changed webhook configuration

`build/integration-acceptance/20261021T203804Z-2043774/run-{1,2}`: **2 passed each**,
zero failures/errors/skips; 129.42s / 125.51s. Selection: both
`WebhookRetryTest` methods. After a real 503 and persisted PENDING state with a
future retry, admin changes destination and secret. The retry reaches the new
path and verifies against the new secret, retaining the same raw payload and
delivery header. Exactly two requests and attempts=2 establish reuse of the
queued delivery. The 7.89 payment leaves balances 9992.11/10007.89, one ledger
transaction and two postings. The unchanged-configuration retry control passes
alongside it. No fixture corrections, retries of tests, production changes or
backoff overrides were needed. This characterizes current-subscription lookup
before retry selection; in-flight mutation and atomic URL/secret changes are
not covered. Inventory (588) and diff whitespace checks pass. Known failing
regressions remain enabled outside this selection.

## Retry exhaustion, auto-disable and admin recovery profile

`build/integration-acceptance/20261021T204352Z-2599707/run-{1,2}`: **1 passed each**,
zero failures/errors/skips; 82.58s / 85.20s. New `webhook-failure` profile runs
`WebhookExhaustionTest` with max-attempts=3, failure-threshold=2 and retry-base/max
1/2 seconds. Two payments each exhaust three actual HTTP 503 attempts and become
FAILED. The first leaves the subscription active; the second records disabled_at
and inactive state. Removing the path-scoped fault and reactivating via admin
lets a third payment deliver and resets consecutive failures. Terminal failures
remain recorded; balances 9997/10003 and exactly three transactions/six postings
show no extra money movement. No fixture corrections or production changes were
needed. These explicit short thresholds do not certify default numerical values.

`build/integration-acceptance/20261021T204700Z-2898604/run-1`: **4 passed**, zero
failures/errors/skips; 91.48s. The normal-profile public/admin delivery suite
passes after runner/receiver changes. Shell syntax validation, all three Python
acceptance-evidence checks, inventory (588) and `git diff --check` also pass.
Receiver failure responses provide actual fault injection; no production
mutation experiment was performed. Existing failing regressions remain enabled
outside these selections. Restart and concurrent health changes remain gaps.

## Successful deliveries break the consecutive-failure streak

`build/integration-acceptance/20261021T204931Z-3068491/run-{1,2}`: **2 passed each**,
zero failures/errors/skips; 115.15s / 120.49s. Selection: both
`WebhookExhaustionTest` methods in `webhook-failure`. The new case executes
failure/success/failure/success without admin activation or direct health writes.
Each failure makes three actual 503 attempts and becomes FAILED; each success
has one HTTP 200 attempt and becomes DELIVERED. Health transitions 1→0→1→0,
active stays true and disabled_at stays null despite two historical failures.
Four payments produce four transactions/eight postings and balances 9996/10004.
The adjacent consecutive-failure auto-disable/recovery control also passes.
No fixture corrections, test retries or production changes were needed. Real
HTTP failure injection exercises health transitions; no production mutation was
performed. Inventory (588) and `git diff --check` pass. The profile's shorter
thresholds are explicit; concurrent completion ordering remains unverified.

## Live rate-limit override changes and shared issuer budgets

`build/integration-acceptance/20261021T205603Z-3602464/run-{1,2}`: **3 passed each**,
zero failures/errors/skips; 56.80s / 55.57s. Selection: new
`RateLimitOverrideLifecycleTest`, existing base-limit exhaustion and admin
stored-override test. Admin updates change live rotation capacities 5→1→2→5;
exact remaining headers establish that two keys share one issuer budget and
another issuer remains isolated. Invalid zero updates preserve the limit/bucket.
429 responses preserve JTI, report zero remaining and a bounded Retry-After.
Removal resumes the existing base-capacity bucket, characterized explicitly.
Balances remain 10000 each and no ledger transaction occurs.

Initial `20261021T205436Z-3515970/run-1` had two passes and one assertion failure:
the test expected an independent second-key budget (200) but received 429. The
existing test's per-key documentation was stale. `RateLimitInterceptor` explicitly
specifies per-issuer budgets and keys them by owner/route/capacity. The new test
and stale comments were corrected to require shared exhaustion, not to accept a
product defect. No production changes were made. Sensitivity was reasoned about,
not mutation-tested. Inventory (588) and `git diff --check` pass; Redis/multi-pod,
refill and concurrency remain gaps documented in `rest-rate-limits.md`.

## Rate-limit decimal and rejection boundaries

`build/integration-acceptance/20261021T205906Z-3769507/run-{1,2}`: **7 passed each**,
zero failures/errors/skips; 58.99s / 58.70s. Selection: five
`RateLimitBoundaryTest` cases, override lifecycle and base exhaustion. Inputs
0.005/0.10/0.30/9999.994 store 0.01/0.10/0.30/9999.99 and yield live rotation
limits 1/1/2/50000, with exact remaining headers. DB assertions verify note and
admin attribution. Missing/null/negative/out-of-range multipliers, oversized
notes and unauthorized PUT/DELETE preserve a preexisting multiplier 2 and
255-character note; later rotation still has limit 10/remaining 9. Money and
ledger checkpoints stay unchanged. No fixture corrections, test retries or
production changes were needed. Sensitivity was reasoned about, not mutation-
tested. Inventory (588) and `git diff --check` pass. Default in-memory provider
only; timed refill, separate routes, Redis/multi-pod and concurrency remain gaps.

## Paper reload publishes public tax configuration

`build/integration-acceptance/20261021T210230Z-3939656/run-{1,2}`: **2 passed each**,
zero failures/errors/skips; 60.57s / 65.26s. Selection: new
`TaxConfigPublicationTest` and existing `TaxCycleLifecycleTest` in the tax
profile. Real Treasury YAML changes and console reload publish exact ordered
bracket/plugin rates, default rate and enabled flags through unauthenticated
GET `/config/tax`. An unchanged reload preserves the full snapshot/timestamps;
invalid income rate 1.50 leaves it unchanged. Disabling both sections updates
flags while preserving rates. No ledger transaction occurs, and original YAML
is restored/reloaded in finally. No fixture corrections or production changes
were needed. Sensitivity was reasoned about, not mutation-tested.

`build/integration-acceptance/20261021T210448Z-4102221/run-1`: **2 passed**, zero
failures/errors/skips; 59.56s. Confirms the updated default `--profile tax`
selection includes both tests without explicit filters. Shell syntax, inventory
(588) and `git diff --check` pass. New `rest-config.md` records missing-row,
publication-failure, restart and concurrency gaps; publication does not prove
tax collection at these configured rates. Known failing regressions remain
active outside this selection.

## Public tax config isolates missing or malformed published rows

`build/integration-acceptance/20261021T210653Z-7155/run-{1,2}`: **6 passed each**,
zero failures/errors/skips; 55.13s / 59.25s. Default tax profile includes four new
missing/malformed publication cases, the YAML reload journey and Business tax
cycle control. A scoped DB fixture deletes or corrupts either balance brackets
or income plugin-rates. Public GET returns 200 with explicit null only for the
affected section; the other section including its timestamp stays identical.
Real Treasury reload restores expected rates/flags with a nondecreasing update
time and no ledger transaction. Faults are always removed through reload in
finally. No fixture corrections, test retries or production changes were needed.
This uses actual published-data fault injection, not a simulated HTTP result;
no production mutation experiment was performed. Inventory (588) and diff
whitespace checks pass. Unexpected valid-JSON shapes, both-absent startup,
publication write failures and concurrency remain gaps in `rest-config.md`.

## Public market directory reflects Paper shops and trades

`build/integration-acceptance/20261021T211041Z-201144/run-{1,2}`: **2 passed each**,
zero failures/errors/skips; 61.36s / 128.33s. Selection: new `ShopDirectoryTest`
and existing `BusinessShopTest`. Three shops created through Paper's real
creation event are isolated by fresh firm ID. Public HTTP asserts exact shop
IDs/order, populated/empty pages, counts, material/item-key/search and combined
buyable/inStock filters. Detailed rows verify locations, business identity/name,
material/key, flags, prices, quantities and stock. A real packet purchase of two
dirt for 7 changes both chest and public stock 24→22, buyer balance to 9993,
firm to 7, proprietor remains 10000, and one sale is recorded. No fixture
corrections, test retries or production changes were needed. Sensitivity was
reasoned about, not mutation-tested. Second-run startup took longer; the original
runner completed normally. Inventory (588) and diff checks pass. New
`rest-chestshop.md` records exclusion, field and remaining endpoint gaps.

## Public directory follows stock depletion and sign removal

`build/integration-acceptance/20261021T211514Z-528130/run-{1,2}`: **3 passed each**,
zero failures/errors/skips; 65.69s / 56.92s. Selection: both `ShopDirectoryTest`
methods and existing sign-removal lifecycle control. New case starts with an
empty cheap shop and a stocked higher-priced shop. Exact IDs/order/counts prove
availability precedes price and inStock excludes only sold-out rows. One purchase
of 64 dirt for 11 empties the second shop; inStock becomes empty while buyable
retains both in price order. A real sign-break packet deactivates the first shop
and removes it from the public directory. Reads/removal do not alter ledger
checkpoint; final buyer/firm/proprietor balances are 9989/11/10000 and one sale
is recorded. No fixture corrections, test retries or production changes were
needed. Sensitivity was reasoned about, not mutation-tested. Inventory (588) and
`git diff --check` pass; non-buyable/admin/hidden semantics and aggregate routes
remain gaps in `rest-chestshop.md`.

## Public market parameter boundaries and offset safety

`build/integration-acceptance/20261021T211814Z-736587/run-{1,2}`: **5 passed each**,
zero failures/errors/skips; 67.59s / 67.41s. Selection: three
`MarketApiValidationTest` cases and both real `ShopDirectoryTest` journeys.
Shops/items invalid page/limit/search boundaries return 400 INVALID_PARAM; max-int
page at limit 100 rejects overflow while max-int page at limit 1 returns a valid
empty 200 page. Accepted search/limit edges succeed. Shop material/item-key
lengths and detail days/key boundaries distinguish INVALID_PARAM from
ITEM_NOT_FOUND for valid unknown items. No ledger checkpoint changes. No fixture
corrections, test retries or production changes were needed. Sensitivity was
reasoned about, not mutation-tested. Inventory (588) and `git diff --check` pass.
These checks do not establish populated item/detail/stats aggregation; remaining
route, binding and cache gaps are recorded in `rest-chestshop.md`.

## Populated public item detail, directory and market totals

`build/integration-acceptance/20261021T212335Z-1048835/run-{1,2}`: **3 passed each**,
zero failures/errors/skips; 65.17s / 66.22s. Selection: new `MarketAggregateTest`
and both shop-directory journeys. Real purchases of 2 units for 7 and 3 for 11
produce literal expected item/daily totals: 2 trades, 5 units, volume 18, weighted
unit average 3.6. Detail checks identity/window, two active shops, cheapest IDs
and stock 22/33. Item-directory search returns the exact item and totals. Global
stats match persisted sales aggregates. Buyer/firm/proprietor balances are
9982/18/10000, buyer inventory five and customer sale count two.

Initial `20261021T212200Z-943305/run-1`: two directory tests passed; the aggregate
test failed 404 because its path used form encoding (`Raw+Gold+Block`) instead of
percent-encoded spaces. Correcting the test's path encoding to `%20` addresses
the actual mismatch, without changing expectations or production code. Both
fresh runs above then passed. Sensitivity was reasoned about, not mutation-
tested. Inventory (588) and diff checks pass. Reserved-material fixture scope,
DB-derived global oracle and unexercised cache-expiry/window/sell-direction
boundaries are documented in `rest-chestshop.md`.

## Market windows, daily series and lifetime totals

`build/integration-acceptance/20261021T212842Z-1300874/run-{1,2}`: **2 passed each**,
zero failures/errors/skips; 60.63s / 53.73s. Selection: new `MarketWindowTest`
and current-trade aggregate control. Two real trades (2 for 7, 3 for 11) precede
an explicit fixture backdating only the first sale timestamp by 40 days.
Thirty-day detail returns one trade/3 units/11 volume, while 90-day detail returns
two/5/18 and an ordered two-day price series. Lifetime directory retains both.
Re-reading 30 days after 90 days preserves its cached response. Ledger checkpoint,
buyer/firm balances 9982/18 and five purchased items remain unchanged by reads.

Initial `20261021T212707Z-1197837/run-1`: current aggregate control passed; the
window test expected daily 11/3 average at four decimal places but received
3.666667. The summary explicitly rounds to four places; daily SQL division
returns six. Exact independently calculated expectations now characterize each
field separately (3.6667 versus 3.666667), without changing production behavior
or inventing a uniform precision contract. Both fresh runs pass. Inventory (588)
and diff checks pass; exact cutoffs, timezone transitions and original timestamp
ingestion remain outside the historical fixture. No production mutation was run.

## Quiet and historical-only market item detail

`build/integration-acceptance/20261021T213152Z-1459565/run-{1,2}`: **3 passed each**,
zero failures/errors/skips; 55.38s / 54.36s. Selection: extended `MarketWindowTest`,
current aggregate control and unknown-item/window validation control. After both
actual sales are backdated beyond a one-day window, detail returns 200 with zero
trades/quantity/volume/average and empty daily series, retaining live offers.
Real removal of both signs then yields zero active shops and no offers on an
uncached two-day view, still 200 with item identity. An uncached 365-day view
retains both historical trades. Unknown-item control remains 404. No fixture
corrections, test retries or production changes were needed. Ledger checkpoint,
balances and purchased inventory remain unchanged by removal/read operations.
Inventory (588) and diff checks pass. No production mutation was performed;
backdated-sale and separate-window limitations are explicit in `rest-chestshop.md`.

## Customer sell direction in the public market API

`build/integration-acceptance/20261021T213728Z-1660410/run-{1,2}`: **4 passed each**,
zero failures/errors/skips; 57.32s / 55.74s. Selection: `MarketSellDirectionTest`,
`MarketAggregateTest` and both existing `SellDirectionTest` cases. The new real
customer sale moves four raw copper blocks for 13, checks firm/customer/proprietor
balances and inventory, SELL attribution and unit price 3.25. Public directory
stock filtering retains the shop while buyable filtering excludes it; detail and
item-directory totals include the sale, with one active shop and no customer-buy
offers. No fixture corrections, retries or production changes were required.
Inventory (588) and diff checks pass. Sign setup bypasses the creation event;
trade and HTTP paths are real. Existing known defects remain unresolved.

## First trade and market-cache refresh

`build/integration-acceptance/20261021T214025Z-1860756/run-{1,2}`: **2 passed each**,
zero failures/errors/skips; 82.49s / 83.60s. Selection: `MarketFirstTradeTest` and
`MarketSellDirectionTest`. A never-traded live offer yields 200 detail with zero
history and one offer, while the traded-item directory is empty. After a real
purchase of two items for nine, the same warmed URLs refresh to exact totals,
identity and remaining stock. Balances/inventory and unchanged post-trade ledger
checkpoint are asserted. No fixture corrections, retries or production changes
were needed. Inventory (588) and diff checks pass. The refresh bound is 60 seconds;
exact TTL timing and pre-trade material/name metadata remain outside the claim.

## Unlimited admin-shop market visibility

`build/integration-acceptance/20261021T214439Z-2258819/run-{1,2}`: **4 passed each**,
zero failures/errors/skips; 53.80s / 54.25s. Selection: `MarketAdminShopTest`, both
chest-backed `AdminShopTest` cases and `ForceUnlimitedAdminShopTest`. The new case
buys three cut copper for 12 from an empty unlimited admin shop. It verifies
admin/null-owner sale attribution, unit price four, buyer balance 9988, inventory
three and chest zero. The public directory and cheapest offers retain the shop
under stock filters with explicit null stock/space/owner metadata. Detail and
item-directory totals include the sale, and reads preserve the ledger checkpoint.
No fixture corrections, retries or production changes were required. Inventory
(588) and diff checks pass. Direct sign setup and finite admin-shop discovery
limitations are documented in `rest-chestshop.md`.

## Item-directory ranking/pagination and vanilla-classification defect

Initial `build/integration-acceptance/20261021T214734Z-2430032/run-1`: four cases,
three passed and one failed; 54.59s. New pagination coverage stopped at an ordinary
white glazed terracotta row incorrectly reporting itemCustom=true. Source review
identifies the full-name versus sign-width-shortened-name comparison in the market
classifier. The false expectation was retained, moved after independent checks.

Follow-up `build/integration-acceptance/20261021T214935Z-2518020/run-1`: **3 passed,
1 failed**, zero errors/skips; 58.11s. All ranking, populated/empty page boundaries,
page-size cache separation, display-name search, exact aggregates, balances,
inventory, sale count and ledger-checkpoint assertions completed before the final
retained classification assertion failed. This is partial behavior evidence, not
a passing pagination test or clean suite. The three validation controls passed
both runs. No production changes were made. See `known-defects.md` for the enabled
regression and root cause. Inventory (588) and diff checks pass.

## Personal-owner identity and chest-space mapping

`build/integration-acceptance/20261021T215149Z-2625926/run-{1,2}`: **4 passed each**,
zero failures/errors/skips; 58.94s / 54.70s. Selection: `MarketPersonalOwnerTest`
and three existing `ChestShopSalesTrackerE2ETest` cases. Two distinct personal
owners sell the same material to one customer. Sales and both directory/detail
offers retain correct owner UUID/name and PERSONAL type; exact prices, batches,
stocks 22/33 and spaces 1706/1695 are checked. Owner balances 10007/10011, buyer
9982, inventory five, two sales and unchanged post-trade ledger checkpoint are
asserted. No fixture corrections, retries or production changes were required.
Inventory (588) and diff checks pass. Earlier known regressions remain active;
this focused selection is not evidence of a fully passing suite.

## Business-shop rename and released-name reuse

Initial `build/integration-acceptance/20261021T215450Z-2841397/run-1`: five passed,
one fixture failure; 74.41s. The new test attempted a second firm creation by the
same owner during the real 300-second cooldown. Rename and its public-directory
update had passed. The fixture now uses a separate owner for the replacement firm.

Corrected `build/integration-acceptance/20261021T215644Z-3034979/run-{1,2}`:
**6 passed each**, zero failures/errors/skips; 71.55s / 68.13s. Selection:
`MarketFirmRenameTest` plus all five `FirmRenameLifecycleTest` aliases. Public
shop identity survives rename with an updated owner name. Reusing the old name
for another owner's firm does not transfer the shop or redirect its next purchase:
original firm 14, replacement zero, both owners 10000, buyer 9986, inventory four,
chest 20 and two sales. No production changes were made. Inventory (588) and diff
checks pass; cached item-detail naming and archive behavior remain separate gaps.

## Cached offer refresh after rename/name reuse

`build/integration-acceptance/20261021T220000Z-3279787/run-{1,2}`: **2 passed each**,
zero failures/errors/skips; 92.10s / 85.64s. Selection: extended
`MarketFirmRenameTest` and `MarketPersonalOwnerTest`. Before rename, the test warms
item detail with the original owner and one trade. After rename, old-name reuse
and a second purchase, the identical URL must refresh within 60 seconds to the
renamed owner, original shop/firm identity, stock 20 and totals two trades/four
items/14 volume. Repeated polling preserves the post-trade ledger checkpoint.
No fixture corrections, retries or production changes were needed. Exact TTL
expiration is not claimed; immediate invalidation also satisfies the contract.
Inventory (588) and diff checks pass. Existing known regressions remain active.

## Existing business shop after funded disband

Two clean runs: `build/integration-acceptance/20261021T220414Z-3726989/run-1`
(54.85s) and `build/integration-acceptance/20261021T220530Z-3818776/run-1`
(53.36s), **2 passed each**, zero failures/errors/skips. Selection:
`ArchivedBusinessShopTest` and existing `BusinessShopTest`. A positive purchase
funds the firm with seven; real funded disband refunds the owner, archives the
firm and removes active links. A subsequent purchase leaves buyer/owner/account
balances 9993/10007/0, inventory two, chest 22, one sale and unchanged ledger.

The observed rejection uses existing shop-access handling. The native resolver's
lack of an archive guard did not produce a payment defect in this journey.
`chestshop-disband.md` records the source explanation and limits, including public
listing policy and privileged overrides. No fixture corrections, retries or
production changes were needed. Inventory (588) and diff checks pass.

## Post-disband trading under both access settings

Exploratory `build/integration-acceptance/20261021T220725Z-3906245/run-1`:
three passed, zero failures/errors/skips; 55.89s. Both true/false settings rejected
the purchase. Added explicit runtime configuration read-back, then final clean
pair `build/integration-acceptance/20261021T220847Z-3996785/run-{1,2}`:
**3 passed each**, zero failures/errors/skips; 64.04s / 59.97s. Selection:
parameterized `ArchivedBusinessShopTest` plus existing `BusinessShopTest`.

Both access modes retain the exact post-disband money, items, sale count and
ledger checkpoint. Business ownership delegates to account access, so the owner
predicate still rejects trading when the optional access predicate is disabled.
This is not proof of a low-level archive guard; see `chestshop-disband.md`.
Configuration restores after each test. No production changes or test retries
were needed. Inventory (588) and diff checks pass.

## Customer sell rejection after funded disband

`build/integration-acceptance/20261021T221150Z-4190723/run-{1,2}`: **7 passed each**,
zero failures/errors/skips; 60.81s / 64.19s. Selection: four parameterized
`ArchivedBusinessShopTest` cases, two existing `SellDirectionTest` cases and
`MarketSellDirectionTest`. The new sell-side cases first fund the firm with 100
and successfully sell two items for seven. Disband refunds the remaining 93.
The next sale attempt preserves seller balance 10007, owner 9993, account zero,
seller inventory two, chest two, one sale and the post-disband ledger checkpoint.
Both access configurations are read back and restored. No fixture corrections,
retries or production changes were required. This is observable rejection evidence,
not isolation of a low-level archive guard. Inventory (588) and diff checks pass.

## Container stock recovery after disband

`build/integration-acceptance/20261021T221454Z-228466/run-{1,2}`: **3 passed each**,
zero failures/errors/skips; 60.98s / 59.67s. Selection: `DisbandStockRecoveryTest`,
`ContainerRestockTest`, `OutsiderContainerAccessTest`. The new case proves active
firm stock cannot be opened by an outsider, then a former proprietor can withdraw
all 36 items after real disband using container packets without elevation. Closing
refreshes tracked stock to zero under the same shop ID. Balances, zero sales and
the post-disband ledger checkpoint remain unchanged. No fixture corrections,
retries or production changes were required. This does not assert exclusive
former-proprietor access after disband. Inventory (588) and diff checks pass.

## Find visibility, direct trades and HTTP characterization

`build/integration-acceptance/20261021T221759Z-410314/run-{1,2}`: **3 passed each**,
zero failures/errors/skips; 62.49s / 62.59s. Selection: `MarketVisibilityTest`
and both `FindDialogTest` aliases. Hiding a shop retains active state and identity;
a real purchase succeeds without resetting the hidden preference, then showing
restores visibility. HTTP offers remain present with correct stock before/after
trade; this is explicitly characterization of an API not filtered by the `/find`
visibility flag. Neighboring dialogs prove hidden exclusion from find results.
Exact balances 10009/9991, inventory two, stock 22, one sale and read/toggle ledger
invariants are asserted. No fixture corrections, retries or production changes
were required. Inventory (588) and diff checks pass.

## Rate-limit interval refill and route isolation

Initial `build/integration-acceptance/20261021T222112Z-624967/run-1`: one passed,
one expectation failure; 52.03s. The new test expected gradual refill within 12
seconds based on comments, but Retry-After was 59. Both provider builders use
refillIntervally for a full minute, contradicting those gradual-refill comments.
The test now explicitly characterizes interval refill rather than prescribing a
new production policy.

Final `build/integration-acceptance/20261021T222244Z-703827/run-{1,2}`:
**2 passed each**, zero failures/errors/skips; 113.66s / 114.48s. Selection:
extended `RateLimitTest` and `RateLimitOverrideLifecycleTest`. Exhausted rotation
does not block history reads or change stored JWT ID; after Retry-After plus one
second, rotation succeeds with four tokens remaining and a changed JWT ID.
Balance and ledger checkpoint stay unchanged. No production changes were made.
Inventory (588) and diff checks pass. Backend/timing limits and the comment-policy
mismatch are recorded in `rest-rate-limits.md`.

## Feed pagination with newly arriving payments

Initial `build/integration-acceptance/20261021T222732Z-1298106/run-1`: five passed,
one expectation failure; 65.05s. The new assertion expected hasMore=false on a
full final page. `TransactionFeedResponse` explicitly documents hasMore as a
full-page hint, so the assertion was corrected to true; a following empty page
establishes catch-up.

Final `build/integration-acceptance/20261021T222904Z-1425564/run-{1,2}`:
**6 passed each**, zero failures/errors/skips; 70.43s / 71.58s. Full
`TransactionFeedTest` selection. A fourth real payment arrives between page reads,
and a fifth after an empty poll. Saved cursors yield exact new IDs, debit amounts,
memos and page metadata; replay returns all five once in order. Balances are
9985/10015 and reads preserve the ledger checkpoint. No production changes were
made. Inventory (588) and diff checks pass. Settlement-order/concurrency limits
are documented in `rest-transaction-feed.md`.

## Personal-key lifecycle on settled transaction feeds

`build/integration-acceptance/20261021T223226Z-1680375/run-{1,2}`: **7 passed each**,
zero failures/errors/skips; 77.02s / 74.00s. Selection: extended
`AdminKeyLifecycleTest` and full `TransactionFeedTest`. Original and reissued
personal keys read exact settled debit IDs/amounts with correct next cursors.
Force-rotated tokens receive TOKEN_SUPERSEDED, and repeatedly revoked replacement
tokens receive TOKEN_REVOKED, for feed cursor zero and a previously saved cursor.
Shared lifecycle checks preserve balances/checkpoint on rejection and retain
independent-key functionality. No fixture corrections, retries or production
changes were needed. Inventory (588) and diff checks pass. In-flight races and
other role transitions remain outside this case; see `rest-transaction-feed.md`.

## Business feed scope and key invalidation

`build/integration-acceptance/20261021T223544Z-1978228/run-{1,2}`: **3 passed each**,
zero failures/errors/skips; 75.21s / 75.15s. Selection: both extended
`BusinessKeyLifecycleTest` variants and `AdminKeyLifecycleTest`. Whole-firm and
account-scoped business keys read exact settled reserve debits before rotation
and after reissue. Sibling feed access remains 403 for scoped keys and 200 for
whole-firm keys. Superseded/revoked bearers are denied at initial and saved feed
cursors. Existing balance/link/checkpoint invariants remain satisfied. No fixture
corrections, retries or production changes were needed. Inventory (588) and diff
checks pass; staff role changes and in-flight races are not certified here.

## Government membership changes with a stable personal token

Initial `build/integration-acceptance/20261021T223921Z-2289867/run-1`: six passed,
one memo expectation failure; 74.78s. `/paya` prefixes the supplied memo with payer
and government destination. Corrected to the full independently constructed
command-format memo, without weakening authorization or transaction assertions.

Final `build/integration-acceptance/20261021T224114Z-2430210/run-{1,2}`:
**7 passed each**, zero failures/errors/skips; 68.57s / 70.99s. Selection:
`FeedMembershipLifecycleTest` and full `TransactionFeedTest`. An unchanged personal
token is denied as outsider/VIEWER, reads the exact 23.45 government credit as
MEMBER, is denied after removal and regains access after regrant. History and
initial/saved feed cursors are checked. JWT/revocation state, all balances and
post-funding ledger checkpoint remain unchanged. No production changes were made.
Inventory (588) and diff checks pass; group and concurrent-role behavior remains
outside this case.

## Authorizer promotion/demotion on API reads

`build/integration-acceptance/20261021T224426Z-2715363/run-{1,2}`: **5 passed each**,
zero failures/errors/skips; 68.18s / 64.29s. Selection: extended
`FeedMembershipLifecycleTest` and four `GovernmentRoleTransitionTest` cases.
The unchanged personal bearer reads exact government credit history/feed as
AUTHORIZER, retains access after a weaker viewer grant is added/removed, and
retains access when auth removal demotes to MEMBER. Explicit database role
assertions verify those transitions before HTTP assertions. Removing MEMBER
leaves no active role and denies reads, followed by successful regrant. Existing
key/balance/ledger invariants hold. No fixture corrections, retries or production
changes were needed. Inventory (588) and diff checks pass.

## Archived government account feed authorization

`build/integration-acceptance/20261021T224732Z-2972305/run-{1,2}`: **3 passed each**,
zero failures/errors/skips; 65.91s / 65.08s. Selection: extended
`FeedMembershipLifecycleTest` and both `GovernmentArchiveLifecycleTest` aliases.
After actual government archive, persisted MEMBER access remains and the same
personal token reads the exact historical credit via feed/history. An independent
outsider is denied on history and initial/saved feed cursors. Archive state,
unchanged balances/token IDs and ledger checkpoint are asserted. This verifies
the API's documented archived-history contract, distinct from the command policy.
No fixture corrections, retries or production changes were needed. Inventory
(588) and diff checks pass.

## Pause and resume an overdue webhook retry

`build/integration-acceptance/20261021T225101Z-3207076/run-{1,2}`: **2 passed each**,
zero failures/errors/skips; 114.02s / 112.04s. Selection: new
`WebhookRetryTest.pausedOverdueRetryRemainsPendingAndResumesWithSameIdentity` and
`AdminWebhookDeliveryTest`. An actual 503 schedules a retry; admin pause leaves
it pending with one attempt after its due time and a later successful control
delivery. Reactivation delivers the same raw payload, delivery header and signature
with exactly two attempts, HTTP 200 and cleared error. Two payments produce only
two transactions/four postings and balances 9992/10008. No fixture corrections,
retries or production changes were needed. Inventory (588) and diff checks pass.
This covers queued pause/resume, not in-flight cancellation.

## Deleting a queued webhook retry

`build/integration-acceptance/20261021T225553Z-3850710/run-{1,2}`: **2 passed each**,
zero failures/errors/skips; 113.69s / 116.59s. Selection: new
`WebhookRetryTest.deletingSubscriptionRemovesPendingRetryBeforeItBecomesDue` and
`AdminWebhookDeliveryTest`. An actual 503 creates a pending retry. Admin deletion
removes the queued row through cascade; repeat deletion affects zero rows. After
the original due interval, a fresh control receives a later payment while the
deleted subscription receives neither a retry nor the new event. Balances
9992/10008 and exactly two transactions/four postings remain correct. No fixture
corrections, retries or production changes were needed. Inventory (588) and diff
checks pass. This establishes pre-dispatch deletion, not in-flight cancellation.


## Full core audit after lifecycle and retry coverage

`build/integration-acceptance/20261021T230029Z-121106/run-1`: **745 reported cases:
709 passed, 23 failed, 13 skipped**, zero errors; 819.71s. This was one clean,
unfiltered core run with `IT_SKIP_BUILD=1`, using the existing production jars
and recompiling tests. The runner exited 1 as required by the enabled failures;
no retries or production changes were made.

Every failure's assertion output was inspected. All 23 match the 12 documented
defect areas in [known-defects.md](known-defects.md): leaderboard/list overflow,
archived typed payments, numeric/partial firm disband, defunct-list markup,
released-name payment routing, invalid Discord profiles, duplicate account names,
REST partial-disband recovery, former-owner personal keys and ordinary-item custom
classification. No additional failure category appeared in this combined run.
Passing focused selections are therefore supported by a broader interaction run,
but this is neither a green suite nor proof of exhaustive behavioral coverage.

The 13 reported skips comprise four protection cases, three unavailable ingest
cases, one restart case, one tax-cycle case, two tax-publication method entries
and two webhook-exhaustion cases. The latter groups require dedicated profiles;
a skipped parameterized method is one report entry, not its expanded profile
case count. Explorer is a separate browser profile and is not in these totals.
Previously recorded dedicated-profile evidence remains separate from this audit.

The 588-entry source inventory and `git diff --check` pass. The acceptance matrix
now distinguishes historical green runs from the current enabled regressions.


## Balance leaderboard across disconnect and reconnect

`build/integration-acceptance/20261021T231852Z-3808797/run-{1,2}`: **2 passed each**,
zero failures/errors/skips; 86.06s / 75.77s. Selection: extended
`BaltopRankingTest` and `ReconnectPersistenceTest`. The leaderboard journey now
confirms Paper observes rank-one and rank-eleven players offline, checks exact
names/ranks/amounts on both pages, reconnects the leader with the same UUID and
checks the first page again. Existing final assertions preserve every seeded
balance and the ledger checkpoint, including absence of another starting grant.

Two earlier fixture failures are retained: `20261021T231520Z-2934246/run-1`
encountered GriefPrevention's reconnect throttle after the offline assertions;
`20261021T231659Z-3288053/run-1` passed reconnect assertions but vanilla kicked the
viewer for command spam during later permission checks. The returning player now
uses the existing reconnect fixture's `griefprevention.spam` permission, and queries
are paced at 1.1 seconds to recover vanilla's command budget. Command barriers
still determine completion. Neither failure demonstrated a leaderboard defect.
No production changes were made. The 588-entry inventory and diff checks pass.
This covers stored names for previously connected players; unknown-name fallback,
tie ordering and archived/non-personal exclusion remain separate gaps.


## Leaderboard archive lifecycle and non-personal exclusion

Archive extension: `20261021T232250Z-38020/run-{1,2}`: **2 passed each**, zero
failures/errors/skips; 89.50s / 83.68s. Selection: `BaltopRankingTest` and
`AdminAccountArchiveTest`. Real admin HTTP archive removes the leader and promotes
the next ten exact names/balances into ranks 1–10 without draining the archived
account. Unarchive restores the exact original first page. Final seeded balances
and ledger checkpoint remain unchanged.

Combined extension: `20261021T232629Z-274237/run-{1,2}`: **2 passed each**, zero
failures/errors/skips; 88.57s / 106.21s. Selection: `BaltopRankingTest` and
`EconomyOverviewTest`. Real business/government creation and typed payments fund
both non-personal accounts above every seeded personal leader. Exact first-page
assertions exclude these accounts throughout offline, reconnect, archive and
restore transitions; final checks preserve both excluded balances. The fixture
administrator returns to 10000 before ranking begins. This detects an absent
account-type filter without relying on low-value accounts being off-page.

Neither extension required retries or production changes. The 588-entry inventory
and diff checks pass. Unknown-name fallback, ties and additional account types
remain separate coverage; the known extreme-page overflow is still enabled.


## Economy totals across account archival

`20261021T233447Z-1099852/run-{1,2}`: **2 passed each**, zero failures/errors/skips;
71.19s / 71.46s. Extended `EconomyOverviewTest` and `AdminAccountArchiveTest`.
Each funded PERSONAL, BUSINESS and GOVERNMENT account is archived and restored
through admin HTTP. Exact category and overall totals subtract only its balance;
restoration recovers the previous display, stored funds and the ledger checkpoint
remain unchanged. Queries are paced for vanilla command limits and use barriers.

An earlier run (`20261021T233055Z-526304`) hit an unrelated REST listener on 8180:
the new API failed to bind, and both tests received INVALID_TOKEN from that other
service. This is environment evidence, not an archive defect. Further startup
attempts at `20261021T233321Z-781105` and `20261021T233437Z-1071541` encountered DB
port conflicts and ran zero tests. The successful runs use game/RCON 21565/21575,
DB 19306, REST 4180, webhook 4199 and Bytebin 4198. Runner preflight now rejects
occupied or duplicate listener ports before stack startup; a controlled occupied
socket verified the rejection. It leaves explicitly external database ports alone.
The 588-entry inventory, shell syntax, three evidence-runner tests and diff checks
pass. No product behavior was changed for this coverage.


## Repair and verify defects exposed by the new coverage

After coverage commit `33c759d`, current develop (`303135e`) was merged at `f4885f2`.
Production fixes now address all twelve defect areas documented in known-defects.md.
`./gradlew build --parallel --continue` passes, including DB-backed tests and
coverage gates. An earlier `-PskipIT` build exposed formatting, incomplete coverage
and an overly broad draft ownership filter; those are corrected, with ownership
revocation scoped transactionally to the admin owner-change operation.

`20261021T234424Z-1362229/run-{1,2}`: **47 passed each**, zero failures/errors/skips;
123.11s / 126.12s. Production jars were rebuilt (IT_SKIP_BUILD unset). Selection:
all original defect classes, three boundary values for each overflow test,
DisbandAuthorizationTest and NexoCustomItemTest. This includes real old-key denial,
replacement-firm payment routing, proprietor and REST partial-disband recovery,
all five list aliases, invalid Discord rejection, duplicate-name rejection and
ordinary-item classification. Nexo remains a stub control, not licensed acceptance.
No live retries or weakened assertions were used. Full updated-core runs remain
pending. Source inventory now includes 589 entries after upstream group changes.

### Develop reconciliation (2026-10-22)

Develop advanced through `662724d` during verification. The full core attempt
`20261021T234955Z-1876880` was deliberately stopped after 175 passes to reconcile
the new Curator finder; the subsequent connection failures were shutdown fallout,
not an accepted full run or newly classified product defects. Finder acceptance
now uses actual inventory click packets and server observations, with 47 fixtures
to cross the new 45-result page boundary.

Three upstream PRs independently added V38 migrations. The owner confirmed that
only the non-production clone had been deployed and authorized repair if needed.
Read-only inspection of its Flyway history found successful V38 taxation index
(`-99881344`), with no employment/global-namespace migration. Taxation therefore
retains V38; byte-identical employment/global-namespace SQL becomes V39/V40.
No clone database mutation was needed. The guard pins the exact invalid combined
migration tree and exact renamed blobs; all 17 guard tests pass, including rejection
of altered SQL, deletion and a different base. Upgrade rehearsal of the invalid
combined tree starts from its valid V37 prefix; ordinary bases retain full history.

Reconciliation discovery run `20261022T000123Z-2495059/run-1`: 21 cases,
16 passed, 5 failed, zero skipped/errors. Two failures were the new generated
corporate-account name collision after release; two preview fixtures assumed
right-click preview despite the new default compass action; one owner fixture
attempted to move a reserved player name to a different identity. The latter
fixtures now explicitly configure preview or rename the account through the
admin API before ownership transfer, and retain a rejection assertion for the
reserved player name. Typed payment fixtures now use distinct names, as required
by PAR-501, while retaining exact selected/unselected balance and posting checks.

The generated corporate-account collision is repaired with one bounded attempt
using the new firm ID suffix; original financial identities retain their names.
Only a recognized database name-conflict signal allows that fallback. Both
migration upgrade rehearsals passed Flyway validation and money invariants:
`origin/develop` (pinned invalid tree's V37 prefix) and `64282ac` (the clone's
actual taxation-only V38 baseline). Full reconciled verification remains pending.

Local verification sequencing incident: the concurrent root build and release
assembly truncated the staged TreasuryAPI JAR. Three startup attempts were rejected
by capability preflight, with zero gameplay cases; neither artifact reuse nor an
ordinary up-to-date release task replaced that stale output. Forcing the release
Sync task (`release --rerun`) restored it; all four archives then passed CRC checks.
The runner now rejects incomplete archives before service startup. A controlled
truncated-archive check verified the actual guard's failure and rebuild guidance.
The root build also recorded one embedded MariaDB startup error (`business_test`
not found); the isolated repeat completed all required build/coverage checks.
CI's independent JVM build passed on `a5d0abc`.

CodeQL identified a polynomial trailing-digit regex in the new test-only dialog
helper. It was replaced by a linear scan bounded to a signed-long digit count;
server dialog interaction reruns and the next code-scanning run verify the change.

Scheduled groups: `20261022T001429Z-2980005/run-{1,2}` passed 1 case each, zero
failures/errors/skips, in 63.71s and 68.01s. This is real scheduled Business API
publication, including offline role changes, archival and manual-source isolation.
The preceding new-test attempt failed during fixture generation because its name
prefix exceeded the helper's five-character limit; that fixture error was corrected.

Controlled group fault: disabling only `reconciliation.enabled` in the disposable
server's source configuration caused the real test to time out after 15 seconds
waiting for its first proprietor membership (78 observations, membership empty).
The fault wrapper restored the original config in `finally`. Final consecutive
clean runs are required after restoration; this intentional failure is not retried
or counted as a passing acceptance result.

Reconciled combined run on `e3148b73`: 51 cases, 47 passes and four ownership
failures, zero skips/errors, in 111.8s. Finder menus, all five firm-release aliases,
retained-fund recovery, typed payments, Discord validation and scheduled groups
passed. The ownership failures exposed a further PAR-501 defect: even a renamed
personal account's sole reservation could not follow its new owner because the
procedure attempted to claim it before moving its own previous claim.

Additive V41 fixes only that sole-claim transition under a reservation-row lock;
shared player/account names remain rejected. Existing V40 SQL is unchanged.
`GlobalNameNamespaceIT` passes with both sole-claim transfer and shared-name denial.
The first V41 draft used a procedure syntax the Flyway parser split incorrectly;
that setup attempt was stopped and corrected to its supported DROP/CREATE form.
No clone database was modified. Full build and live reruns are pending.

Full CI discovery on `a5d0abc` completed 755 cases: 698 passed, 45 failed and 12
were skipped. All failures were inspected: 40 concerned globally duplicated fixture
account names after PAR-501, four used the overlong ownership fixture prefixes,
and one reused a service-key test's renamed account label. Names are now scoped to
the fresh firm/player; no balance, posting, access or recovery assertion was removed.
The independent CI JVM, migration, mutation, API-contract and Explorer checks passed.
CodeQL's single test-helper regex finding is corrected locally for the next push.

The V41 focused run passed 50 of 51 cases. The remaining fixture assumed a newly
created personal account already displayed the player name; it sometimes still
held its UUID label. The test now explicitly renames through the admin API and
waits for both player/account claims before asserting shared-name rejection. It
also checks that returning ownership does not revive the old personal key, then
uses a newly issued personal key to test the admin-role denial separately.
