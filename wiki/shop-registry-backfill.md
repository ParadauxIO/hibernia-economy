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
   subsequent live observations; this scans loaded chunks only.

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
