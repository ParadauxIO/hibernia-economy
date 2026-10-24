---
title: ChestShop configuration
order: 13
description: ChestShop trade rules, sales-tax rates, integrations, and recovery evidence.
---

# ChestShop configuration

ChestShop's settings live in `plugins/ChestShop/config.yml`. It's a large upstream file;
below are the main operating settings and their shipped defaults. Check your tenant's
files for effective values. `/chestshop reload` (alias `/cs reload`) refreshes
configuration; installing or changing optional integration plugins needs a restart.

> [!IMPORTANT]
> **ChestShop sets the rate; Treasury collects the tax.** Set `TAX_AMOUNT` for
> ordinary shops and `SERVER_TAX_AMOUNT` for admin/server counterparties in this
> file. Values are **percentages** (`5` means 5%), both default to `0`.
> Treasury's `government.tax-income-account` selects the destination; Treasury's
> source-income-tax settings do not set this sales-tax rate.

Tax is deducted from the money recipient after the primary trade commits. It is
best-effort: a tax failure logs a warning but does not undo the purchase. The
market record contains the tax actually collected. The current Treasury adapter
checks the initiating player's `ChestShop.notax.buy` exemption; do not assume
`ChestShop.notax.sell` independently exempts a sale.

`SERVER_ECONOMY_ACCOUNT` is also active: it identifies the account backing server
shops when configured. It must resolve to a known player account;
`SERVER_ECONOMY_ACCOUNT_UUID` supports explicit identity when name resolution is
unavailable. Inspect startup warnings and restart after changing that identity.
Blank uses ChestShop's internal system account for
unlimited-shop money flows. Verify the recipient and tax with a small staging
buy and sell after changing these settings.

## Trade limits & fees

| Key | Default | What it does |
|---|---|---|
| `MAX_SHOP_AMOUNT` | `3456` | **Per-trade cap** — the most items one transaction can move (a double chest of stacks). |
| `SHOP_CREATION_PRICE` | `0` | Fee to create a shop. `0` = free. |
| `SHOP_REFUND_PRICE` | `0` | Money refunded when a shop sign is destroyed. |
| `PRICE_PRECISION` | `2` | Max decimal places allowed on prices. |

> [!NOTE]
> The upstream per-item `priceLimits.yml` and discount systems were removed from
> this fork. Old files and `ChestShop.nolimit.*` nodes do not enforce or bypass
> a native price-cap policy. The sell-above-buy check below remains active.

## Trade rules

| Key | Default | What it does |
|---|---|---|
| `BLOCK_SHOPS_WITH_SELL_PRICE_HIGHER_THAN_BUY_PRICE` | `true` | Blocks shops where sell > buy (anti money-printing). **Keep on.** |
| `ALLOW_PARTIAL_TRANSACTIONS` | `true` | Lets a trade scale down when funds/items/space are short rather than failing. |
| `REVERSE_BUTTONS` | `false` | Swaps clicks: left-click buys, right-click sells. (Default: right=buy, left=sell.) |
| `SHIFT_SELLS_IN_STACKS` | `false` | Shift-click trades in 64-stacks. |
| `SHIFT_SELLS_EVERYTHING` | `false` | Shift-click trades all matching items at once. |
| `SHOP_INTERACTION_INTERVAL` | `250` | Anti-spam cooldown between sign uses (ms). |
| `IGNORE_CREATIVE_MODE` | `true` | Blocks creative-mode players from trading. **Keep on.** |
| `ALLOW_FREE_SHOPS` | `false` | Reject free/zero-price shops when disabled. |
| `USE_STOCK_COUNTER` | `false` | Show stock counts on shop signs. Hopper refreshes do not update finder/market database observations. |
| `HOPPER_STOCK_REFRESH_INTERVAL_TICKS` | `100` | Wait from the first hopper change before recounting stock (about five seconds at 20 TPS). Batches drain across ticks, with at most 32 normal and 32 recovery containers per tick and separate 0.5 ms soft budgets. Values below 1 use 1; reload applies the interval to pending work. |
| `ALLOW_AUTO_ITEM_FILL` | `true` | Allows `?` on the item line to fill from the held item. |

## Admin shops & naming

