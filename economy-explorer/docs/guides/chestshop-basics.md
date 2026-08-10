---
title: Selling with ChestShops
order: 5
description: Build a chest shop with a sign so other players can buy from or sell to you automatically — even while you're offline.
---

# Selling with ChestShops

A **ChestShop** is a chest with a sign on it that buys or sells items automatically.
Once it's set up, other players trade with it on their own — you get paid (or stocked)
even when you're offline.

## What you need

- A **container** — a chest (or a double chest, barrel, etc.).
- The items you want to sell, placed inside it.
- A **sign**, placed against the container.

## The four sign lines

A shop sign has exactly four lines, and the order matters:

```text
Steve          ← line 1: your name (the shop owner)
64             ← line 2: how many items per trade
100 : 90       ← line 3: the price — buy and/or sell
DIAMOND        ← line 4: the item
```

| Line | What goes here |
|---|---|
| **1 — Owner** | Your name. Leave it blank and it fills in automatically. |
| **2 — Quantity** | How many items change hands per trade (e.g. `64` for a stack). |
| **3 — Price** | What the shop charges and/or pays (see below). |
| **4 — Item** | The item's name, e.g. `DIAMOND`. Use `?` to fill it from the item in your hand. |

> [!NOTE]
> Put the sign on the chest **before** you finish the last line — when you complete a
> valid sign on a container you own, it becomes a shop.

## Setting the price (line 3)

The `B` (buy) and `S` (sell) letters set the prices, **from the customer's point of view**:

| You write | What it means |
|---|---|
| `B 100` | Players **buy** from you for 100 (your shop **sells** to them) |
| `S 90` | Players **sell** to you for 90 (your shop **buys** from them) |
| `100 : 90` | Both — the **first** number is the buy price (100), the **second** is the sell price (90) |

A plain number with no letter (like `100`) makes a **buy-only** shop. The letter can go
before or after the number. Note that the sell price can't be set higher than the buy
price.

> [!NOTE]
> Free shops are turned off on this server, so a `0` or `free` price won't work — every
> shop must charge or pay something.

You can shorten big numbers with `K` and `M` — `1.5K` is 1,500 and `2M` is 2,000,000.

> [!TIP]
> A shop that both buys and sells needs enough **items** in the chest to sell, and
> enough **money** in your balance to buy. Top up whichever side is running low.

## Partial trades

Your shop doesn't refuse a trade just because it can't cover the *whole* amount — it
serves **as much as it can** and charges (or pays) only for that much:

- A customer tries to **buy** a 64-stack but the chest has only 12 left → they get **12**,
  and pay for 12.
- A customer wants to buy 64 but only has enough money for **20** → they get **20**.
- Someone tries to **sell** you 64 but the chest has room for only **30** → you take
  **30** and pay for 30.

A side stops completely only when it hits **zero** — nothing left to sell, no room or
money to buy, or the customer can't afford even a single item.

## Shopping at someone's shop

To trade at a shop, click its sign:

- **Right-click** to **buy** from the shop.
- **Left-click** to **sell** to it.

The [partial-trade](#partial-trades) rule works in your favour as a shopper too: if you
can't afford a full stack, you'll still get as many as your money covers.

## Check a shop

Look at any shop sign and run:

```text
/shopinfo
```

It tells you who owns it, the item, the quantity, and the prices. Not sure what an item
is called for line 4? Hold it and run:

```text
/iteminfo
```

## Selling custom items

Custom items from the server's item plugin (**Nexo**) trade just like vanilla ones — you
don't need to do anything special. The easiest way is to let the sign fill the item in
for you:

1. **Hold the custom item** you want to sell.
2. Build the sign as usual, and put `?` on **line 4** (the item line).

The `?` reads whatever you're holding, so the correct name lands on the sign automatically.
If you'd rather type it, hold the item and run `/iteminfo` — it prints the exact name to
put on line 4 (custom items show a short id or nickname, e.g. `ruby_gem`, rather than a
vanilla material name).

```text
Steve
16
B 500
ruby_gem       ← a custom item's id, or just use ? while holding it
```

> [!NOTE]
> Everything else is identical — quantity, buy/sell prices, [partial trades](#partial-trades)
> and firm ownership all work the same for custom items. Stock the chest with the real
> custom item (not a plain lookalike); the shop only trades exact matches.

## Want the shop to pay your company?

A shop normally pays the player who built it. To send the money to a **firm account**
instead, see **[ChestShops for your firm](/docs/guides/chestshop-business)**.

## Where to go next

- Route sales to a business: **[ChestShops for your firm](/docs/guides/chestshop-business)**.
- Every ChestShop command and the full sign format: the **[ChestShop reference](/docs/reference/chestshop)**.
