# PAR-499 live acceptance matrix

Tracking: [PAR-499](https://app.taskl.ie/w/paradaux/issues/PAR-499).
Baseline checkout: `b7dfa4a` (develop merge #68, including PAR-448). This is an **in-progress review**,
not a claim of complete acceptance.
Current status: the twelve defect areas recorded in earlier rows below are now
fixed and passed two focused rebuilt runs (47 cases each); see `known-defects.md`
and `results.md`. Reconciled core and all six ordinary dedicated profiles passed
twice on `a1e875d1`; the opt-in real-Nexo addition is recorded separately. Historical failure
labels below refer to the discovery evidence. The source index contains every syntactically
found `@Route`, permission/configuration annotation, HTTP mapping, event handler and repeating-task registration in the
five in-repository runtime modules. `python3 integration-tests/acceptance/inventory.py
--check` detects index drift. Entries remain unreviewed until their assertions are
inspected and executed; annotation presence does not prove registration.

## Reviewed additions

| Behavior / source | Executable evidence | Fault it must detect | Execution |
|---|---|---|---|
| Treasury `PayAccountCommand`: player/government/business/numeric targets, aliases and greedy memo | `PayAccountCommandTest.typedPaymentSelectsRequestedAccount` | Wrong typed destination; missing/duplicate debit or credit; dropped memo; unauthorized source | Passed in both clean core runs |
| Typed payment to archived government by name or numeric ID | `ArchivedTypedPaymentTest` | Archived destination must not receive funds | Fixed after reproducing both bypasses; enabled regressions verify rejection without money/posting changes |
| Typed payment invalid amount, self-target, missing target/type, insufficient funds | `PayAccountCommandTest.rejectionDoesNotWriteMoneyOrPostings` | Rejection with balance mutation or extra postings | Passed in both clean core runs |
| `treasury.pay` permission | `PayAccountCommandTest.explicitPermissionDenialLeavesBothAccountsUnchanged` | Permission gate bypass | Passed in both clean core runs |
| Two asynchronous payments sharing insufficient source funds | `PayAccountCommandTest.competingPaymentsCannotSpendTheSameFundsTwice` | Double spending; partial settlement | Passed; request contention, not forced DB interleaving |
| Join → pay → disconnect → reconnect | `ReconnectPersistenceTest` | Duplicate starting grant, account replacement, lost payment or stale displayed balance | Passed; graceful Paper restart also covered by the dedicated restart profile |
| ChestShop visibility/hologram commands, owner vs outsider | `ShopLifecycleCommandsTest` | Wrong owner authorization, lost preferences, duplicate registry row, changed trade terms | Passed; no claim about rendered holograms |
| Real sign-dig packet → registry deactivation | `ShopLifecycleCommandsTest` | Listener not wired; stale market entry after sign removal | Passed in both clean core runs |
| Required capabilities before selected acceptance tests | `:tests:preflight` in `ops/integration/run.sh` | Disabled/missing plugin, unsupported server version, broken login/seeding, unavailable or unprotected REST rotation | Passed in both clean core runs |
| Real sign placement/editor submission → trade → inventory/money/sales row | `PlayerShopPlacementTest` | Missing packet/event wiring; wrong stock, payment or registry identity | Passed |
| PAR-448 account-scoped business key issue, spend/history isolation and REST rotation | `BusinessAccountScopeTest` | Sibling-account authority leak or lost restriction after rotation | Passed in both clean core runs |

Detailed execution and fault evidence: [results.md](results.md).
Original defect observations and repairs: [known-defects.md](known-defects.md).

## Existing suite review queue and explicit gaps

These mappings identify existing evidence to review, **not passing acceptance**.
The machine-readable index is deliberately conservative rather than matching test
names and automatically declaring a feature covered.

| Area | Existing evidence | Remaining acceptance work / limitation |
|---|---|---|
| Treasury balance/pay/history/admin/fines/government | `treasury/*Command*Test`, `TreasuryAdminTest`, `AmountAttacksTest`, `GovMemberTransferTest` | Review each indexed route, aliases and authorization changes; several older rejection tests assert chat or only one balance |
| Treasury history/audit/export | `TransactionHistoryPaginationTest`, `TransactionAuditTest`, `TransactionExportTest`: exact rows/pages, durable audit records, role distinction and actual gzip CSV upload/recovery | See the nine-route review; group/non-default/archived export roles, populated audit pagination, wider CSV metadata/size/network boundaries (direct government and business role transitions now passed twice) |
| Balance leaderboard | `BaltopRankingTest`: twelve real players, exact ranks/names/amounts across pages, zero/negative clamp, help and revoked permission on every route | Fixed integer-overflow offsets; `BaltopOverflowProbeTest` protects maximum and wraparound boundaries. Confirmed offline names on both pages, leader reconnect, archive/unarchive ranking and high-value business/government exclusion passed twice; unknown-name fallback, additional account types and tie behavior remain |
| Funded government archive/list lifecycle | `GovernmentArchiveLifecycleTest`: both aliases, exact list IDs/names/balances/order, archived row hidden, reads/transfers/payout/pay rejected, original balance/posting preserved and name reuse rejected | Passed twice. The typed-payment bypass is fixed and covered by enabled regressions; archive permission revocation, primitive-state assertions and reactivation remain |
| Government balance/history and viewer access | `GovernmentHistoryAccessTest`, `GovernmentGroupViewerTest`: both aliases, exact 12-credit history and balance, zero/extreme pages, direct/group viewer cannot spend, live LP/account grant revocation and exact lists | Viewer journeys and individual role/permission transitions passed twice; see `government-reads.md`. Group demotion, archived/missing accounts and offline-name branches remain |
| Personal sales reports | `PersonalSalesCommandTest`: all five routes, known buy/sell trades, owner isolation, exact quantities/totals and revoked permission | Multi-page sales, window boundaries, taxed trades and top-item ranking |
| Economy summary | `EconomyOverviewTest`: category deltas from three actual transfer types, conserved total supply, help and revoked permission | Funded personal/business/government archive/unarchive category deltas passed twice; SYSTEM exclusion, custom formatting and console sender remain |
| Starting balance/login | `FirstJoinSeedsBalanceTest`, `treasury/AdditionalCoverageTest` | Disabled starting grant/configuration branches; crash recovery (graceful restart persistence is covered) |
| Tax cycles/exemptions | `lifecycle/TaxCycleLifecycleTest`: exact manual collection, exemption/config reload, accelerated real Bukkit task execution, same-period no-op and next-period progression; existing tax tests | Natural calendar boundaries/DST/catch-up, multi-account rounding boundaries and remaining config matrix |
| Business aggregate leaderboard | `FirmBalanceRankingTest`: multi-account totals/order, lower-page clamping, far-page empty, archived-firm exclusion and revoked permission through all five aliases | Passed twice. `FirmBalanceOverflowTest` protects the repaired maximum-page negative-index error; populated second page, ties and zero/negative totals remain |
| Funded firm disband | `FundedDisbandTest`: all five roots, confirmation gating, exact two-account sweep and proprietor refund, archived accounts/default pointer/soft-unlinked history, repeat-confirm no-op | Passed twice; see `business-firm-lifecycle.md`. `DisbandAuthorizationTest` also passed all five aliases for caller/firm isolation, permission revocation and proprietor changes with exact refund recipient. Real-time expiry/re-prompt also passed twice in `DisbandExpiryTest`. Partial drain rollback is verified, and the discovered proprietor-resume/numeric-reference authorization defects are fixed; admin recovery passed both fault positions twice with exact rollback and no duplicate payouts. Reconnect and concurrency remain |
| Business firm/account/staff/roles/requests | `business/*Commands*Test`, `AccountMembershipTest`, `CrossFirmIsolationTest`, `FirmAuthorizationAttacksTest`, `FirmTransferTheftTest` | Route-by-route assertion review; ownership/access revocation across plugins; payroll and archived-state journeys |
| Business information and employment lists | `FirmReadCommandsTest`: five aliases, exact metadata/aggregate balance, numeric info, archived state, real hire/fire list transitions, owner/member/other/global reads and permission revocation. `FirmListPaginationTest`: two exact full pages, disconnected proprietor names, clamping and revoked page routes | Information verified; list state/access checks and all five repaired defunct-markup regressions pass. Missing player-record fallback and click targets remain |
| Business chat | `business/MiscCommandsTest` and command index | CarbonChat is absent from disposable stack: cannot certify real chat integration |
| Funded firm rename | `FirmRenameLifecycleTest`: five aliases, numeric target, no-op/case-only rename, exact stored casing, case-insensitive collision, invalid name and permission grant/revocation; identity, linked accounts, metadata and funds preserved | Archived rename targets, concurrent collisions and downstream display labels remain |
| Disbanded firm name release/reuse | `FirmNameReleaseTest`: active release denied, archived name reserved, permission revocation, release/repeat, new identity/account on reuse, name-vs-ID info routing and new payment preserving old history through five aliases | The wrong-destination payment after partial-drain name reuse is fixed and regression-tested; numeric old-firm recovery is tested separately. Console/missing IDs and concurrent release/create remain |
| Firm attribute setters and admin override | `FirmAttributeAuthorizationTest`: five aliases, numeric targets, ordinary outsider denied, explicit admin grant/revoke, proprietor node revocation, exact metadata after each denial; seven invalid URLs on both routes and valid length boundaries. `FirmAttributeRoleTransitionTest`: connected Manager → Co-Proprietor → Manager, both fields denied/allowed/denied with server nodes unchanged | Realty plot validation, archived targets and link click events remain; direct ADMIN-role edits are covered below |
| Personal/business/admin shop trades | `chestshop/PersonalShopTest`, `BusinessShopTest`, `AdminShopTest`, `SellDirectionTest`, `BothDirectionShopTest` | Existing stock/ledger evidence; fixture places sign NBT via RCON, so this does **not** cover real sign placement/editing |
| Shop rejections/stock/capacity/concurrency | `FailureModesTest`, `SellRejectionsTest`, `StockAndPriceEdgeTest`, `ConcurrencyTest`, `MultiBuyerTest` | Review rollback and exact inventory conservation; packet delivery barriers required |
| Shop creation/protection | `PlayerShopPlacementTest`: real placement; `PacketShopProtectionTest`: denied sign/chest digging and exact next purchase; existing `ShopCreationTest`, `ShopProtectionTest`, `ProtectionIntegrationCreationTest` | Existing event-level checks supplement real packet journeys; business role/administrative bypass digging and wider creation branches remain. The four existing protection cases passed twice with `--profile protection` (sets `chestshop.integrations=true` and both startup hooks) |
| Shop finder/preview/registry | `PreviewPreferenceCommandTest`: four roots, reconnect/isolation and finder permission revocation; `FindHeldItemTest`: selected slot/empty hand/unknown code; `FindDialogTest`, `FindPaginationTest`: actual results, visibility, prices, inspection/back, pages, saved filters/sort; `LegacyShopImportTest` and `PreferenceImportTest`: real file imports/recovery; `NotificationCommandTest` | Broader filter/custom-item matrix, preview rendering, background discovery, chunk lifecycle and WorldEdit cleanup |
| Shop metrics | `MetricsCommandTest`: exact live buy/sell counters and revoked permission through both aliases | 30-minute rollover and account count |
| Container stock and ownership | `ContainerRestockTest`: actual quick-move/close updates registry 36→41→0; `OutsiderContainerAccessTest`: owner window vs outsider read-only response and zero windows | Business roles, bypass/config branches, other click modes and disconnect while carrying items |
| Hopper automation | `HopperMinecartProtectionTest`: actual rail/cart, protected stock retained, same cart drains all stock after sign removal | Block-hopper placement, disabled protection, stock-counter automation branches |
| Custom items | Core uses a labeled Nexo stub; real `nexo` profile verifies native purchase/sale, base-material rejection, failed-payment inventory restoration and paired postings | Two clean Paper 26.3 runs passed. Metadata/dye variants, rendering, reloads and Utilities/BreweryX/VaultStorage/Bolt combinations remain under [PAR-502](https://app.taskl.ie/w/paradaux/issues/PAR-502) / [PAR-496](https://app.taskl.ie/w/paradaux/issues/PAR-496); see [evidence](par-502.md) |
| REST tokens/transfers/auth/feed/webhooks/admin | `restapi/*Test` | Audit every indexed endpoint, scopes/expiry, both transfer engines, retries/idempotency, and cross-service read-after-write |
| TreasuryAPI command | `restapi/TreasuryApiCommandTest` | Route/permission review against current source |
| Ingest | `ingest/EssentialsXIngestTest` | **Disabled:** standalone TreasuryIngest plugin absent; DcDiag ingest stub is not real migration acceptance |
| Vault/Realty | Real supplied `realty` profile: registration, buy permission denial, debit failure/recovery, exact Treasury settlement, WG ownership and scheduled title-holder publication; real Vault withdrawal/deposit | Passed twice on Java 25/Paper 26.3 after fixing the Vault rollback/cache defect. Contract/lease/provider combinations remain broader than these assertions; see [evidence](par-502.md) |
| Explorer | Same-stack Chromium: payment privacy, firm account/activity, market prices/volume, fine totals, scheduled group grants and same-session revocation/expiry | Three cases passed twice. The separate `explorer-keycloak` profile also passed real login and same-session proprietor transfer/revocation twice. See [evidence](par-502.md) |
| Lifecycle/dependency failures | `restart` and `crash` verify committed ledger/world/shop state and the next trade; `rest-restart` verifies persisted token revocation, idempotency and new settlement | Each passed twice. Crash evidence starts from an explicitly saved checkpoint. In-flight atomicity, DB outage/recovery, downtime schedules and remaining reload matrix stay open; see [evidence](par-502.md) |
| Java/Bedrock/runtime parity | Mock client 26.3/protocol 777, Paper 26.3-206 and Java 25: core passed twice with 747 passes and 19 explicit skips | Reuses [PR #80](https://github.com/ParadauxIO/hibernia-economy/pull/80). This is disposable-runtime evidence, not a staging deployment or Bedrock certification. Exact source hashes/durations: [PAR-502 report](par-502.md) |

Unannotated configuration fields, role combinations, registration wiring, external submodules,
and Explorer routes are not exhaustively indexed by the Java annotation scanner.
They need manual review alongside `entry-points.json`. All unresolved rows remain under PAR-499 and child [PAR-502](https://app.taskl.ie/w/paradaux/issues/PAR-502).

## Acceptance commands and evidence

Use `bash ops/integration/run.sh` (the checkout does not mark it executable).
For a focused run:

```sh
bash ops/integration/run.sh -- --tests '*PayAccountCommandTest' --tests '*ReconnectPersistenceTest' --tests '*ShopLifecycleCommandsTest'
```

The dedicated protection profile runs the four previously gated WorldGuard/GriefPrevention
creation cases: `bash ops/integration/run.sh --profile protection`.

Repeatable command: `python3 ops/integration/acceptance.py` (two clean core runs),
and `python3 ops/integration/acceptance.py --profile protection` for the startup-hook profile.
Reports are retained with JWTs and configured secrets redacted.

The historical discovery runs included enabled failures, all retained in
[known-defects.md](known-defects.md). Current verification, exact revisions, counts,
durations and controlled-fault evidence are in [results.md](results.md).
The four protection-profile cases also passed twice. The remaining cross-plugin/browser journeys,
source-to-assertion review, and unavailable integrations remain part of acceptance.
JUnit XML/HTML and timings are under `integration-tests/tests/build/`; server/API/DB
logs are under `build/integration/`. Preserve these before the next clean run.

The [ChestShop command review](chestshop-commands.md) accounts for each of its 29
annotated routes, distinguishing direct behavioral assertions from untested aliases
and missing command branches. Item grants and live admin permission revocation now
have exact inventory assertions; bypass opt-out/restoration checks registry authorization.

The [Treasury transaction-command review](treasury-transactions.md) audits all
nine history/audit/export routes. It records exact live history/audit/CSV assertions, durable access logs and the
local HTTP boundary separately from remaining account-role and metadata gaps.

`FirmRolePermissionPropagationTest` checks direct ADMIN add/remove on the existing
Employee role through five aliases: exact metadata allow/deny behavior, unchanged
role assignment, and owner/employee member-authorizer identities on both default
and Reserve accounts. Multi-holder and FINANCIAL-only role edits remain open.

The [REST firm route review](rest-firms.md) distinguishes exact profile/patch and
public-profile, account-list/rename and live employee/role-list assertions from
remaining route branches and role-flag semantics. Three invalid-Discord regressions and a duplicate-account-name regression
expose REST validation gaps; passing patch semantics do not certify all firm HTTP behavior.

`FirmBalanceLifecycleTest` checks exact REST aggregate totals and account-list/rename
filtering through real archive and disband commands, with retained funds explicitly
accounted for. This complements the SQL-only link-removal scenarios.

`AdminDisbandAtomicityTest` verifies REST admin disband rollback at either funded
account, exact proprietor payouts including an empty account, and retry idempotency.
The REST atomic transaction is distinct from Paper's partial-progress recovery.

`AdminDisbandMissingProprietorTest` covers empty disband without minting an account,
funded 422 rollback when no destination exists, and exact retry payout after the
proprietor joins through the normal starting-balance flow.

`PaperDisbandRecoveryTest` verifies completed-Paper REST retry as a no-op, but
retains a failing regression for partial Paper disband: REST returns success
without draining the still-linked funded account.

`ConcurrentAdminDisbandTest` checks eight barrier-released REST calls: every response
succeeds, receipts report each refund once, and exact ledger totals/records and firm
links converge. It does not force a particular database interleaving.

The [REST account administration review](rest-admin-accounts.md) records funded
personal archive/unarchive behavior, restored transfer capability, and remaining
owner-change/type-specific lifecycle gaps.

`PersonalOwnershipKeyTest` verifies new-owner account/key usability without a second
starting grant, but retains two regressions showing that the old PERSONAL key can
still spend and read the reassigned account.

Owner identifier resolution: `AccountOwnerResolutionTest` adds real REST UUID reassignment and name restoration, same-owner idempotence, duplicate-personal conflicts by name/UUID, invalid/unknown identifiers and auth denials. Each rejection preserves both persisted owners, account types/archive flags, balances and ledger checkpoint. See `rest-admin-accounts.md` and `results.md`; the formerly discovered credential-revocation defect is fixed and separately regression-tested.

Admin API-key lifecycle: `AdminKeyLifecycleTest` exercises force-rotation,
in-game reissue and repeated revocation through real transfer/history requests.
Superseded/revoked tokens cannot move money or read history; rejection preserves
balances and checkpoint. Reissue restores the same key/account, while an
independent key remains usable throughout. See `rest-admin-keys.md` for the two
routes, precise assertions and remaining scope/concurrency/ownership gaps.

Business key lifecycle: `BusinessKeyLifecycleTest` adds firm-wide and
account-scoped admin rotation/revocation plus Paper reissue. It checks preserved
key/firm/owner/account bindings, real spend/history rejection for invalidated
credentials, sibling denial after scoped reissue, exact successful receipts and
balances, and unchanged ledger/default/link state on rejection. See
`rest-admin-keys.md` for remaining ownership/concurrency gaps.

SERVICE key lifecycle: `ServiceKeyLifecycleTest` checks `/auth/rotate` retains
admin write/transfer scope, and admin self-force-rotation/self-revocation blocks
subsequent rename/transfer/rotation with unchanged persisted name, balances and
checkpoint. An independent SERVICE key still performs writes and a return
payment with correct ledger attribution. See `rest-admin-keys.md`; in-flight and
concurrent invalidation remain unverified.

Admin webhook delivery: `AdminWebhookDeliveryTest` links all five admin webhook
operations to real delivered events: owner-scoped rejection, changed URL/path and
HMAC secret, pause/resume and delete. `WebhookReceiver.Delivery.path` makes the
actual destination observable. Controls establish dispatcher progress for
bounded non-delivery checks. See `rest-admin-webhooks.md` for retry, queued-event,
concurrency and firm-scope gaps.

Webhook retry: `WebhookRetryTest` injects one real HTTP 503 per delivery ID,
observes persisted retry state and later successful delivery with identical
payload/identity/signature. Exact balances and one transaction/two postings
ensure redelivery does not repeat the payment. The receiver fault rule is
path-scoped and removed on close. See `rest-admin-webhooks.md` for exhaustion,
auto-disable and restart gaps.

Queued webhook configuration: a second `WebhookRetryTest` case changes URL and
secret after an observed first failure and before the retry is due. It checks
new destination/signature with identical delivery identity/payload and no extra
ledger transaction. This characterizes current-configuration lookup; in-flight
mutation and atomic multi-field updates are not covered.

Webhook exhaustion profile: `--profile webhook-failure` runs a gated live test
with three attempts, two exhausted deliveries and 1/2-second retry timing.
Repeated actual 503 responses produce terminal failures, auto-disable and then
admin reactivation/successful health reset, with conserved ledger effects.
See `rest-admin-webhooks.md`; default numeric thresholds and restart/concurrent
health transitions remain separate coverage requirements.

Webhook health reset: the failure profile now checks failure/success/failure/
success without administrative health changes. Both historical failures remain,
but intervening delivery resets the counter and prevents automatic disabling.
Exact response counts, persisted delivery/health states and ledger conservation
are asserted; see `rest-admin-webhooks.md`.

Live rate-limit overrides: `RateLimitOverrideLifecycleTest` checks immediate
capacity changes and removal through real rotations, shared exhaustion across
two keys owned by one issuer, isolation from another owner, rejected-update
preservation and unchanged JTI on 429. Prior per-key documentation in
`RateLimitTest` was stale and is corrected to the current per-issuer contract.
See `rest-rate-limits.md` for provider, refill and concurrency gaps.

Rate-limit boundaries: `RateLimitBoundaryTest` checks lower/upper decimal-storage
edges, half-up effective capacity and the minimum-one clamp through real HTTP
headers and DB rows. Invalid bodies/bounds/oversized notes and unauthorized
writes preserve the override and attribution; the live limit remains unchanged.
See `rest-rate-limits.md` for exact values and remaining provider/timing gaps.

Public tax config: `TaxConfigPublicationTest` (default tax profile selection)
checks real Treasury YAML reload → published snapshot → unauthenticated HTTP.
Exact sorted rates/flags, unchanged-reload timestamps, invalid-rate snapshot
preservation and disabled-section publication are asserted. Original config is
restored. See `rest-config.md` for missing-row, restart, failure and concurrency
gaps; this publication test does not establish tax collection semantics.

Public config degraded-data handling: tax-profile parameter cases inject missing
or malformed balance/income JSON rows. Each checks explicit null only for the
affected section, intact other-section values/timestamp, real reload recovery,
restored rates/flags and unchanged ledger checkpoint. See `rest-config.md` for
fixture scope and remaining structural/concurrency/publication-failure gaps.

Public market directory: `ShopDirectoryTest` creates three fresh-firm BUSINESS
shops through Paper's creation event, checks exact HTTP IDs/order/page metadata
and material/item-key/search/buyable/inStock filters, then verifies a real
purchase changes public stock consistently with chest inventory and balances.
See `rest-chestshop.md` for field, exclusion and remaining endpoint gaps.

Directory exclusions: sold-out shops are retained normally but excluded by
inStock; availability sorts ahead of price. A full-stock real purchase changes
these views, and a real sign break removes the inactive row from public views.
`ShopDirectoryTest` checks IDs/counts and ledger/inventory effects; non-buyable,
admin and hidden-shop behavior remains unverified.

Market API boundaries: `MarketApiValidationTest` checks shops/items pagination,
search limits, representable versus overflowing extreme offsets, shop filter
lengths and detail-window/key limits with exact domain errors. Accepted unknown
item boundaries return ITEM_NOT_FOUND, not server errors. See `rest-chestshop.md`;
populated aggregate endpoints remain unverified by these validation cases.

Populated market aggregates: `MarketAggregateTest` checks real trades against
literal item/daily totals and weighted average, active-shop count and cheapest
shop order/stock, plus global counts/volume against persisted sales. It uses a
reserved material and checks inventory/balances. Item keys containing spaces
must be path-encoded with percent escapes. See `rest-chestshop.md` for window,
sell-direction, cache and multi-day gaps.

Historical market windows: `MarketWindowTest` backdates one actual sale by 40
days, then checks distinct 30/90-day totals, ordered daily series and unchanged
lifetime totals. Re-reading the shorter window verifies cache-key separation.
The timestamp fixture is explicit; cutoff/timezone boundaries and original
ingestion timing are not certified. See `rest-chestshop.md`.

Quiet/historical item detail: `MarketWindowTest` now distinguishes no activity
in a requested window from an unknown item, before and after real removal of all
shops. It asserts zero aggregates/average, empty daily series, live-shop removal,
retained identity and historical totals. Explicit backdated sales and uncached
window choices limit the claim; see `rest-chestshop.md`.

Customer sell direction: `MarketSellDirectionTest` ties real BUSINESS-shop payout,
stock movement and SELL attribution to public item aggregates and shop filters.
A stocked sell-only offer remains visible with inStock but is excluded by buyable
and cheapest customer-buy offers. See `rest-chestshop.md` for fixture limitations.

First-trade/cache lifecycle: `MarketFirstTradeTest` checks never-traded live item
detail, absence from the traded-item directory, and convergence of the same warmed
URLs after a real purchase. It checks exact aggregate, offer-stock and money/item
outcomes; see `rest-chestshop.md` for timing and metadata limitations.

Unlimited admin-shop market visibility: `MarketAdminShopTest` verifies a real
empty-chest purchase, admin attribution, public null stock/space metadata,
in-stock/cheapest offer inclusion and exact item aggregates. Unlimited mode is
explicit and restored afterward; finite admin-shop discovery remains separate.

Item-directory populated pagination: `MarketItemPaginationTest` exercises four
real trades over three materials, count/name ranking, two page sizes, empty page,
display-name search, cache separation and exact totals/balances/inventory. These
checks completed on the live follow-up run, but the test remains **failing** at its
final ordinary-vanilla classification assertion. See `known-defects.md` and
`results.md`; do not treat this as full passing acceptance.

Personal shop owner mapping: `MarketPersonalOwnerTest` checks two owners of shops
for the same material, correct UUID/name/type mapping in directory and detail,
per-owner payouts, exact stock and ordinary-chest remaining space. See
`rest-chestshop.md`; rename/reassignment behavior remains outside this case.

Business-shop rename/name reuse: `MarketFirmRenameTest` verifies immediate public
directory name updates with stable shop/firm/account identity and correct real
payments after another owner recreates the released name. See `rest-chestshop.md`
for cached-detail, legacy-sign and archive limitations.

Cached rename visibility: `MarketFirmRenameTest` now warms item detail before
rename and checks the identical URL refreshes its owner label, stock and totals
after name reuse and another purchase. Stable shop/firm identity is asserted;
see `rest-chestshop.md` for the bounded eventual-refresh claim.

Post-disband shop trading: `ArchivedBusinessShopTest` proves a formerly working
account-bound business shop cannot accept another ordinary customer purchase after
funded disband, with exact money/items/sale-count/ledger invariants. The observed
rejection is shop-access handling; see `chestshop-disband.md` for limits.

Post-disband access configuration: `ArchivedBusinessShopTest` now checks both
ignoreAccessPerms modes with runtime read-back and restoration. Both reject the
purchase through existing business-owner/access semantics; lower-level archival
protection is not inferred from this result.

Post-disband customer sales: `ArchivedBusinessShopTest` now covers both trade
directions under both access settings. Positive pre-disband payout, exact refund
and retained customer items/money after rejection are verified; see
`chestshop-disband.md` for fixture and rejection-mechanism limits.

Post-disband inventory recovery: `DisbandStockRecoveryTest` verifies active-firm
outsider protection, former-proprietor withdrawal through container packets,
stock refresh on close and no money/sale effects. See `chestshop-disband.md` for
the deliberately broader orphaned-account access policy and coverage limits.

Visibility scope: `MarketVisibilityTest` verifies hide/trade/show, preserved hidden
preference after a real trade, stable active shop identity and money/stock effects.
HTTP retention is labeled characterization; both find-dialog aliases separately
prove hidden exclusion. See `rest-chestshop.md` for the command's scoped contract.

Rate-limit recovery: `RateLimitTest` now verifies history-route availability while
rotation is exhausted, unchanged JWT on denial, real minute-interval refill and
successful rotation afterward without financial effects. Interval behavior is
characterization; provider comments describe gradual refill inconsistently.
See `rest-rate-limits.md` for timing/provider limitations.

Changing transaction feeds: `TransactionFeedTest.feedPagesByLimitAndCursor` now
covers a payment arriving between pages and another after catch-up, exact cursor
resumption and replay without missing/duplicate IDs. The full-page hasMore hint
is distinguished from an empty caught-up page; see `rest-transaction-feed.md`.

Feed key lifecycle: `AdminKeyLifecycleTest` now verifies exact settled feed data
before rotation and after reissue, plus initial/saved-cursor denial for superseded
and revoked personal tokens. See `rest-transaction-feed.md` for concurrency limits.

Business feed key scope: `BusinessKeyLifecycleTest` now verifies exact settled
reserve debits, sibling-account restrictions before/after reissue and feed denial
for superseded/revoked tokens at initial and saved cursors. See
`rest-transaction-feed.md` for the distinction from live staff-role revocation.

Live API membership: `FeedMembershipLifecycleTest` verifies one unchanged personal
bearer across outsider/viewer/member/removal/regrant government roles, with exact
settled credit data and no money/token side effects. API VIEWER exclusion is
explicitly distinct from in-game/explorer access; see `rest-transaction-feed.md`.

API role hierarchy: `FeedMembershipLifecycleTest` now verifies persisted
AUTHORIZER promotion, preservation across weaker viewer mutations, demotion to
MEMBER with continued reads and final membership removal with denied reads.
The same token is used throughout; see `rest-transaction-feed.md`.

Archived API history: `FeedMembershipLifecycleTest` now verifies retained member
access to exact government credit history/feed after archive, with outsider denial
and unchanged funds/token/ledger state. The API and in-game archive read policies
are distinguished in `rest-transaction-feed.md`.

Queued webhook pause/resume: `WebhookRetryTest` now pauses after the first failed
attempt, observes the retry overdue alongside successful control dispatch, then
reactivates and verifies identical delivery identity/payload with no repeated
payments. See `rest-admin-webhooks.md` for the pre-dispatch boundary.

Queued webhook deletion: `WebhookRetryTest` now checks pending-delivery cascade,
repeat-delete no-op and no requests after the original retry deadline while a
control subscription receives a new payment. See `rest-admin-webhooks.md` for
the bounded observation and pre-dispatch scope.


Full core interaction audit: `20261021T230029Z-121106/run-1` completed with
709 passes, 23 failures matching all documented regressions, 13 profile/runtime
skips and zero errors in 819.71s. Every failed assertion was classified; no new
failure category appeared. See `results.md` for skip accounting and build limits.
The review queue and unavailable-runtime limitations above remain open.


Upstream integration: develop through `303135e` adds the PAR-101 derived Business/
Realty group-reconciliation task. The refreshed source index contains 589 entries.
That scheduled cross-plugin journey still needs live-stack review; upstream unit
and SQL tests are not counted as live acceptance here.

### Latest develop reconciliation and scheduled group acceptance

The current source inventory contains 587 entries after merging through `662724d`.
Finder acceptance now uses Curator inventory click packets and 47 physical shop
fixtures, covering both sides of the 45-result page boundary. The prior dialog
results are no longer the shipped interface. Typed-payment fixtures use globally
unique names under PAR-501; destination isolation assertions remain unchanged.

The new `groups` profile executes `DerivedGroupLifecycleTest` against the real
TreasuryAPI scheduler and Business API. It passed twice from clean state, checking
proprietor inclusion, FINANCIAL/ADMIN role grants, an offline employee's refreshed
publication timestamp, revocation, firm archival, preservation of manual grants,
and independent Business publication despite unavailable Realty. No balances or
postings change. This supersedes the Business scheduler gap above; real Realty,
binding edits/expiry/failure interleavings and browser authorization rendering still
need dedicated live coverage. A controlled disabled-scheduler run failed waiting for the first required membership; the original configuration was restored before final reruns.

### PAR-502 follow-up

[PAR-502 implementation and execution evidence](par-502.md) supersedes the older
runtime/group/browser/integration limitations where it supplies explicit tests.
It records the stale-binding removal and Vault rollback-cache fixes, source
fingerprints, failure history, clean pairs and remaining review work. PAR-499 and
PAR-502 remain open; the source inventory and passing suite are not exhaustive
branch acceptance. The saved-checkpoint crash and REST-restart profiles supplement
the earlier graceful-restart evidence. Balance aliases now have exact offline
amount and contextual permission assertions in `BalanceAccessTest`.

The combined `providers` profile now loads real Nexo, Utilities, Bolt, BoltChestShop
and BreweryX. Native trade/failure cases and startup Utilities item-permission
denial/grant passed twice (3 passes, 1 explicit external reload-defect skip each).
This does not establish every Bolt protection or BreweryX/VaultStorage branch.
See [provider evidence and preserved failure](par-502.md#real-combined-provider-profile).
