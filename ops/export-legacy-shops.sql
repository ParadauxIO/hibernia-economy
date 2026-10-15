-- PAR-169: run against the LEGACY chestshop-database database, with writes stopped.
-- mariadb --default-character-set=utf8mb4 --batch --raw --skip-column-names legacy_db \
--   < ops/export-legacy-shops.sql > legacy-shops.jsonl
-- Copy to plugins/ChestShop/legacy-shops.jsonl; see wiki/shop-registry-backfill.md.
-- LEFT JOIN deliberately keeps missing Item rows: the importer rejects them instead
-- of silently dropping shops. JSON_OBJECT escapes embedded newlines into one line.
SET time_zone = '+00:00';
SELECT JSON_OBJECT(
    'world_uuid', CAST(s.world_uuid AS CHAR),
    'pos_x', s.pos_x, 'pos_y', s.pos_y, 'pos_z', s.pos_z,
    'owner_name', s.owner_name, 'item_bytes', TO_BASE64(i.item_bytes),
    'buy_price', s.buy_price, 'sell_price', s.sell_price,
    'quantity', s.quantity, 'stock', s.stock, 'estimated_capacity', s.estimated_capacity,
    'visible', s.visible, 'hologram', s.hologram,
    'last_updated', UNIX_TIMESTAMP(s.last_updated)
)
FROM Shop s LEFT JOIN Item i ON i.item_code = s.item_code
ORDER BY s.world_uuid, s.pos_x, s.pos_y, s.pos_z;
