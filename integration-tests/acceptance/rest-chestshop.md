# Public ChestShop REST assertion review (PAR-499)

`ShopDirectoryTest` covers authenticated GET `/api/v1/chestshop/shops` with
three Paper-created BUSINESS shops belonging to a fresh firm: dirt batches of
2 for 7 and 1 for 19, and stone batches of 3 for 11. The diagnostic fixture
fires the actual creation event; it is not a player sign-placement packet test.
A separate existing test covers the packet placement path.

The directory checks exact shop IDs/order, page/count metadata for two populated
pages and an empty page, material, item-key, search and combined buyable/inStock
filters. Row assertions include coordinates, type/firm identity/name, material,
item key, custom/admin flags, price, batch quantity and stock. An actual player
purchase buys two dirt for 7: chest stock and public stock become 22, buyer
balance 9993, firm balance 7, proprietor 10000 and exactly one sale row. The
fresh firm filter isolates fixtures from other tests' shops.

This is partial route acceptance. Later sections cover sold-out/non-buyable/
unlimited-admin/inactive filters, validation and aggregate routes. Remaining
gaps include hidden-shop semantics, firm rename/archive, finite admin-shop
discoverability and complete timestamp assertions. Personal owner mapping and
ordinary-chest space are covered below. Item key is checked against the persisted registration;
that proves HTTP mapping, not independent item-key encoding. See `results.md`.

## Sold-out ordering and inactive shop exclusion

A second `ShopDirectoryTest` case creates an empty dirt shop priced 7 and a
stocked dirt shop priced 11 with batch/stock 64. Availability must sort the latter
first despite its higher price; inStock excludes the empty shop, while buyable
retains both offered buy prices. One real purchase empties the stocked shop.
Now inStock returns zero and buyable retains both, sorted by price.

A real sign-break packet then deactivates the cheaper shop. The public directory
and buyable view must exclude it while still retaining the active sold-out shop;
inStock remains empty. Sign removal and reads leave the ledger unchanged after
the purchase. Buyer holds 64 dirt and balance 9989, firm 11, proprietor 10000;
exactly one sale is recorded. Creation uses the diagnostic real-event fixture,
while purchase/removal use player packets. Non-buyable, admin and hidden-shop
semantics still require their own cases.

## Public parameter boundaries

`MarketApiValidationTest` covers shops/items directory page/limit/search bounds:
page zero/negative, overflowing maximum-int page at limit 100, limits 0/-1/101,
and search lengths 1/101 return 400 INVALID_PARAM. Limits 1/100, search lengths
2/100 and whitespace-only search are accepted. Maximum-int page at limit 1 is
representable and returns an empty 200 page preserving that page number.

Shop filters enforce material length 64 and item-key length 190. Detail requests
enforce days 1..365 and key length 190: invalid windows/length return INVALID_PARAM,
while accepted boundaries for a never-seen item return 404 ITEM_NOT_FOUND.
The ledger checkpoint remains unchanged. These are validation checks, not
populated items/detail aggregation coverage. HTTP binding errors, encoded path
edge cases, per-owner throttling and cache-validation interactions remain gaps.

## Populated items, item detail and global stats

`MarketAggregateTest` uses RAW_GOLD_BLOCK, reserved to this test in the current
suite, and asserts no earlier sales for that material. Two real business-shop
purchases (2 units for 7, 3 for 11) independently define expected totals: two
trades, five units, volume 18 and weighted average unit price 3.6. Public detail
checks these values, window 30, two active shops, exact cheapest-shop order and
remaining stock 22/33. Its daily series checks the same totals against the
recorded trade date. Item-directory search returns the one exact item and totals.

Global stats are compared to aggregate DB counts/volume across the shared test
stack; this checks HTTP aggregation/mapping rather than independently deriving
all earlier tests' sales. A bounded one-second poll accommodates an existing
60-second stats cache without exhausting the authenticated request budget. The
selected clean runs need not exercise cache expiry. Exact final buyer/firm/owner
balances are 9982/18/10000, buyer inventory five and customer sale count two.
Sell-direction, tax/net-versus-gross semantics, old-window exclusions, no-sales
live items, multi-day series, item ranking/pagination and cache refresh behavior
remain separate gaps.

## Historical windows and multi-day prices

