# Settled ChestShop purchase API

`io.paradaux.chestshop.api.events.ChestShopPurchaseEvent` is a synchronous,
non-cancellable Bukkit `PlayerEvent` for a successful client **BUY**. It runs after
settlement returns successfully and before empty-shop cleanup, market recording,
logging, messages or the legacy transaction callback. Exceptions from those later
reactions therefore cannot suppress this notification. Publisher failures are
logged without interrupting subsequent post-trade reactions.

`getPlayer()` is the buyer. `getReceipt()` provides an immutable `PurchaseReceipt`
with `tradeId()` (the transaction's existing UUID), `buyerId()`, exact `price()`
and `items()` (material and positive actual quantity). Item metadata/custom item
identity, shop ownership and ledger linkage are not implied by this small API.
Neither the event nor its receipt exposes mutable inventory stacks or the mutable
transaction carrier. Free purchases are valid. SELL, cancelled settlement,
insufficient goods/funds and thrown settlement failures do not publish.

Listen to this **one** notification path for purchase objectives. Do not also
consume the Treasury sale-record event or legacy `TransactionEvent` for the same
objective. No Typewriter dependency is added to ChestShop. Consumers should use a
compile-only dependency and remain optional to the financial core.

The UUID is stable for repeated publication of the same transaction object and
different transaction objects receive different UUIDs. Publication is in-process,
not a durable outbox: it does not promise replay after a crash, globally exactly
once delivery, or exactly once rewards. Observer deduplication and quest-attempt
state belong to the consumer. Observer failure never rolls back settled funds.

For the separate ADT-215 Typewriter extension, check active quest criteria before
writing a player-scoped persistent purchased fact to `1`. Reset that fact before
acceptance of a new attempt. Boolean writes suppress repeated completion within
an attempt; they are not a cross-attempt receipt ledger. Purchases before quest
acceptance must not count. Reconnect persistence and return-to-NPC completion
need separate extension tests. Realty rentals and passing Exams require their own
verified adapters; this API does not claim those hooks.

This is source/API work only. No server installation, schema migration, rollout
or quest configuration is part of this change.
