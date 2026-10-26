---
title: What's new
order: 0
description: Find the shopping, business, Explorer and integration features in the major economy update.
---

# What's new in the economy update

This guide covers the develop-to-main release in
[PR #2](https://github.com/ParadauxIO/hibernia-economy/pull/2), checked against
`177c1d4b` on 26 October 2026. It describes the release candidate; availability on
your server depends on deployment, configuration and installed integrations.

## Shopping and selling

- **Find and compare shops.** Use `/find` with an item or the item in your hand;
  inspect stored item details and navigate to results in your current world.
  Shop owners can hide shops and control optional item previews. See the
  [ChestShop reference](/docs/reference/chestshop).
- **Review purchases before paying.** Right-click a sign to inspect the exact
  goods, quantity and price, then confirm or cancel. Ordinary exact-item sales
  use left-click. Offers are checked again before settlement.
- **Trade variations from one sign.** A prefix such as `carlton_dry*` opens a
  variation selector. Choose **Change quantity** for 1×, 2×, 4×, 8× or a custom
  item count; the refreshed preview applies stock, funds and capacity limits.
  Wildcard shops are excluded from `/find` and market shop listings, but their
  sales still appear in history. See [shop setup and trading](/docs/guides/chestshop-basics).
- **Use a business alias on signs.** Assign a short alias to a specific account.
  Changing or clearing it stops old signs from resolving; update those signs.
  See [firm shops](/docs/guides/chestshop-business#shop-aliases).

Custom-item integrations, floating previews and free shops depend on server
configuration. Free shops are disabled in the shipped configuration.

## Running a firm

Open `/firm menu` to browse firms, staff and accounts and submit management or
money actions with your current permissions. The
[firm menu reference](/docs/reference/business#firm-menu) explains navigation.

Use `/firm sales list`, `/firm sales summary` and `/firm sales export` for sales,
with live notifications grouped during busy periods. `/firm baltop` ranks combined
account balances. `/firm chat` provides an employee channel where CarbonChat is
installed. See [setting up a business](/docs/guides/setting-up-a-business) and the
[Business reference](/docs/reference/business).

Ownership and staff changes update account access, with background repair for
failed synchronization. Employment history is retained. Firm names remain reserved
after disbanding until staff explicitly release them. **There is no global name
namespace** across players, firms, account labels and aliases; account labels may
repeat. Use [account IDs](/docs/guides/business-accounts) to distinguish wallets.

## Exploring the economy

The [Explorer guide](/docs/guides/using-the-explorer) covers:

- Overview, Wealth, Money Flow and Taxation, including published personal tax
  settings and the distinction between estimated bridge volume and raw history.
- Full item-catalogue search, observed stock and remaining space, per-item offer
  comparisons, map/location links and historical price charts.
- Transaction counterparties and details for accounts you can read, including
  shared government accounts, without opening unrelated private histories.
- Local-time timestamps with UTC daily chart buckets, mobile directories,
  CSV/JSON exports and linked account dashboards.
- In-game password recovery for an existing linked account, where enabled.

For daily play, `/economy` shows statistics and `/sales` shows personal shop sales.
See the [Treasury reference](/docs/reference/treasury),
[tax guide](/docs/guides/tax) and [salary guide](/docs/guides/salaries).
Property parties, leases, offers and subregions are covered in the
[Realty guide](/docs/guides/property-realty) and [reference](/docs/reference/realty).

## Apps and plugin integrations

Business API keys can cover an entire firm or one linked account. Public market
and tax endpoints, account history/feed, idempotent transfers and issuer rate
limits are explained in the [API guide](/docs/guides/api); signed deliveries are
covered in [Webhooks](/docs/guides/webhooks).

Plugin authors can consume the new
[settled purchase event](/docs/guides/api#reacting-to-a-settled-shop-purchase).
It reports successful customer purchases; it does not install quests or guarantee
durable delivery.

If your server rotates its API signing secret during the release, existing tokens
stop working. Reissue personal or business keys in-game and update the apps using
them. Copy each new token when shown; it cannot be retrieved later. See
[TreasuryAPI commands](/docs/reference/treasury-api).
