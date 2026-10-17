---
title: ChestShop configuration
order: 13
description: ChestShop's trade limits and rules, and how its sales tax rates work.
---

# ChestShop configuration

ChestShop's settings live in `plugins/ChestShop/config.yml`. It's a large upstream file;
below are the settings a DemocracyCraft operator actually cares about. After editing,
reload the config with **`/chestshop reload`** (alias `/cs reload`) — a full
restart isn't required.

> [!IMPORTANT]
> **ChestShop decides the sales tax rate; Treasury collects it.** The rate comes from the
> keys in [Sales tax](#sales-tax) below, and ChestShop hands each charge to Treasury's tax
> API, which moves it into Treasury's default tax account (`DCGovernment`) as its own ledger
> entry. `SERVER_ECONOMY_ACCOUNT` is inert on this server — leave it empty. Tax *exemptions*
> are by permission: `ChestShop.notax.buy`.

## Trade limits & fees

| Key | Default | What it does |
|---|---|---|
| `MAX_SHOP_AMOUNT` | `3456` | **Per-trade cap** — the most items one transaction can move (a double chest of stacks). |
| `SHOP_CREATION_PRICE` | `0` | Fee to create a shop. `0` = free (the live value). |
| `SHOP_REFUND_PRICE` | `0` | Money refunded when a shop sign is destroyed. |
| `PRICE_PRECISION` | `2` | Max decimal places allowed on prices. |

> [!NOTE]
> Per-item min/max price caps live in a separate file, `priceLimits.yml`, enforced at shop
> creation. It currently holds only example entries.

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
| `ALLOW_AUTO_ITEM_FILL` | `true` | Allows `?` on the item line to fill from the held item. |

## Admin shops & naming

| Key | Default | What it does |
|---|---|---|
| `ADMIN_SHOP_NAME` | `Admin Shop` | The owner-line text that marks a sign as an unlimited admin shop. |
| `FORCE_UNLIMITED_ADMIN_SHOP` | `false` | Makes admin shops unlimited even with a container attached. |
| `SERVER_ECONOMY_ACCOUNT` | *(empty)* | **Inert on DC** — Treasury owns the accounts (see the note above). |

## Sales tax

Every sale is taxed on the money it pays out, charged to whoever receives it: the shop owner
when a player buys, the player when they sell to a shop. Sales into an admin shop are not taxed.

| Key | Default | What it does |
|---|---|---|
| `TAX_AMOUNT` | `0` | Percent of a player-shop sale taken as tax, when `TAX_BRACKETS` is empty. |
| `SERVER_TAX_AMOUNT` | `0` | Percent taken when the other side is an admin shop / the server account. |
| `TAX_BRACKETS` | `[]` | Balance-based brackets for player shops, replacing `TAX_AMOUNT` when set. Entries are `'<balance floor>=<percent>'`. |
| `TAX_ITEM_MULTIPLIERS` | `[]` | Per-item multipliers on the rate a sale would otherwise pay, capped at 100%. Entries are `'<item>=<multiplier>'`. |

**Brackets** use the receiver's balance *before* the sale and are flat, not marginal — the
whole sale is taxed at its bracket's percent, like Treasury's balance tax:

```yaml
TAX_BRACKETS:
- '0=0'      # under $1,000: no tax
- '1000=1'   # $1,000 – $4,999.99: 1%
- '5000=2'   # $5,000 and up: 2%
```

**Item multipliers** scale that rate for particular items — `2` doubles it, `0.5` halves it,
`0` makes the item tax-free. An item is matched by, most specific first:

1. its custom item id, `<namespace>:<id>` — Nexo items are `nexo:<item id>`, and any plugin
   that stores an item id string in the item's persistent data works the same way;
2. its ChestShop item code, as written on signs (with or without the `#…` metadata suffix);
3. its material name, e.g. `NETHERITE_INGOT` — **plain items only**, so a Nexo item made
   from paper is never taxed as `PAPER`.

```yaml
TAX_ITEM_MULTIPLIERS:
- 'NETHERITE_INGOT=2'    # netherite ingots: twice the normal rate
- 'nexo:ruby_sword=3'    # a Nexo item by its id
```

With both of the examples above, a seller holding $3,000 pays 1% on most sales and 2% on
netherite ingots. A malformed entry is logged once and skipped; `/chestshop reload` applies
changes.

## Protection

| Key | Default | What it does |
|---|---|---|
| `USE_BUILT_IN_PROTECTION` | `true` | ChestShop's own chest-destruction protection. Keep on. |
| `TURN_OFF_SIGN_PROTECTION` | `false` | **Leave `false`** — `true` lets anyone destroy others' shop signs. |
| `TURN_OFF_HOPPER_PROTECTION` | `false` | **Leave `false`** — `true` lets hoppers siphon shop chests. |
| `ALLOW_LEFT_CLICK_DESTROYING` | `true` | Owner left-clicking their own sign starts breaking it. |
| `REMOVE_EMPTY_SHOPS` | `false` | Auto-destroys a shop's sign when it runs empty. |

## Region gating

`WORLDGUARD_INTEGRATION`, `GRIEFPREVENTION_INTEGRATION`, `REDPROTECT_INTEGRATION` and the
related flags are all **off** on the live server (shops can be built anywhere allowed by
normal build perms). Turn these on if you want to restrict shop creation to claims or
regions.

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
server restart interrupts pending cleanup, use `/find resync` on loaded chunks.

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
