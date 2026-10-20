# Legacy shop registry backfill (PAR-169)

`/chestshop importlegacy` imports the old chestshop-database `Shop` registry,
including shops in unloaded chunks. It requires `ChestShop.admin` and works from
console. The source is a JSON-lines export, so ChestShop needs no legacy database
credentials or direct connection to another plugin's database.

## Cutover

1. Back up both databases and stop legacy shop writes. Apply economy Flyway
   migrations (V25 or later), and deploy Treasury and ChestShop from this release
   together. Keep trading/resync paused until verification finishes.
2. Export **from the legacy database** using the MariaDB client:

   ```sh
   mariadb --default-character-set=utf8mb4 --batch --raw --skip-column-names legacy_db \
     < ops/export-legacy-shops.sql > legacy-shops.jsonl
   ```

   Use your normal client credentials configuration. Check the command succeeded
   and the line count equals `SELECT COUNT(*) FROM Shop`. Do not use the client's
   default escaped/tabular output. The export includes `Item.item_bytes`, not just
   a material name: custom items retain their metadata through Paper decoding and
   the current ChestShop item identity builder.
3. Put the file at `plugins/ChestShop/legacy-shops.jsonl`. Load every source world
   with its original UUID (individual chunks need not be loaded). Keep ChestShop's
   `users.db` name mappings, Treasury accounts, Business firms/account links, and
   custom item providers available. The admin-shop name must match the old config.
4. Run `chestshop importlegacy`. File reading happens asynchronously; one row is
   resolved and written per scheduled server task. Memory is bounded and no chunks
   are forced to load. The result reports accepted rows (including already indexed
   locations) and rejected export lines. Lookup/decoding/write failures stop at the
   failing line with the cause in the server log. Fix the cause and rerun the file.
   **A run with rejected rows or a stop is incomplete.**
5. Verify counts and contents before reopening trading. On a clean destination,
   `SELECT COUNT(*) FROM chestshop_shop WHERE active=1` must equal the source count.
   Compare location sets using `HEX(world_uuid)` and sign coordinates against source
   UUIDs with dashes removed; check prices, batch quantity, stock, remaining capacity,
   visibility and hologram flags. Owner names become resolved Treasury account IDs,
   types, personal UUIDs and Business firm IDs; world UUIDs become current names.
   Capacity `-1` becomes NULL; admin stock/capacity become NULL (infinite).
   `stock_at` and `last_seen` retain `last_updated`, rather than claiming a fresh scan.
   If the source timestamp is NULL, `stock_at` stays NULL and `last_seen` is import time.
6. Run the same import again and compare a sorted dump of `chestshop_shop` with the
   previous one. IDs, flags, data and timestamps must be unchanged. Archive/remove
   the export after verification. Run `/chestshop resync <chunks>` as needed for
   subsequent live observations; this works from console or in-game and scans loaded chunks only.

For player preview preferences and the remaining add-on upgrade steps, see
[ChestShop add-on compatibility and cutover](chestshop-add-on-compatibility.md).

## Existing destination rows

The importer inserts missing locations through `MarketApi.importShop`; it never
updates an existing row, including inactive tombstones. This prevents an old
snapshot overwriting newer observations, owner toggles or post-cutover removals.
Existing-row differences must therefore be reconciled during cutover verification:
count equality alone is insufficient on a populated destination. The importer does
not delete extra destination shops. If an old row truly needs restoring, use a live
resync after checking the sign, rather than replaying a stale snapshot.

The insert is atomic on `uq_shop_location` and propagates database failures; it does
not use `INSERT IGNORE`. Each successful row commits separately so a failed import
can resume by rerunning. MySQL/MariaDB may consume auto-increment values on duplicate
attempts; no shop rows change. No ledger balances or transactions are modified.

`MarketApi.upsertShop` remains the fail-soft live observation API. Repeated bulk
scans retain one row/ID per location, explicitly refresh `last_seen`, set `stock_at`
for measured stock and clear it for unknown/infinite stock. Owner flags survive
refreshes of active rows; a revived tombstone gets new-shop defaults.

## Item fidelity and world identity (PAR-171)