| Key | Default | What it does |
|---|---|---|
| `ADMIN_SHOP_NAME` | `Admin Shop` | The owner-line text that marks a sign as an unlimited admin shop. |
| `FORCE_UNLIMITED_ADMIN_SHOP` | `false` | Makes admin shops unlimited even with a container attached. |
| `TAX_AMOUNT` / `SERVER_TAX_AMOUNT` | `0` / `0` | Sales-tax percentages for ordinary / admin-server counterparties. |
| `SERVER_ECONOMY_ACCOUNT` | *(empty)* | Optional server-shop account; blank uses the internal system account. |

## Protection

| Key | Default | What it does |
|---|---|---|
| `USE_BUILT_IN_PROTECTION` | `true` | ChestShop's own chest-destruction protection. Keep on. |
| `TURN_OFF_SIGN_PROTECTION` | `false` | **Leave `false`** — `true` lets anyone destroy others' shop signs. |
| `TURN_OFF_HOPPER_PROTECTION` | `false` | **Leave `false`** — `true` lets hoppers siphon shop chests. |
| `ALLOW_LEFT_CLICK_DESTROYING` | `true` | Owner left-clicking their own sign starts breaking it. |
| `REMOVE_EMPTY_SHOPS` | `false` | Auto-destroys a shop's sign when it runs empty. |

## Region gating

`WORLDGUARD_INTEGRATION`, `GRIEFPREVENTION_INTEGRATION` and their related flags
control optional claim/region restrictions. `REDPROTECT_INTEGRATION` is not a
supported setting in this fork. Check the installed
plugins and your tenant configuration before changing them; normal build permissions
and add-on restrictions can still reject shop creation or trades.

## Custom items (Nexo)

Support for **Nexo** custom items is **built into ChestShop** — no companion plugin is
needed (this was previously a separate `NexoUtilities` plugin). ChestShop hooks Nexo
automatically when it's present (`Nexo` is a softdepend); the integration classes stay
dormant when it isn't. ItemsAdder items are also recognised (by their persistent-data id).

Configuration lives in `plugins/ChestShop/nexo.yml`, generated on first run:

| Key | Default | What it does |
|---|---|---|
| `aliases.<name>` | *(example)* | Map a short **alias → nexo id** (e.g. `bnug: bunny_nugget`), or an **`ItemName#code` → alias** for a specific variant. Players can then type the alias on the item line. |
| `display.preferAlias` | `true` | Prefer showing a configured alias over the raw id on signs. |
| `display.fallbackFormat` | `%s` | Sign text when no alias applies. **Keep `%s`** — it writes the **bare id**. ChestShop's item-line pattern rejects colons, so a `nexo:%s` form would fail sign validation and the shop wouldn't create. |
| `display.signMaxChars` | `15` | Max characters an alias may use before falling back to the id. |
| `input.acceptBareIds` | `true` | Accept a bare nexo id on the item line (no `nexo:` prefix). **Keep on** — it's what lets bare ids from `fallbackFormat: %s` round-trip. |
| `input.supportItemsAdder` | `true` | Also detect ItemsAdder items. |

