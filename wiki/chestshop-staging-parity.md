# ChestShop staging parity audit

Audited 2026-10-18 against source commit
`4ec5dcfa5c9a233804136845e3c6f0ae8ab7c4d8`.

**Baseline verdict: the audited fork was not feature complete against the legacy server stack.**

The implementation following this audit is described in
[add-on compatibility and cutover](chestshop-add-on-compatibility.md). The matrix
below records the original baseline, not the status of the subsequent fixes.
It replaces most ChestShopDatabase functionality and adds substantial ledger,
business and market functionality, but several server integrations have been lost.
Passing the fork's tests does not establish compatibility with those add-ons.

## Baseline and evidence

The available staging installation is `/home/paseo/democracycraft-clone`. It
already contains `chestshop-2.3.0-SNAPSHOT.jar`, rather than an independently
running legacy ChestShop. The old plugin and ChestShop configuration are preserved
under `hibernia-economy-previous/ChestShop-4.0.2.jar` and
`hibernia-economy-previous/plugin-data/ChestShop/`. The comparison therefore uses
the preserved configuration, installed add-on jars and configuration, the current
fork's source, and staging's `logs/latest.log`. It does not claim to have replayed
the old server or tested real player transactions.

All top-level installed plugin jars were scanned for references to
`com/Acrobot/ChestShop`. Six jars reference that API: ChestShopDatabase,
BoltChestShop, Utilities, VaultStorage, BreweryX and WorldGuardExtraFlags.
Their relevant classes were inspected using `javap -p -c`, rather than inferring
behavior from their names. Scratch evidence and the build log are in
`.context/chestshop-parity/` and are excluded from Git locally.

The staging startup log independently records failures for all six:

| Plugin | Log line | Observed failure |
|---|---:|---|
| ChestShopDatabase 1.2-SNAPSHOT | 1695 | Fails to enable in `UnsafeChestShopSign.init`; subsequently disabled |
| BoltChestShop 1.0.0 | 2290 | Cannot register its protection listener |
| Utilities 3.0.0 | 2428–2429 | Item parsing and pre-transaction listeners cannot register |
| VaultStorage 1.2.0 | 1912–1927 | Fails to enable on missing `ShopDestroyedEvent`; subsequently disabled |
| BreweryX 3.7.0 | 2080 | Shop-created listener cannot register |
| WorldGuardExtraFlags 4.2.5-SNAPSHOT | 1693 | Pre-transaction listener cannot register |

These are observations from the available startup log, not a fresh server restart.
The common cause is the removed legacy API namespace and event pipeline. Merely
adding similarly named classes would not restore behavior: lifecycle and trade
services must actually invoke the hooks at the appropriate point.

## Feature comparison

“Present” below means source implementation with relevant automated coverage;
it does not imply live acceptance with the complete staging plugin stack.

