# ChestShop hopper stock updates

PAR-523 adds a bounded, deferred refresh worker for shop-sign stock counters.
`USE_STOCK_COUNTER` remains false by default. PAR-544 makes the same refresh keep
`/find` stock current, and the worker runs for that alone when counters are off.

## Behavior

The move listener keeps its immediate live-world protection check at HIGHEST
priority. A separate MONITOR handler records source and destination container
coordinates for non-cancelled events. It does not count items, resolve signs,
write to the database or schedule a task per transfer.

The shared main-thread worker waits `HOPPER_STOCK_REFRESH_INTERVAL_TICKS` (100 ticks by default,
about five seconds at 20 TPS) from the first dirty event and reads
the current inventory. Repeated events do not postpone the refresh. Values below 1 are clamped to 1.
Reloading ChestShop applies the new interval to pending work based on its original
first-dirty tick, preserving queue order. Processing still runs every tick: a large
batch continues on subsequent ticks rather than waiting another full interval. It refreshes
all connected shop signs, including both halves of double chests, and writes only
changed counter text. Failed insertion and later cancellation therefore cannot
add phantom stock. Unlimited admin shops are skipped, as are counters when
`MAX_SHOP_AMOUNT` exceeds 99999.

## Finder stock

Each refreshed non-admin shop is also measured for the `/find` registry
(`current_stock` and `estimated_capacity`) by `ShopStockPublisher`:

- A reading equal to the last value written for that sign, by any path, writes nothing.
- Changed readings are coalesced per sign and written by one async batch at a time,
  so registry writes stay in order and never run on the server thread.
- Trade, chest-close, creation and resync writes stay synchronous. They report their
  value to the publisher, which drops queued hopper readings for that sign and makes
  an in-flight one skip its write. An older hopper reading therefore cannot replace a newer one.
- Wildcard shops have no registry row and are skipped. Plugin disable flushes
  queued writes synchronously.

This prototype uses a lazy index of pending container coordinates. It does **not**
cache positive or negative shop membership: each batch discovers signs afresh.
That keeps creation, removal, container splits and editor changes visible at
refresh time without a separate invalidation system. Ordinary sorter containers
still incur discovery once per batch. A durable shop-membership index remains an
option if profiling identifies that work as the bottleneck.

## Resource limits and recovery

- Normal pending work is deduplicated and capped at 4,096 container positions.
- Excess positions mark their loaded chunk for reconciliation. One queued entry
  per chunk preserves the final update even if transfers stop during overload.
- Each tick processes up to 32 normal positions and 32 recovery positions, with a
  separate 0.5 ms soft time budget for each phase. Recovery has its own allowance
  so sustained normal traffic cannot starve it.
- Recovery enumerates at most one chunk per tick and retains only its container
  coordinates. Chunk enumeration and an individual refresh cannot be preempted:
  **1 ms is a target, not a hard upper bound**. Dense chunks and expensive item
  matching must be included in load tests.
- Memory consists of the capped normal queue, deduplicated chunk coordinates,
  one chunk's recovery positions and work set aside at unload. Chunk unload keeps the
  positions it drops (up to 4,096 in total) and re-queues them when that chunk loads.
  Unload during overflow reconciliation, or past that cap, recounts the whole chunk
  on load instead. A chunk load on its own queues nothing, so exploration adds no
  work. World unload and plugin disable discard everything.
- No refresh intentionally loads an unloaded chunk. Counter freshness can exceed
  the configured interval under load and at low TPS. Existing loaded shops are refreshed on their
  next transfer or ordinary manual/trade update, not by a startup world scan.

## Verify locally

Run with Java 25 and the prerequisites in [Build & test](build-and-test.md):

```bash
bash gradlew :chestshop:check -Pci=true
bash ops/integration/run.sh --profile hopper
```

The hopper profile creates a disposable stack with counters enabled. It exercises
real incoming/outgoing hopper transfers for single and double chests, final sign
counts after transfers stop, item conservation, and the existing minecart theft
protection journey. Unit tests cover failed transfer predictions, multiple signs,
deduplication, paced work, overflow, lifecycle cleanup and live protection.

Local validation on 2026-10-24 passed all 1,641 ChestShop tests, the coverage gate,
the separate FAWE test and startup without editing APIs. All 20 batching tests
executed without skips. The disposable Paper 26.3 build 206 / Java 25 run passed
both single/double-chest transfer cases and the minecart-protection test. That
stack uses the diagnostic Nexo stub; it does not certify real custom-item plugins
or production load. The 10,000-event unit scenario demonstrates coalescing to one
container discovery, not a CPU or tick-time benchmark.

## Performance decision and enablement

Passing correctness tests is not a production performance result. Before enabling
this on a busy server, compare the disabled listener, the previous listener and
this worker on the same disposable world, hardware, Java/Paper versions and plugin
stack. Record the effective Paper hopper settings and ListenerControl rules.

Use repeated warmed runs with ordinary sorter networks, busy shops, full
destinations, minecarts and dense chunks large enough to overflow the normal
queue. Record workload sizes, transfer rate, p95/p99 tick time, allocation rate,
worker/profile time, counter convergence latency and variation between runs.
Choose acceptable tick-time overhead and refresh latency before judging the
results. Event dispatch and the immediate protection lookup still have costs;
the worker only bounds and coalesces deferred stock work.

For an approved rollout, enable `USE_STOCK_COUNTER`, ensure Paper's effective
`hopper.disable-move-event` is false, and remove the relevant ChestShop
InventoryMoveItemEvent exclusion from ListenerControl. Confirm that both the
protection and refresh handlers are registered, then repeat transfer and theft
checks in the actual plugin stack.

To stop deferred counter work, turn off `USE_STOCK_COUNTER` and reload ChestShop.
This also disables other stock-counter updates, but retains immediate protection
while the event listener remains registered. Disabling the whole listener also
removes its minecart protection. Preserve the previous configuration and plugin
artifact for a full rollback.