> [!NOTE]
> Players don't touch `nexo.yml` — they just hold the item and use `?`, or type its id.
> Aliases are an admin convenience for nicer/shorter sign text. See
> [Selling custom items](/docs/guides/chestshop-basics#selling-custom-items).

## DemocracyCraft-specific behaviour (not config)

These are fork behaviours with no config key — worth knowing they exist:

- **Treasury is the backend** — every trade moves money through Treasury; ChestShop stores
  no balances.
- **Firm shops** — a `B:<code>` owner line routes a shop to a Treasury business account,
  gated by the firm's `CHESTSHOP` role permission. Legacy `b:<FirmName>` signs self-heal
  to the new format on first trade.
- **Native custom items** — Nexo/ItemsAdder support is compiled into ChestShop (see
  [Custom items](#custom-items-nexo)); no separate bridge plugin required.
- **Market analytics** — every trade is recorded to Treasury's market API, which is what
  feeds this Explorer's Market pages. On whenever the API is present.

## Failed trade recovery evidence

If settlement fails and ChestShop cannot return the goods (including an inventory
exception), the trade is cancelled and a warning identifies its `tradeId`.
ChestShop commits a recovery row to `plugins/ChestShop/users.db`, in
`trade_recovery`, independently of normal sale logging. Keep this database in
server backups; these rows are retained until an operator archives them.

To inspect outstanding evidence with SQLite:

```sql
SELECT id, trade_id, recorded_at, evidence FROM trade_recovery ORDER BY id;
```

`recorded_at` is UTC. The versioned YAML evidence contains the player and owner
UUIDs/names, buy/sell direction, unlimited-shop flag, exact price, sign world and
coordinates when available, and Bukkit item snapshots with amounts and metadata.
A known settlement transaction ID and reversal exception are included when
available. Rows are evidence of failed attempts, not proof of a specific loss or
an instruction to refund. Compare the inventories and Treasury ledger before
manually reconciling; ChestShop does not automatically retry or compensate.

If SQLite cannot save the record, an error includes the trade ID and serialized
evidence in the server log. Preserve that log for manual investigation.

### WorldEdit, FAWE and WorldGuard

WorldEdit and FastAsyncWorldEdit are optional. Completed edits reconcile affected
shop signs with Treasury: removed signs disappear from `/find` and lose their
previews; pasted or restored shop signs are registered again. Cleanup is drained
on the server thread in batches of at most 256 positions per second. If a chunk
unloads first, cleanup waits for it to load; it never force-loads chunks. After a
server restart interrupts pending cleanup, use `/chestshop resync <chunks>` on loaded chunks.

With WorldGuard installed, `%region-name%` is available in these plain-text
`config.yml` templates (no PlaceholderAPI required):

```yaml
FIND_LOCATION_FORMAT: '%region-name% %world%: %x%, %y%, %z%'
PREVIEW_NAME_FORMAT: '%region-name%'
```

The defaults keep coordinate-only `/find` labels and unnamed item previews.
`%region-name%` uses the highest-priority applicable region ID, breaking ties by
alphabetical ID. It is empty outside regions, for unavailable worlds, or without
WorldGuard. The global region is excluded. Region names work independently of
WorldGuard's ChestShop protection settings. Templates also support `%world%`,
`%x%`, `%y%` and `%z%`; values are literal text, not formatting markup.

## Legacy add-ons and finder settings

The fork exposes the legacy entry points used by BoltChestShop, Utilities,
VaultStorage, BreweryX and WorldGuardExtraFlags. Keep Utilities installed if
you rely on its `shop.buy.<id>` / `shop.sell.<id>` policy and traded-item refresh.

`plugins/ChestShop/find.yml` imports the old ChestShopDatabase presentation
settings on first use: buy/sell/both result materials and MiniMessage templates,
transit/GPS commands, preview scale and default preview visibility. Existing
native settings win. `/chestshop reload` reloads the file and applies the new scale
to existing previews without changing player visibility. Navigation commands
run with the player's permissions. Java results expose both actions; Bedrock
results use left/right clicks in an inventory menu. Quantity and remaining
capacity appear in results.

Java results include **Inspect item** for the stored name, enchantments and other
metadata. Inventory results offer it on shift-right-click, or ordinary right-click
when no right-click navigation command is configured. An empty left-click command
disables transit; an empty right-click command selects inspection. Back returns
to the same result page.

### Search aliases

`plugins/ChestShop/find-aliases.yml` imports
`plugins/ChestShopDatabase/item-code-groupings.yml` once when the native file is
absent. These aliases affect typed finder searches only; they do not change sign
codes or item identity and are separate from `nexo.yml` aliases:

```yaml
groupings:
  - item-code: DIAMOND
    aliases: [diamonds, diamond_gem]
```

Use actual registry item codes. Alias matching is case-insensitive and codes with
spaces remain supported. Back up the file, edit and run `/chestshop reload`.
Malformed/conflicting entries reject the replacement alias map; inspect the error,
restore the previous file and reload. Test both alias and original code, including
an unknown alias. Item-code suggestions refresh every 30 seconds; new codes may
not appear immediately. Native search ranks all matching shops before applying
filters/sorting, rather than limiting candidates to the query API's default 500.

Migrate effective legacy `csdb.*` permissions and explicit denials to the native
nodes, including `ChestShop.find`. Command aliases do not migrate permissions.

At startup, missing nonblank Utilities aliases merge into `ChestShop/nexo.yml`
without replacing native aliases. Keep both original item databases and Nexo
definitions during cutover. `/chestshop importpreferences` imports missing player
preview choices from `legacy-preferences.jsonl`, preserving native choices.
See the repository's [cutover guide](https://github.com/ParadauxIO/hibernia-economy/blob/develop/wiki/chestshop-add-on-compatibility.md)
for exports, plugin ordering and acceptance checks.