| Capability | Fork assessment | Evidence / remaining work |
|---|---|---|
| Sign creation, buy/sell, admin shops, price/quantity limits, partial fills | Present | `ShopServiceImpl`, `TradeValidatorImpl`, `PartialFillCalculatorImpl`, settlement and inventory tests |
| Player ownership, restricted signs, notifications, admin bypass | Present | Account, restricted-sign, notification and bypass services/tests; migrated command names need permission review |
| Container types, shift stacks, sign-to-open, stock counter | Configurable | Staging retains custom container list, shift-stack, sign-open and stock-counter settings; fresh defaults differ |
| Nexo base items, aliases, metadata variants, dye input, autograph exclusion, ItemsAdder identity conversion | Mostly present | `NexoIntegration`; this is not full standalone ItemsAdder integration, since it hooks through Nexo |
| Utilities aliases on existing signs | Configuration gap | 497 nonblank aliases in Utilities are missing/mismatched in staging `ChestShop/nexo.yml`, which contains only the generated example alias |
| Utilities item-specific buy/sell requirements | Missing | Utilities enables restrictions for 79 item IDs using `shop.buy.<id>` / `shop.sell.<id>`; fork checks `ChestShop.shop.*` and material/full-code permissions instead |
| Utilities refresh of traded base Nexo items | Missing | Legacy `TransactionManager.onPreTransaction` calls `updateTradedItems` on the relevant inventory; native Nexo integration resolves/parses items and clears its base cache, but does not reproduce this inventory update |
| Finder held-item / typed-item search, fuzzy option, world scoping | Present | `FindCommand`, `ShopFinderServiceImpl`, `FindState` |
| Buy cheap, buy nearby, sell best; shop-type / empty / full filters | Present | `FindState` and dialog tests |
| Sort by total/unit buy/sell price, stock, remaining capacity, quantity, distance, weighted priorities | Present | All eight `ShopAttribute` values and `FindState` |
| Result owner, price, unit price, stock, distance and location | Present | `FindDialog` labels and item bodies; presentation differs from the old inventory GUI |
| Result quantity and remaining-capacity display | Missing from rendering | Available in the model and sorting, but native result labels omit both; old lore shows both |
| Configurable region name in results | Present, requires config | `ShopDisplayService` + WorldGuard region-name provider; native location format must include `%region-name%` |
| Finder transit/GPS navigation | Missing | Old settings use `compass transit <x> <y> <z>` and `compass <x> <y> <z>`; `FindDialog.select` only sends coordinates and closes |
| Configurable buy/sell/both result materials, MiniMessage display/lore templates | Reduced | Native dialog uses fixed rendering plus a plain-text location template; it does not consume old `shop-template-*` settings |
| Floating item previews, per-shop hologram flag, persistent player preview preference | Present | `PreviewServiceImpl`, `ShopToggleCommand`, market preference APIs/tests |
| Preview scale / default visibility configuration | Reduced | Old staging scale is `0.85`; native scale is fixed at `0.5`, entity visibility defaults true |
| Existing registry migration, including unloaded chunks | Mechanism present; cutover unverified | JSONL importer and runbook exist; no source/destination database or location reconciliation was performed in this audit |
| Legacy player preview preferences | Migration unverified | Registry export covers shops; a separate preference migration/reconciliation is still needed before assuming player choices survive |
| WorldEdit / FAWE removal cleanup | Present | Separate stock-WorldEdit and FAWE adapters/tests; independent of broken ChestShopDatabase |
| Bedrock finder behavior | Unverified / integration gap | Old finder explicitly branches on a Bedrock predicate; framework 1.2.0 offers `BedrockSupport`, but ChestShop binds no detector. No Bedrock client acceptance test was performed |
| WorldGuard chest access and region-gated creation | Present | Native `WorldGuardIntegration`; respects its own configuration gates |
| WorldGuardExtraFlags `chestshop-transact` | Missing | Old listener queries this flag with player bypass handling before a trade; native WorldGuard integration checks creation/chest protection, not this transaction flag |
| Bolt lock ownership / shared `chestshop` access | Missing | Bolt add-on cannot register; native `ProtectionServiceImpl` has no Bolt provider |
| Bolt sign/container auto-lock creation and removal | Missing integration | Old add-on handles protection events and shop destruction. Old automatic LWC-named settings were false, so preserve configured behavior rather than enabling new locking by default |
| VaultStorage shop detection / connected signs / removal hooks | Broken dependency | `ChestShopServiceImp` calls legacy `ChestShopSign`, `uBlock`, and fires legacy `ShopDestroyedEvent`; the whole plugin fails startup |
| Brewery unsealed-brew shop warning | Missing | Old hook warns with `Player_ShopSealBrew` when a new shop container has an unsealed potion; it does not auto-seal or prohibit the sale |
| Treasury settlement, recovery audit, business accounts/aliases, sales accounting | Additional capabilities | Native Treasury/Business integration, trade recovery and business account tests |
| Market REST/explorer, historical sales/prices | Additional capabilities | Shared market API and REST/explorer implementation; full API/browser acceptance was outside this audit |

Native source references are under
[`chestshop/src/main/java/io/paradaux/chestshop`](../chestshop/src/main/java/io/paradaux/chestshop/).
The six legacy classes establishing the integration gaps are:

- `org.popcraft.boltchestshop.BoltChestShop`
- `com.utilities.listeners.ChestShopItemListener` and `com.utilities.managers.TransactionManager`
- `net.democracycraft.vault.internal.service.ChestShopServiceImp`
- `com.dre.brewery.integration.listeners.ChestShopListener`
- `net.goldtreeservers.worldguardextraflags.listeners.ChestShopListener`
- `io.github.md5sha256.chestshopdatabase.command.FindCommand`, its dialog/results GUI and preview handler

## Configuration and migration differences

