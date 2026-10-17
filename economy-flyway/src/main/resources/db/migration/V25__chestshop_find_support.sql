-- /find support for the live shop registry (PAR-166 epic: PAR-5/17/167/168/170/171).
--
-- The in-ChestShop `/find` shop-search reads chestshop_shop through a new in-process
-- read API (ShopQueryApi) and needs a few columns the V6 registry never carried.
-- Additive only — every column is nullable or defaulted so existing rows and the
-- write path (MarketApiImpl.upsertShop) keep working unchanged.
--
--   * estimated_capacity — remaining free space for the shop item, so /find can sort
--     by / filter on "room to sell into" (REMAINING_CAPACITY, hide-full). NULL =
--     unknown or admin/infinite (mirrors current_stock's NULL semantics).
--   * visible  — owner-controlled search visibility. DISTINCT from `active`:
--     active=0 means the sign was destroyed (history tombstone); visible=0 means a
--     live shop the owner chose to hide from /find. (PAR-167)
--   * hologram — per-shop floating-item preview toggle (PAR-168); independent of
--     search visibility.
--   * world_uuid — stable world identity alongside the name key (names can be
--     re-aliased); populated going forward, NULL for pre-existing rows. (PAR-171)
--
-- Plus a (world, sign_x, sign_z) index so the per-chunk / bounding-box scans the
-- hologram loader and resync do are range scans, not full-table (PAR-170), and a
-- per-player hologram-preference table (PAR-168).

ALTER TABLE chestshop_shop
    ADD COLUMN estimated_capacity INT        NULL AFTER current_stock,
    ADD COLUMN visible            TINYINT(1) NOT NULL DEFAULT 1 AFTER active,
    ADD COLUMN hologram           TINYINT(1) NOT NULL DEFAULT 1 AFTER visible,
    ADD COLUMN world_uuid         BINARY(16) NULL AFTER world,
    ADD KEY idx_shop_world_xz (world, sign_x, sign_z);

-- Per-player preference: does this player see shop holograms at all. Default-visible;
-- a row exists only once the player has toggled (absent = use the configured default).
CREATE TABLE chestshop_preview_preference (
    player_uuid_bin BINARY(16) NOT NULL,
    visible         TINYINT(1) NOT NULL DEFAULT 1,
    PRIMARY KEY (player_uuid_bin)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
