# ChestShop add-on compatibility and cutover

The native fork retains the legacy `com.Acrobot.ChestShop` entry points used by
ChestShopDatabase 1.2-SNAPSHOT, BoltChestShop 1.0.0, Utilities 3.0.0,
VaultStorage 1.2.0, BreweryX 3.7.0 and WorldGuardExtraFlags 4.2.5-SNAPSHOT.
These classes form an explicit namespace exception: they are adapters into the
native services, not a second ChestShop implementation or another balance store.
The supported binary member descriptors are recorded in
`chestshop/src/test/resources/legacy-addon-abi.tsv`.

## Hooks and ownership

- Item parsing and item-string queries publish the legacy events, so Utilities
  aliases and metadata behavior continue to work. Legacy item-database calls
  delegate to the same native item-code store.
- Pre-transaction events run after initial shop/client validation and before
  partial-fill, stock, capacity and funds checks. Utilities can enforce its
  configured `shop.buy.<id>` / `shop.sell.<id>` policy and refresh eligible traded
  items, and WorldGuardExtraFlags can deny `chestshop-transact`. A legacy listener
  cannot clear a native rejection. Settlement still belongs to Treasury.
- Protection checks reach BoltChestShop. Its grants replace a built-in denial
  only when `TURN_OFF_DEFAULT_PROTECTION_WHEN_PROTECTED_EXTERNALLY` is enabled.
  The historical `PROTECT_*_WITH_LWC`, `LWC_*_PROTECTION_TYPE` and
  `REMOVE_LWC_PROTECTION_AUTOMATICALLY` options also drive Bolt's protection
  lifecycle; they remain off by default.
- Created/destroyed/post-transaction events reach the installed add-ons.
  VaultStorage's externally emitted removal event enters native removal,
  including registry cleanup, once. Forwarded native removals do not recurse.
  BreweryX retains its unsealed-brew warning.

Keep Utilities installed when relying on its item permission policy or traded-item
normalization. The native Nexo resolver does not replace all Utilities features.
The compatibility boundary covers the installed integrations' API surface; it is
not a promise of every API method from every upstream ChestShop release.

## Finder configuration

On first use, `ChestShop/find.yml` imports `ChestShopDatabase/settings.yml` if
present. Existing native files are preserved. It accepts the legacy keys:

| Key | Behavior |
|---|---|
| `shop-template-buy`, `shop-template-sell`, `shop-template-both` | `material`, MiniMessage `display-name` and `lore` for results |
| `shop-icon-left-click-command` | Transit action; default `compass transit <x> <y> <z>` |
| `shop-icon-right-click-command` | GPS action; default `compass <x> <y> <z>` |
| `shop-preview-scale` | Floating item scale, default `0.5`; staging's imported value is `0.85` |
| `shop-preview-default-visibility` | Default for players with no stored preference |

Templates accept `%owner%`, `%buy-price%`, `%sell-price%`,
`%buy-price-unit%`, `%sell-price-unit%`, `%quantity%`, `%stock%`, `%capacity%`,
`%distance%`, `%region-name%`, `%world%`, `%x%`, `%y%` and `%z%`.
Player/world names are inserted as text rather than MiniMessage markup.
The plain-text `FIND_LOCATION_FORMAT` and `PREVIEW_NAME_FORMAT` settings remain
in the main `config.yml`.

Java dialog results show quantity/capacity and a GPS button alongside each
transit selection. Bedrock players are detected through Floodgate or Geyser;
results use an inventory menu with left-click transit and right-click GPS,
paging and canceled inventory moves. Commands execute as the player, with the
player's permissions, and only while the player remains in the result's world.
An empty command disables that action. Reload presentation settings with
`/chestshop reload`.

Native `/find toggle preview|visibility|hologram <true|false>` and
`/csdb find ...` aliases remain available after the old database add-on is removed.
Administrative `/csdb reload` and `/csdb resync <chunks>` require
`ChestShop.admin`. Finder access uses `ChestShop.find`; migrate effective
LuckPerms grants/denials from `csdb.find` explicitly.

## Upgrade and migration

1. Back up the plugin data and both registries. Deploy Treasury and ChestShop
   from this PR together; the preference import requires the updated MarketApi.
   Preserve `users.db`, `items.db`, existing configuration, Nexo definitions,
   Treasury accounts and Business firm links.
2. Keep the existing Utilities configuration available for the first native
   startup. Missing nonblank aliases merge into `ChestShop/nexo.yml`; existing
   native values win, and null/blank aliases are ignored. Invalid YAML stops the
   migration without replacing that file. The source Utilities file is untouched.