Staging's current ChestShop configuration preserves the old shop containers
(including decorated pots and shulker boxes), `SHIFT_SELLS_IN_STACKS=true`,
`ALLOW_SIGN_CHEST_OPEN=true`, `ADMIN_SHOP_NAME=Government`, stock counters,
WorldGuard protection and excluded repair-cost item attributes. The shipped
defaults differ. Preserve these decisions explicitly during deployment.

`TAX_AMOUNT=2.5` and `SERVER_ECONOMY_ACCOUNT=DCGovernment` remain in the old file;
do not assume these establish native Treasury tax/settlement behavior. Verify
the Treasury sales-tax rule and government account mapping as described in
[ChestShop configuration](../economy-explorer/docs/admin/config-chestshop.md).

The old `TURN_OFF_DEFAULT_PROTECTION_WHEN_PROTECTED_EXTERNALLY=true` setting is
still enabled. Bolt's missing hook makes protection behavior particularly
important to retest: built-in checks cannot prove parity with Bolt ownership
and sharing rules. Do not change this flag blindly as a substitute for a Bolt
integration.

Staging also uses ListenerControl to unregister ChestShop's
`InventoryMoveItemEvent` listener (configuration and startup log line 1528).
Hopper protection and hopper-driven stock refresh must be checked in the actual
server configuration, even if the native listener passes isolated tests.

Legacy `csdb.*` nodes and `/csdb`/`/find toggle` command paths are not native
command compatibility. Native toggles/resync live under `/chestshop`, and finder
access uses `ChestShop.find`. Review LuckPerms mappings and staff tooling.
The audit did not query effective permissions or the legacy database.

For aliases, merge the 497 usable entries from `Utilities/config.yml:aliases`
into `ChestShop/nexo.yml:aliases`, preserving real native overrides and reporting
conflicts. One of the 498 source entries has a null value. Validate every
migrated code against the installed Nexo definitions and existing item database;
copying the YAML alone does not prove that every item remains available.

A review candidate has been prepared at
`.context/chestshop-parity/nexo-merged.yml`: 497 usable source aliases merged into
the native file, retaining its generated example (498 entries total), with no
overwrite conflicts. YAML round-trip and exact alias-value assertions passed.
The candidate has not been installed or validated through Nexo on a server.

## Work required before parity sign-off

1. Restore the six integrations through native adapters or maintained add-on
   ports. Prioritize the 79-item permission policy, Bolt access, VaultStorage
   startup and `chestshop-transact` enforcement. Implement hooks before any goods
   or money move; retain the ledger's transaction guarantees.
2. Merge and validate aliases, implement safe traded-item refresh, and verify
   existing custom sign/item-code identity. Keep autograph and metadata variants
   intact; do not normalize personalized items into base items.
3. Restore both configured navigation actions, show batch quantity and remaining
   capacity in results, expose preview settings, and provide equivalent result
   customization where the old templates are needed.
4. Bind Bedrock detection and exercise finder screens, wait behavior, results,
   navigation and previews on a real Bedrock client. UUID/name acceptance alone
   does not prove the finder UI works.
5. Run the [registry backfill procedure](shop-registry-backfill.md), compare
   location sets/flags/metadata and migrate preview preferences. Loaded-chunk
   resync alone is insufficient for initial migration.
6. Verify the complete installed stack on staging: all required integrations
   start; owners, Bolt delegates and unrelated players get the intended access;
   restricted items and transaction-denied regions cannot trade; permitted
   buy/sell and business/admin trades settle correctly; transit/GPS works;
   WorldEdit/FAWE and VaultStorage removal leave no stale shop/previews; Brewery
   warnings appear; visibility/preferences survive reconnect and restart.

Do not remove ChestShopDatabase until its registry/preferences are reconciled.
After its replacement is verified, retire that add-on to avoid duplicate finder
commands and two registries. Utilities, Bolt and VaultStorage have other server
responsibilities and cannot simply be removed to silence these errors.

## Verification performed

`JAVA_HOME=/home/paseo/.jdks/jdk-21.0.12.1+1 bash ./gradlew :chestshop:check -Pci=true --console=plain`
completed successfully. Gradle restored cached test/coverage results: 1,357 main
tests, one FAWE adapter test, and one startup test without world-editing APIs;
zero failures, errors or skips. Coverage verification and Spotless also passed.

This audit did not alter staging files, restart services, modify databases,
deploy plugins or conduct in-game acceptance. This historical audit alone does not establish parity; implementation checks and
remaining deployment acceptance are documented in the linked cutover guide.
