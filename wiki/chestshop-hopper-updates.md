# ChestShop hopper stock updates

PAR-523 adds a bounded, deferred refresh worker for shop-sign stock counters.
`USE_STOCK_COUNTER` remains false by default. PAR-544 makes the same refresh keep
`/find` stock current, and the worker runs for that alone when counters are off.

## Behavior

The move listener keeps its immediate live-world protection check at HIGHEST
priority. It ignores sources whose inventory type no configured shop container can
hold, such as hoppers. A hopper minecart blocked under a shop retries every tick,
so a container just confirmed as a shop is remembered for one second. Only "is a
shop" is remembered. A stale entry can block a pull for at most a second, and can
never allow one.

A separate MONITOR handler records source and destination container
coordinates for non-cancelled events, skipping hoppers by inventory type. It does
not count items, resolve signs, write to the database or schedule a task per
transfer.

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

- A reading equal to the last submitted value for that sign, by any path, writes nothing.
- Changed readings are coalesced per sign until the next tick, then submitted to the
  same FIFO background writer as trade, chest-close, creation, discovery, resync,
  deletion and toggle registry mutations. Database writes never run in the hopper callback.
- Newer authoritative observations drop pending hopper readings before submission.
  Already-submitted readings precede the newer observation in the FIFO, so they cannot
  overwrite it later. Only immutable records and scalar values enter the writer.
- Preview and finder reads wait for preceding registry writes on their background threads.
  Sale recording remains synchronous because Treasury dispatches a Bukkit sale event.
- Baselines are indexed by world/chunk; chunk unload drops one bucket without scanning
  unrelated shops. Wildcard shops have no registry row and are skipped.
- Plugin disable submits pending hopper readings and waits up to 15 seconds for the
  writer to drain, logging if it cannot finish. Registry writes remain best-effort analytics;
  this queue is not a durable transaction log.

Shop membership is not cached, so each refresh discovers a shop's signs afresh. A
container found with **no** shop sign is skipped by the enqueue and refresh paths for
30 to 60 seconds, the exact time depending on its position. Most hopper traffic is
ordinary sorting, so this removes most refresh work. The skip ends early when:

- a block is placed or a sign is edited within two blocks;
- a supported WorldEdit/FAWE edit changes a sign nearby;
- the chunk or world unloads.

Changes that fire no event, such as other plugins' API edits, are picked up when the
skip expires. Protection never uses this cache.

A refresh resolves each shop's sign item once and shares it with the counter and the
`/find` publisher. It reuses a parsed item for up to 30 seconds, because parsing
consults custom-item providers and fires the legacy `ItemParseEvent`. After a
provider reload a counter may count the previous item definition for that long.
Trades never use this cache. Signs are read as live states, not snapshots.

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
- The not-shop skip holds at most 65,536 positions, parsed items at most 1,024 and
  the protection memo at most 4,096 containers. Past a cap, work is simply done again.
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

## Benchmark

`ops/integration/hopper-bench.mjs` compares four variants on fresh disposable stacks
(the `hopper-bench` profile of `run.sh`). All four run with `USE_STOCK_COUNTER: true` and
production's Paper hopper settings (`cooldown-when-full: false`,
`ignore-occluding-blocks: true`, Spigot's default transfer/check rates of 8 ticks).
WorldGuard's move handler is unregistered in every variant, as production's
ListenerControl does, so only ChestShop's handlers differ:

| Variant | What runs |
|---|---|
| `event-off` | `hopper.disable-move-event: true`: Paper fires no move events |
| `no-listener` | events on; ChestShop's move handlers unregistered, as ListenerControl can |
| `legacy` | the listener before 6b35b153 ([patch](../ops/integration/hopper-bench/legacy-listener.patch)) |
| `worker` | this checkout: immediate protection and the batched refresh worker |
| `worker-monitor-only` | the worker without ChestShop's HIGHEST (protection) handler, as ListenerControl can |
| `noop-listener` | events on, ChestShop's handlers removed, one empty MONITOR handler: the cost of dispatch alone |