`MarketWindowTest` creates two real RAW_IRON_BLOCK trades (2 units for 7 and
3 for 11), then backdates only the first persisted sale timestamp by 40 days.
This explicit historical-data fixture does not change ledger or inventory.
The 30-day detail must contain only the recent trade; 90-day detail must include
both with an ordered two-day series. The lifetime item directory retains both.
Reading 30 days again after 90 days must preserve its original response, checking
window separation in the cache. Balances/inventory remain those of the real
payments, and HTTP reads do not advance the ledger checkpoint. Exact cutoff
instants, timezone transitions and sale ingestion timestamp generation remain
outside this fixture's coverage.

Current response precision is characterized separately: summary average 11/3 is
3.6667 (four places), while the SQL-derived daily average is 3.666667 (six places).
The test asserts both independently calculated values; it does not define a new
uniform precision requirement.

## Quiet windows and historical-only items

The window test also backdates its second real sale by ten days, leaving the
one-day window empty. Detail still returns 200 with zero trades/quantity/volume/
average and an empty price series, while retaining material identity and two
live shop offers. Both signs are then removed by real player packets. A fresh
two-day window returns the same zero history with activeShopCount zero and no
cheapest shops, rather than ITEM_NOT_FOUND. A fresh 365-day window retains both
historical trades even after shop removal. Different windows avoid existing
cached shop lists; cache invalidation/expiry is not established. Unknown-item
404 behavior is covered by the neighboring validation control.

## Customer sells to a business shop

`MarketSellDirectionTest` funds a real firm through `/business deposit` and uses
an actual customer left-click to sell four raw copper blocks for 13. It asserts
SELL attribution to the firm, one sale, unit price 3.25, customer balance 10013,
firm balance 87, proprietor balance 9900, empty customer inventory and four items
in the chest. The sign is a direct fixture, so this is not shop-creation coverage.

The public directory must show a null buy price, sell price 13.00 and stock four.
The stock filter retains that row; the buyable filter excludes it, including when
combined with stock. Item detail and the item directory include one trade, four
items and volume 13; detail retains one active shop but no cheapest customer-buy
offers. HTTP reads must not advance the ledger checkpoint. This detects direction
inversion in filtering or aggregates, as well as incorrect business payout routing.

## First trade and warmed-cache refresh

`MarketFirstTradeTest` creates a live business shop for a reserved, never-traded
material through the diagnostic creation event. Before any trade, item detail
returns 200 with its item key, zero aggregates/average, empty daily series and
one stocked offer. The traded-item directory is empty for that material.

A real customer then buys two items for 9. Both identical, previously warmed
URLs must converge within 60 seconds to one trade, quantity two and volume nine.
Detail must also show average 4.5, one daily point and offer stock 22; the directory
must acquire the item. Polling is spaced 1.5 seconds apart to respect authenticated
rate limits. This accepts immediate invalidation as well as TTL refresh; it does
not require stale data or certify an exact expiration instant. It detects caches
that never refresh and aggregate endpoints that fail to acquire a first trade.
Balances, inventory, one recorded sale and an unchanged post-trade ledger
checkpoint guard against unintended effects from repeated reads. Pre-trade
material/name metadata is not certified here: the current aggregate query derives
those labels from sales, while item-key identity and offer identity are checked.

## Unlimited admin-shop offers

`MarketAdminShopTest` explicitly enables unlimited admin shops, empties an admin
shop chest and buys three cut copper for 12 with a real player packet. It checks
the buyer's balance 9988, three received items, the still-empty chest, exactly one
BUY sale with admin attribution and no business/personal owner, and unit price
four. The test restores the default unlimited setting after execution.

The public directory must retain this shop under inStock and combined buyable/
inStock filters, with explicit null stock/space/owner metadata and the exact
price/batch. Detail's cheapest offers must also include it. Detail and the item
directory must include one trade, quantity three and volume 12. Repeated reads
must not advance the ledger checkpoint. This protects the null-as-unlimited stock
contract from accidental sold-out filtering. Sign setup is direct; shop creation,
finite admin-shop sold-out discoverability and config reload are not certified by
this case. Existing neighboring admin tests exercise chest-backed buy/sell and
unlimited transactions separately.


## Populated item ranking and pagination

`MarketItemPaginationTest` creates three real business shops for white, blue and
red glazed terracotta. Actual customer purchases produce two white trades (four
items, volume six), one blue trade (one item, volume 100) and one red trade (three
items, volume seven). The directory's count-first, name-second ranking must be
white, blue, red, despite blue having the greatest volume and red trading first.
This characterizes the existing documented query ordering, not volume ranking.