Registry `item_data` is Bukkit YAML with an `item` key, not Base64. Plain vanilla
items can be reconstructed from `material`; `item_name` is a search/display label,
not a replacement ItemStack display name. Metadata-bearing vanilla items (including
names, enchantments, damage and potions) are classified as custom by `hasItemMeta`
and retain serialized data even without a custom-item provider. The capture policy
therefore remains unchanged. Serialization failure still leaves NULL data; it is
not a guarantee of exact reconstruction for damaged or incomplete historic rows.

`/find` result bodies and floating previews use the same decoder: prefer stored
YAML regardless of the custom flag, fall back to material for missing/invalid data,
and omit the icon if neither is usable. Result selection remains available. Tests
cover vanilla fallback, named/enchanted/damaged swords, potions and malformed rows.

The world identity decision is **UUID alongside name**, already implemented by V25:
`world_uuid BINARY(16)` is nullable for older callers/rows, captured from the live
world and exposed through `ShopResult`. No duplicate migration is needed. Names
remain the location unique key and `/find` filter/distance basis; UUID storage does
not automatically reconcile a world rename. Before renaming, pause registry writes,
back up the registry, reconcile old-name rows (including location collisions) and
verify UUID/location sets before resuming. Resync alone can create new-name rows
while leaving old-name rows behind. Unloaded worlds cannot be resolved by the
legacy importer until loaded, even though their UUID remains stored. Cross-server
world identity and automatic rename reconciliation remain outside this contract.

## Repairing an existing analytics registry (PAR-496)

An existing `chestshop_shop` table is not proof of a completed legacy backfill.
Older trackers indexed activity after installation and retained destroyed-shop
rows. Compare location sets and observation dates, separating active shops from
inactive tombstones and accounting for different snapshot ages.

New shops are indexed from the creation event's prepared text: during a sign
change the block-state snapshot can still contain blank or previous text. Reading
that snapshot used to record the previous quantity/prices, sometimes a quantity
of one and no offered price. Restocking now rebuilds the full record from the live
sign instead of updating only stock. This discovers previously unindexed shops
and refreshes quantity, prices, UUID, stock and capacity while preserving owner
visibility/hologram choices on an existing live shop in the same world.

ChestShop also queues chunks already loaded at enable and each subsequent chunk
load for reconciliation. There is one database lookup/scan in flight, with at
least one tick between chunks. It reads the registry asynchronously, then scans
only that still-loaded chunk on the server thread. Valid signs are upserted;
previously indexed locations without a valid sign become inactive. A failed
lookup, unloaded chunk, or replaced world does not establish deletion. A valid
sign whose owner or item cannot be resolved is retained for a later attempt.
No chunks are forced to load. `/chestshop resync <chunks>` remains the explicit
loaded-chunk repair command; automatic discovery does not replace imports for
shops in chunks that have never loaded.

### World replacement and defaults

World names can be reused after a reset. Do not assign an unmatched old UUID to
the current same-named world or import old-world records into a replacement.
Search and floating previews reject a recorded UUID that differs from the current
world. A preview lookup started before replacement is discarded when it returns.
Live upserts with an explicitly changed UUID reset the old shop's visibility and
hologram flags to new-shop defaults; same-world upserts preserve them. A caller
without a UUID cannot erase a known identity.

Pre-V25 rows have NULL UUID/capacity, default-visible/default-hologram flags, and
no player preference migration. A NULL UUID remains compatible with legacy rows;
it does **not** prove which world generation produced that row. Physical
reconciliation captures the current UUID only for an observed shop. Audit unknown
or unloaded rows separately, and retain rollback snapshots. Do not fabricate a
UUID migration or delete a whole world based on name/age alone.

### Cutover order

1. Back up native registry/preferences and freeze/export the legacy source as
   described above. Record snapshot dates and inventory old-world UUIDs.
2. Deploy the creation/discovery repair, then reconcile loaded chunks. Verify real
   signs' batch quantities and prices, including `Q ... : C ...` quantity lines.
3. Import missing shops from known, original source worlds and import player
   preferences with `/chestshop importpreferences` as described in the add-on
   cutover guide. Both importers preserve existing native choices/tombstones.
4. Reconcile existing-row differences using live signs; an insert-only replay
   deliberately cannot overwrite stale existing metadata or restore legacy
   visibility/hologram choices over native choices. Resolve such conflicts
   explicitly rather than bulk resetting owner settings.
5. Verify location sets, flags, item fidelity, rejected import lines and unresolved
   worlds. Unloaded/unresolved records remain pending; raw row-count equality is
   not a cutover acceptance criterion.