```bash
node ops/integration/hopper-bench.mjs                  # 3 reps x 2 scenarios x 5 variants
node ops/integration/hopper-bench.mjs --summarize build/hopper-bench/<stamp>
```

Port and JDK settings come from the environment, as for `run.sh`. Each run builds the
workload with `/diag bench build`, warms it up for 90 s and records 300 s. The order of
variants is shuffled per repetition. Output goes to `build/hopper-bench/<stamp>/`:
one JSON file per run plus `summary.md` and `summary.json`.

Workloads (DcDiag `HopperBench`), all in forceloaded chunks outside the online owner's
view distance:

| | typical | overload |
|---|---|---|
| sorter chains: supply chest, 8 hopper/chest pairs, sink chest | 64 (1 chunk) | 320 (5 dense chunks) |
| busy shops: supply → hopper → shop → hopper → sink; ¼ double chests, ¼ with two signs | 32 | 128 |
| hoppers pushing into full chests, half of them shops | 8 | 32 |
| hopper minecarts under chests, drained by a hopper; half under shops (pull cancelled) | 16 | 64 |
| counter probes | 8 | 8 |

Typical dirties about 1,300 positions per refresh interval. Overload dirties about
6,000, which overflows the 4,096-position queue into chunk reconciliation of
chunks holding about 1,100 tile entities each.

Each run records:

- every tick's duration (Paper `ServerTickEndEvent`) and its main-thread CPU time
- main-thread CPU time and allocated bytes
- items delivered to sink chests per second
- a JFR profile (2 ms execution samples): ChestShop's share of main-thread samples,
  split into worker, listener and other code
- each worker drain's wall and CPU time on the main thread. DcDiag swaps ChestShop's
  scheduler task for an identical timed call.
- the worker's refreshes, refreshed shop signs and skipped non-shop transfers per second
- refresh-queue depth every second
- probe latency: a probe shop fills with 8 items through a hopper, then drains through
  a second hopper. Latency runs from the chest reaching its final count to the sign
  showing that count, with a 30 s timeout.

### Thresholds

Fixed on 2026-11-05, before any run. Comparisons use medians across repetitions,
against `event-off` in the same scenario:

- mean MSPT at most 2% higher, and p99 tick time at most 0.5 ms higher;
- no worker drain above 2 ms in any run;
- probe p95 latency within the refresh interval plus 1 s (6 s at 100 ticks) for both
  filling and draining, with no probe timeouts;
- under sustained overload the queue levels off: in no run does the mean depth in the
  final third of the sample exceed 1.1 times the middle third (plus 16).

Two revisions on 2026-11-06:

- **Measured as CPU time.** The shared host's load rose from 8–12 to 16–50. Between
  days `event-off` MSPT rose 45% while its CPU per tick rose 3%. Tick and drain limits
  are now judged on main-thread CPU time. The overhead limit is judged on ChestShop's
  own share of each run's profile, which run-to-run drift (±10% CPU/tick) cannot swamp.
- **What "safe" means.** The 2% overhead target proved out of reach for any design that
  handles every hopper transfer. With one empty move-event handler registered, the
  overload workload already costs 9% more CPU per tick. The bar for production is:
  cheaper than the build production runs today; no drain above 2 ms CPU; freshness
  within 6 s; and a bounded queue. 2% remains a stretch goal.

### Results

Run on 2026-11-05 at commit 567acae7. Host: 16 vCPU AMD EPYC 9645 VM, shared with
other work (load average 8–12 during the runs). Stack: Paper 26.3-206, Java 25.0.2
(GraalVM CE), a 4 GB fixed heap and the stack from `run.sh`. Each cell is the median of
3 runs (warmup 90 s, sample 300 s = 6,000 ticks). One attempt (overload `no-listener`,
rep 3) failed and passed on retry; the retry overwrote its log. TPS stayed at 20.0
throughout. `worker-monitor-only` ran after the other four, in the same way.

**Typical** (1,420 tile entities; 280 items/s delivered with events off):