Page size two must yield two rows, one row, then an empty page with consistent
total counts. Page size one must yield the same order over three pages. A spaced
display-name search isolates blue; revisiting the initial page after those queries
checks cache-key separation. Exact item aggregates, buyer balance 9887, firm
balance 113, owner balance 10000, purchased inventories and four recorded sales
are checked. Reads leave the post-trade ledger checkpoint unchanged. All three
materials are reserved for this case; creation uses diagnostic creation events
and trading uses real player packets. Concurrent paging and tied custom-item
names remain outside this case.

The pagination test also retains an active failing vanilla/custom classification
assertion: ordinary white glazed terracotta currently returns itemCustom=true.
Its long name is shortened for a sign, and the market classifier treats the
mismatch with its full name as custom. See `known-defects.md`; pagination assertions
run before this regression, and this case must not be reported as fully passing.

## Distinct personal owners and container space

`MarketPersonalOwnerTest` creates two personal shops for the same material through
real diagnostic creation events. One customer buys two calcite for seven from
the first and three for eleven from the second. Both sale rows must attribute
the correct PERSONAL UUID. Directory and detail offers must preserve each shop's
ID, distinct owner UUID/name, null firm, non-admin flag, price, batch and stock.
Exact remaining capacity is checked against an ordinary 27-slot chest: 1706 and
1695 additional stackable items respectively.

Balances must be 10007 and 10011 for the owners and 9982 for the customer, with
five purchased items and chest stocks 22/33. Two recorded sales and an unchanged
post-trade ledger checkpoint protect repeated HTTP reads. This catches incorrect
owner joins and payout routing; player renames, offline-name updates and personal
account reassignment remain separate coverage concerns.

## Firm rename and released-name reuse

`MarketFirmRenameTest` buys two tuff for seven from a business shop, renames its
firm through the real admin command, then recreates a separate firm under the
released old name. The original public directory row must immediately show the
new name while retaining its shop ID, firm ID, BUSINESS type, price and batch.
The replacement firm's directory must remain empty. Rename and its HTTP reads
must not advance the post-purchase ledger checkpoint.

A second real purchase from the unchanged account-bound sign must credit the
original firm again: original balance 14, replacement zero, owner 10000, buyer
9986, customer inventory four and chest stock 20. The latest sale must still name
the original firm ID, and that firm's default account must remain unchanged.
This tests directory joins and account-bound trading across name reuse.
Archive/disband and name-bound legacy signs remain outside this case.

The test also warms the item-detail URL before rename, verifying the original
owner name and one trade. After rename, released-name reuse and the second
purchase, it polls that identical URL at 1.5-second intervals for up to 60 seconds.
The sole offer must retain its shop/firm identity while acquiring the renamed
owner and stock 20, with two trades, four items and volume 14. The post-trade
ledger checkpoint stays unchanged. This checks eventual cached-label and stock
refresh; immediate invalidation is allowed and exact TTL timing is not claimed.

## `/find` visibility versus active trading

`MarketVisibilityTest` hides a personal basalt shop with the real visibility
command, buys two items for nine through a customer packet, and shows the shop
again. The shop remains active with a stable ID, the hidden preference survives
the trade, stock falls from 24 to 22, balances become 10009/9991 and exactly one
sale is recorded. Toggles and reads do not create ledger transactions.

The public HTTP directory retains the offer throughout hide/trade/show, including
buyable/inStock filtering. This is explicitly characterization: the command's
help, handler documentation and reply scope visibility to `/find`; the HTTP query
filters active state but not visible state. It is not a privacy guarantee or a
requirement that future HTTP policy must retain this behavior. Neighboring
`FindDialogTest` cases independently prove hidden exclusion and re-show inclusion
for both find aliases. A hidden shop must not be treated as deactivated or unable
to trade merely because it is absent from `/find`.

## API authentication

All `/api/v1/chestshop/**` requests require an active Treasury API key in
`Authorization: Bearer <jwt>`. PERSONAL, BUSINESS, GOVERNMENT and SERVICE keys
can read these endpoints. Missing, malformed, expired, revoked or superseded keys
are rejected with HTTP 401. The default budget is 120 requests/minute per owner
and route (600 for BUSINESS), subject to configured owner overrides.

`RouteSecurityIT` checks the authentication wall across all four routes. Market
E2E tests use a fresh SERVICE key per test and revoke it afterward. The explorer's
market pages read the database directly and do not require client changes.