3. Pause legacy registry writes and follow [shop registry backfill](shop-registry-backfill.md)
   to reconcile unloaded shops, item metadata, owner identity and per-shop flags.
4. Export player preferences from the legacy database:

   ```sh
   mariadb --batch --raw --skip-column-names legacy_db \
     < ops/export-legacy-preferences.sql > legacy-preferences.jsonl
   ```

   Rows with a null legacy choice are omitted so the configured default still applies.

   Copy the export to `plugins/ChestShop/legacy-preferences.jsonl`, then run
   `/chestshop importpreferences` as an admin or from console. It inserts missing
   preferences only, preserving newer native choices. A malformed row or database
   error stops the run at that line; correct the cause and rerun. Reads stream
   off-thread and each write goes through Treasury's published API. Compare UUID
   sets and effective values before reopening trading.
5. Retire ChestShopDatabase after reconciliation. Running both finders at once
   creates command/registry ownership ambiguity. Keep BoltChestShop, Utilities,
   VaultStorage, BreweryX and WorldGuardExtraFlags installed for their integrations
   and other responsibilities.
6. Exercise restricted and permitted buy/sell, Bolt owner/delegate/unrelated
   access, denied WorldGuard transaction regions, Brewery warnings, VaultStorage
   removal, Java/Bedrock finder navigation, and preference/restart persistence.
   Preserve staging's deliberate ListenerControl/hopper behavior and validate it
   in the complete server stack.

The native plugin retains existing container, sign-open, stock-counter and
Treasury configuration choices. Alias migration does not prove every historic
Nexo ID still exists; validate migrated signs against the installed item definitions.

## Automated verification

`bash ./gradlew :chestshop:check -Pci=true` exercises the native suite, ABI
resolution, migration behavior, optional API startup and world-edit adapters.
The affected Treasury integration tests cover insert-only preference import.
For an optional test using the actual installed Utilities listener bytecode:

```sh
bash ./gradlew :chestshop:installedAddonTest \
  -PlegacyAddonDir=/path/to/server/plugins -Pci=true
```

The optional probe reads the configured restriction IDs and checks denied and
permitted purchases/sales against the unmodified Utilities transaction listener.
It does not deploy plugins or connect to the legacy database. A real Java and
Bedrock client acceptance pass remains part of deployment verification.

## Existing staging item data requiring reconciliation

The definition audit found 2,401 configured Nexo IDs with all definition YAML
files parsed successfully. Of the 497 usable Utilities aliases, 27 target IDs
absent from those definitions. These mappings are preserved: guessing replacement
IDs could change the traded item and strand existing stock. One further alias,
`krampuscoat`, is a vanilla metadata code and is migrated into the native item-code
alias store as well as the Nexo alias configuration. Metadata aliases accept both
`short: Item#code` and `Item#code: short` forms.

| Alias | Missing target ID |
|---|---|
| `cyan_ctble` | `cyan_wooden_lowtable` |
| `ironw_chair` | `iron_wooden_chair` |
| `iron_buffet` | `iron_wooden_buffettable` |
| `iron_hlamp` | `iron_wooden_hanginglamp` |
| `iron_blamp` | `iron_wooden_biglamp` |
| `iron_ctble` | `iron_wooden_lowtable` |
| `iron_dtble` | `iron_wooden_diningtable` |
| `jungle_chair` | `jungle_wooden_chair` |
| `jungle_btble` | `jungle_bedside_table` |
| `jungle_buffet` | `jungle_wooden_buffettable` |
| `jungle_strge` | `jungle_storage_furniture` |
| `jungle_sofa_s` | `jungle_wooden_sofa_single` |
| `jungle_sofa_l` | `jungle_wooden_sofa_left` |
| `jungle_sofa_r` | `jungle_wooden_sofa_right` |
| `jungle_sofa_m` | `jungle_wooden_sofa_middle` |
| `jungle_sofa_c` | `jungle_wooden_sofa_corner` |
| `jungle_lamp` | `jungle_wooden_lamp` |
| `jungle_hlamp` | `jungle_wooden_hanginglamp` |
| `jungle_blamp` | `jungle_wooden_biglamp` |
| `jungle_ctble` | `jungle_wooden_lowtable` |
| `jungle_dtble` | `jungle_wooden_diningtable` |
| `red_ctble` | `red_wooden_lowtable` |
| `white_h_pot` | `white_heather_potw` |
| `amethyst_buffet` | `amethyst_buffettable` |
| `cherry_blamp` | `cherry_wooden_biglamp` |
| `cherry_ctble` | `cherry_wooden_lowtable` |
| `prcs_lgrn_dye` | `processed_light_green_dye` |