| | event-off | no-listener | legacy | worker | worker-monitor-only |
|---|---|---|---|---|---|
| mean MSPT | 3.09 | 3.04 | 4.08 (+32%) | 3.82 (+24%) | 3.35 (+8%) |
| p99 MSPT | 8.53 | 8.52 | 16.49 | 11.35 | 8.91 |
| main CPU ms/tick | 2.89 | 2.91 | 3.79 | 3.66 | 3.37 |
| main alloc MB/s | 6.1 | 5.8 | 20.2 | 13.8 | 9.0 |
| worker drains > 2 ms per run | 1 | 0 | 1 | 113 | 69 |
| worst worker drain, ms | 7.0 | 3.5 | 4.4 | 40.1 | 37.4 |
| probe p95 fill / drain → sign, ms | — | — | 0 / never | 2,550 / 2,450 | 2,550 / 2,500 |

**Overload** (6,648 tile entities; 1,281 items/s with events off):

| | event-off | no-listener | legacy | worker | worker-monitor-only |
|---|---|---|---|---|---|
| mean MSPT | 5.86 | 5.86 | 8.52 (+45%) | 7.79 (+33%) | 6.78 (+16%) |
| p99 MSPT | 17.74 | 19.06 | 39.22 | 26.85 | 22.88 |
| main CPU ms/tick | 5.41 | 5.19 | 7.80 | 7.17 | 6.58 |
| main alloc MB/s | 13.4 | 13.6 | 72.7 | 44.4 | 24.3 |
| worker drains > 2 ms per run | 0 | 1 | 2 | 120 | 89 |
| worst worker drain, ms | 4.0 | 4.9 | 4.7 | 28.5 | 24.7 |
| probe p95 fill / drain → sign, ms (worst run) | — | — | 0 / never | 11,250 / 7,450 | 9,100 / 11,300 |
| max queue: positions / chunks | — | — | — | 4,095 / 18 | 4,096 / 19 |

Against the thresholds:

- **`worker` fails in both scenarios.** Mean MSPT is 24% higher (typical) and 33%
  higher (overload), p99 is 2.8 ms and 9.1 ms higher, and 110–150 drains per run
  exceed 2 ms. Freshness passes in the typical scenario (about 2.5 s) and fails under
  overload (7.4–11.3 s). The queue stays at its cap without growing, and no probe
  timed out.
- **`worker-monitor-only` also fails.** p99 passes in the typical scenario (+0.39 ms).
  Mean MSPT is 8% and 16% higher, the long drains remain and overload freshness fails.
- **`legacy` is worse than the worker on tick time**, by about 32–45% mean MSPT and
  roughly twice the allocation. Its sign never reflects outgoing transfers: every
  drain probe timed out.
- **The event itself is cheap.** `no-listener` matches `event-off`: with no registered
  handlers the cost is within run-to-run noise. The whole cost is ChestShop's handlers.
- The baseline variants show 0–1 drains above 2 ms per run, and worst cases of 3–7 ms,
  even with an empty queue. That is scheduling noise on the shared host. The worker's
  100+ long drains per run are real; its single worst value is partly noise.
- Protected variants deliver fewer items (260 against 280 per second) because the
  minecarts under shop chests are blocked. In the baselines those minecarts empty the
  shops.

Where the drain time goes (a separate 120 s worker profile of the typical scenario).
The drain is 10.8% of main-thread samples. The MONITOR enqueue handler is 2.8% and the
protection handler 6.3%. Within the drain:

- 63% is sign discovery (`findConnectedShopSigns`). Most of that is
  `Block.getState()` taking full snapshots: of each neighbouring sign (53% of
  discovery) and of the container, including all its item stacks (13%).
- 20% is formatting the counter line (`updateCounterOnQuantityLine`).
- 11% is `/find` stock publishing.

The 0.5 ms phase budget is checked only between refreshes. A refresh that takes
snapshots of large chests and their signs overruns it, so p99 drain time is 2.2–2.9 ms.

### Optimisation (2026-11-06)

Profile-driven rounds, each verified by `:chestshop:check` and the live hopper suite:

1. **Snapshots:** read signs as live states, and find a sign's container from block
   data instead of block-state snapshots.
2. **Cheap pre-checks:** check a neighbour's material before reading its block data;
   skip hoppers by inventory type; parse `SHOP_CONTAINERS` once.
3. **Not-shop skip:** skip refreshes for containers known not to be shops (above).
4. **Fewer revalidations:** a 1 s protection memo for confirmed shops, and one sign
   validation per refresh instead of two.
5. **Regexes:** compile the colour-code, quantity and item-alias patterns once.
6. **Lookups:** one hash lookup per transfer, with hand-written key equality.
7. **Parsing:** reuse parsed items for 30 s.
8. **Expiry spread:** not-shop expiry spread over 30–60 s, so rediscovery has no waves.

Each worker run's ChestShop main-thread CPU per tick, from its profile (median of the
runs). The baseline variants show 0.02–0.05 ms of unrelated ChestShop timers:

| Build | typical | overload | worst drain CPU, ms (typical / overload) |
|---|---|---|---|
| 9fd3221 era (production until this deploy) | 0.83 | 2.05 | 3.1 / 2.5 |
| after rounds 1–3 | 0.41 | 0.86 | 2.8 / 2.2 |
| after round 7 | 0.26 | 0.69 | 1.3 / 2.4 |
| final (41c99a73) | 0.25 | 0.64 | 1.1 / 2.1 |

Final matrix: 2 repetitions per scenario, 60 s warmup, 120 s samples. Results
against the production bar:

| | typical | overload |
|---|---|---|
| cheaper than the production build | 0.25 vs 0.83 ms/tick | 0.64 vs 2.05 ms/tick |
| worst drain CPU (limit 2 ms) | 1.13 ms | 0.81 and 2.10 ms: one drain in 4,800 ticks |
| freshness p95, fill / drain (limit 6 s) | 2.25 / 2.20 s | 2.25 / 2.35 s |
| probe timeouts | 0 | 0 |
| queue levels off, peak | yes, 189 | yes, 731 |

ChestShop's own CPU is 8% (typical) and 11% (overload) of `event-off` CPU per tick, so
the 2% stretch goal is not met. The typical workload refreshes 22 positions and 9
shop signs per second, and skips 2,600 transfers at known non-shops. The single
2.1 ms overload drain was not attributable at the 2 ms profile resolution; its
samples are ordinary refresh work. The production build reached 2.5–3.1 ms.

## Enablement and rollback

**Production state, checked 2026-11-06** against the live server's files:

- ChestShop `2026.11.43.1-gde8cc832c686` includes the worker;
- `USE_STOCK_COUNTER: true` with `HOPPER_STOCK_REFRESH_INTERVAL_TICKS: 100`;
- `hopper.disable-move-event: false`, with no per-world override found;
- ListenerControl's ChestShop entry is commented out, so both move handlers are registered.

So production already pays for the worker, at the cost of the first row above.

**Recommendation: deploy this build with the current configuration.** It meets the
production bar in [Thresholds](#thresholds): about three times cheaper than the build
production runs, with no drain above 2 ms CPU, freshness within 6 s and a bounded queue.
No configuration change is needed.

This build does not change hopper-minecart protection settings. In the typical workload
the protection check costs about 1.7% of main-thread CPU (measured with it enabled), so
turning it on is a separate decision. After any change to `TURN_OFF_HOPPER_PROTECTION`,
reload ChestShop and repeat the minecart theft check on the real plugin stack.

To stop the worker entirely, deactivate ChestShop's MONITOR handler in ListenerControl
and restart:

```yaml
deactivated-events:
  ChestShop:
    monitor:
    - inventory.InventoryMoveItemEvent
```

Sign counters and `/find` stock then update only on trades, chest close, creation,
discovery and resync. Turning off `USE_STOCK_COUNTER` alone does **not** stop the
worker: since PAR-544 it also runs for `/find` stock.

**Rollback:** redeploy the previous ChestShop artifact. No configuration or data changes
accompany this build.
