# ChestShop upstream reconciliation (ADT-217)

This selective reconciliation tracks [ADT-217](https://app.taskl.ie/w/paradaux/issues/ADT-217)
against official ChestShop master `86156d8c99c84a3dae62cc075581448de2bbc3e5`.
The local starting revision was `7f5c888a4146effaafa13fce11e34966389289d1`,
confirmed as remote `main` on 2026-10-29. The ticket's `develop` target is stale:
GitHub reports that branch missing and `main` as the default. Work remains on the
isolated `adt-217-reconcile-upstream-chestshop-fixes` branch.

| Official commit | Disposition | Native equivalent / decision |
| --- | --- | --- |
| [8233d87](https://github.com/ChestShop-authors/ChestShop-3/commit/8233d87e72e5f65049b46cf4d0f876ecc1839223) | Already equivalent | PR #150 and subsequent reconciliation use nullable `getBasePotionType()`, retain custom effects, and omit empty potion output. Existing `InfoServiceImplTest` covers these cases. No legacy potion API restored. |
| [4fd4148](https://github.com/ChestShop-authors/ChestShop-3/commit/4fd414868cf9ae1fc94e0d57cd12c81ae1f21acf) | Ported selectively | Unchanged edits are ignored. Native removal/creation carriers retain the old/new sign states. Maintaining the upstream legacy ABI is explicitly out of scope; no `ShopEditedEvent` or legacy Account facade is added. |
| [1fb1fba](https://github.com/ChestShop-authors/ChestShop-3/commit/1fb1fbaa4d179cacca042cd5a2213c397ae6a24c) | Ported | Successful replacement runs native destruction/refund/index removal, then creation/index replacement once. Failed replacements preserve the previous shop. Fees run after all native validators. |
| [428ccb8](https://github.com/ChestShop-authors/ChestShop-3/commit/428ccb8ee52ec8a4bbf556aec408c5b09fd0bd5d) | Ported selectively | `WORLDGUARD_ONLY_IN_REGION` defaults true, preserving old configuration behavior. False permits wilderness with explicit flag denials honored. `USE_FLAG` still requires ALLOW even with no region manager; this deliberately preserves the fork's stricter behavior. Existing onLoad registration and independent chest/use protection remain. |
| [ca92cf4](https://github.com/ChestShop-authors/ChestShop-3/commit/ca92cf4a2a2fb9829dd5f761b803f87ac39594e3) | Deferred | PlotSquared is a new optional integration, not a repair to an existing provider. ADT-214 records PlotSquared installed, but does not establish a requirement for ChestShop-specific plot flags. Add only with a separate operator requirement and absent/present API acceptance. |
| [6b49c05](https://github.com/ChestShop-authors/ChestShop-3/commit/6b49c05ea77e577ce120641e79e3f36bfadb2419) | Not applicable | No PlotSquared integration to soft-depend on. Revisit together with the feature above. |
| [86156d8](https://github.com/ChestShop-authors/ChestShop-3/commit/86156d8c99c84a3dae62cc075581448de2bbc3e5) | Not applicable | No bStats server-version reporting exists here. Native `MetricsServiceImpl` counts trades/items for `/csmetrics`. |

## Lifecycle contract

Front-side edits run at HIGHEST after ordinary protection handlers, and ignore
already-cancelled events. Unchanged text has no money or lifecycle effects.
Creation validation must succeed, including its full creation fee, before an old
shop is retired. A rejected replacement cancels the sign change and preserves the
old text and registry entry. Editing a shop into ordinary text is a removal.
Back-side edits do not enter the front-side shop lifecycle.

Successful edits use the old sign snapshot for removal and refund ownership; the
new sign snapshot and normalized text feed creation reactions. With a fee of
10 and refund of 5, the editor pays 10 and the old owner receives 5, once each,
subject to existing exemptions and funding/compensation behavior. The refund does
not fund the upfront creation check. Treasury transfer, sale linkage, Business
account resolution, item serialization and custom-item implementations are unchanged.

As with other Bukkit mutations, plugins must respect event priorities: cancelling
after HIGHEST or mutating a MONITOR event is not supported as transaction rollback.

## Evidence and acceptance boundaries

`ShopServiceImplTest` reproduced three failures before the repair: unchanged edits
charged again, replacement omitted the refund, and rejected replacement broke the
old sign. All three pass with the repair. Additional checks cover lifecycle order,
owner/item/price replacement, old-owner refund, erased shops, prior cancellation,
and late native validation rejection without charging. These use real native
services and MockBukkit signs with mocked economy and market boundaries; they do
not claim live ledger or database acceptance.

`WorldGuardBuildingTest` covers region/no-region/no-manager and ALLOW/DENY/unset
combinations, including preservation of an existing denial. Existing tests cover
Treasury/Business lookup, settlement, partial fills, compensation, market sync,
legacy add-on descriptors, and startup without WorldEdit/FAWE/WorldGuard APIs.

Authenticated Java/Bedrock acceptance, real Paper 26.3 item roundtrips, installed
add-on jars and `/firm` menus remain the live acceptance work tracked by
[PAR-496](https://app.taskl.ie/w/paradaux/issues/PAR-496) and
[PAR-502](https://app.taskl.ie/w/paradaux/issues/PAR-502), whose remaining checklists
were consolidated into [PAR-509](https://app.taskl.ie/w/paradaux/issues/PAR-509).
Those closures do not certify these checks. This task does not certify
official upstream 26.3 support or authorize deployment, database repair or migrations.

## Local verification

The initial implementation passed the full repository build, including
Treasury/REST database tests, and 1,791 ChestShop tests plus FAWE and optional-API
startup checks. Three sign-edit regressions failed before the lifecycle repair.

The final scope omits the newly introduced legacy ABI classes and edit-event bridge
at the maintainer's request. The replacement regression asserts native old/new sign
state, owner, item, price, ordered market updates, and exact fee/refund calls.
Final `bash gradlew :chestshop:spotlessApply build -Pci=true --console=plain`
passed in 1m 6s. ChestShop passed 1,791 tests plus FAWE and optional-API startup
checks, with no failures, errors or skips. Coverage and formatting gates passed.
The built JAR was checked to exclude the removed edit-event and account classes.

Artifact: `chestshop/build/libs/chestshop-2.3.0-SNAPSHOT.jar`.
SHA-256: `d23ded34bbc2bf1d21b54fc236fdb5d0fd3d540dcc8dea269f70251825b97709`.
No plugin staging or deployment was performed.

Local logs are retained in `.context/adt-217/`. Two initial overlapping Gradle
invocations failed on test-result files; subsequent serial builds passed.
