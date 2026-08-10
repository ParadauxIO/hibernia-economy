---
title: ChestShop
order: 3
description: The shop sign format and every ChestShop command.
---

# ChestShop

**ChestShop** lets you set up automatic buy/sell shops with just a chest and a sign, so
players can trade with you even while you're offline. It's a long-running community
plugin — originally by **Acrobot** and the ChestShop authors — that we run in a version
adapted for our servers, so shops settle through [Treasury](/docs/reference/treasury)
and can pay into a [business account](/docs/guides/chestshop-business).

This page is the full sign format and command list; for a walkthrough, see
**[Selling with ChestShops](/docs/guides/chestshop-basics)**.

## The shop sign

A shop sign has four lines, in this order:

```text
Steve          line 1 — owner
64             line 2 — quantity
100 : 90       line 3 — price
DIAMOND        line 4 — item
```

### Line 1 — owner

Who the shop belongs to.

| Value | Meaning |
|---|---|
| *your name* | A personal shop — money goes to you. |
| *(blank)* | Filled in with your name automatically. |
| `B:<code>` | A **firm account** — money goes to the company. The code after `B:` is a short **base-36 code** for the account, **not** the account's decimal number, so copy it exactly as shown. Get it from `/business account list` (or the Explorer). See [ChestShops for your firm](/docs/guides/chestshop-business). |
| `b:<FirmName>` | An older **legacy** form — lowercase `b:` plus the firm's *name*, which resolves to that firm's default business account. Still works (the prefix is matched case-insensitively) and the sign quietly self-heals to the modern `B:<code>` form. |
| `Admin Shop` | An **admin shop** with unlimited stock and no chest, backed by the server. Needs the `ChestShop.adminshop` permission. |

### Line 2 — quantity

How many items move per trade — a whole number. For example `64` trades a full stack at
a time. A single trade can move at most **3456** items (a double chest of 64 stacks).

### Line 3 — price

The `B` and `S` letters set the **buy** and **sell** prices — from the customer's point of
view:

| Value | Meaning |
|---|---|
| `B 100` | Players **buy** from the shop for 100 (the shop sells to them). |
| `S 90` | Players **sell** to the shop for 90 (the shop buys from them). |
| `100 : 90` | Both — the **first** number is the buy price (100), the **second** is the sell price (90). |

> [!NOTE]
> Free shops are **disabled** on this server (`ALLOW_FREE_SHOPS` is off). A `0`/`free`
> price is rejected at creation, and any pre-existing free shop is removed when next used.

The letter can go before or after the number (`B 100`, `100 B`, and `100B` all work). A
plain number with no letter — like `100` — becomes a **buy-only** shop (the same as
`B 100`).

> [!NOTE]
> A shop's sell price can't be higher than its buy price — that would let players
> buy-then-sell for free profit, so the server rejects it.

Numbers accept `K` and `M` shorthand: `1.5K` = 1,500, `2M` = 2,000,000.

### Line 4 — item

The item being traded, e.g. `DIAMOND` or `OAK_PLANKS`. Use `?` to fill it in from the
item you're holding when you place the sign.

**Custom items** (from the **Nexo** item plugin) go on this line too — as a short item
id or configured nickname (e.g. `ruby_gem`) rather than a vanilla material name. The
simplest way is to hold the item and use `?`, which encodes it correctly; `/iteminfo`
shows the exact text otherwise. Custom items trade identically to vanilla ones. (Item ids
appear *bare*, without a `nexo:` prefix, because a colon isn't allowed on the item line.)
See [Selling custom items](/docs/guides/chestshop-basics#selling-custom-items).

> [!TIP]
> Not sure what an item is called? Hold it and run `/iteminfo`. You can also look one up
> by name — e.g. `/iteminfo log`.

> [!NOTE]
> **Partial trades.** A trade isn't all-or-nothing: if the shop can't cover the full
> quantity, it serves **as much as it can** and charges/pays only for that — a customer
> gets the 12 items left in a chest that advertises 64, or as many as their money covers.
> A side stops only when it reaches **zero** (no stock, no room, or the customer can't
> afford one item). Controlled by `ALLOW_PARTIAL_TRANSACTIONS` (on).

## Commands

| Command | What it does |
|---|---|
| `/shopinfo` | Show details of the shop you're looking at — owner, item, quantity, prices. Aliases: `/sinfo`, `/shop`. |
| `/iteminfo [item]` | Show the name and shop code for the item in your hand, or for a named item (e.g. `/iteminfo log`). Alias: `/iinfo`. |
| `/cstoggle` | Turn the "someone used your shop" messages on or off. |
| `/csaccess` | Toggle your access to trade at shops. |
| `/csVersion` | Show the ChestShop version. Alias: `/chestshop`. |
| `/csMetrics` | Show server-wide shop metrics. |
| `/csGive <item code> [amount] [player]` | Admin: give an item by its shop code. |

## Shops and firms

To make a shop pay a company instead of a person, put the firm account's code (something
like `B:G`, from `/business account list`) on the owner line. The bit after `B:` is a
short base-36 code for the account, **not** its decimal number, so copy it exactly.
Building firm shops needs the
**CHESTSHOP** permission in that firm. Full walkthrough:
**[ChestShops for your firm](/docs/guides/chestshop-business)**.

## Permission nodes

ChestShop nodes use a `ChestShop.` prefix (defaults: `true` = everyone, `op` = operators).

### Commands

| Command (aliases) | Node | Default |
|---|---|---|
| `/iteminfo` (`/iinfo`) | `ChestShop.iteminfo` | `true` |
| `/shopinfo` (`/sinfo`, `/shop`) | `ChestShop.shopinfo` | `true` |
| `/cstoggle` | `ChestShop.toggle` | `true` |
| `/csaccess` | `ChestShop.accesstoggle` | `op` |
| `/csGive` · `/csVersion` (`/chestshop`) · `/csMetrics` | `ChestShop.admin` | `op` |

### Using and building shops

| Node | Grants | Default |
|---|---|---|
| `ChestShop.shop.*` | Use shops — parent of create/buy/sell | `true` |
| `ChestShop.shop.create` | Create a shop (parent of `.create.buy` / `.create.sell`) | inherits `true` |
| `ChestShop.shop.buy` | Buy from a shop | `true` |
| `ChestShop.shop.sell` | Sell to a shop | `true` |
| `ChestShop.admin` | Modify/destroy others' shops; create admin shops (parent of `ChestShop.adminshop`) | `op` |
| `ChestShop.adminshop` | Create/destroy **Admin Shops** | inherits from `ChestShop.admin` |
| `ChestShop.mod` | View other players' shop chests (no destroy/admin powers) | `op` |
| `ChestShop.nofee` | Skip the shop-creation fee | `op` |
| `ChestShop.notax.buy` · `ChestShop.notax.sell` | Skip transaction tax | `op` |
| `ChestShop.nolimit.buy.min` · `.buy.max` · `.sell.min` · `.sell.max` | Bypass configured price limits | `op` |

> [!NOTE]
> Most of these have per-item and per-category variants — e.g.
> `ChestShop.shop.create.<itemType>`, `ChestShop.shop.buy.<itemType>`, or category
> groups like `ChestShop.shop.create.food` — for restricting which items a player may
> trade. There are also per-name nodes (`ChestShop.name.*`, `ChestShop.othername.*`)
> for shared/town shops. See the upstream ChestShop `plugin.yml` for the full list.
