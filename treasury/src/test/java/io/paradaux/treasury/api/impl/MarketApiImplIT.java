package io.paradaux.treasury.api.impl;

import io.paradaux.treasury.api.market.ChestShopSaleRecord;
import io.paradaux.treasury.api.market.ChestShopShopRecord;
import io.paradaux.treasury.testsupport.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Integration test for the ChestShop write side: {@link MarketApiImpl} delegating
 * to {@code ChestShopMarketMapper} against a real (embedded) MariaDB. Exercises the
 * actual SQL — INSERT, the {@code ON DUPLICATE KEY UPDATE} upsert, the stock CASE
 * logic, BINARY(16) UUID binding, NULL binding on nullable columns, and fail-soft.
 *
 * <p>The {@code chestshop_sale}/{@code chestshop_shop} tables live in economy-schema
 * (V6), not Treasury's test {@code schema.sql}, so they're created here.
 */
class MarketApiImplIT extends IntegrationTestBase {

    private MarketApiImpl market;

    @BeforeEach
    void setUpMarket() throws Exception {
        market = injector.getInstance(MarketApiImpl.class);
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute(CREATE_SALE);
            st.execute(CREATE_SHOP);
            st.execute("TRUNCATE TABLE chestshop_sale");
            st.execute("TRUNCATE TABLE chestshop_shop");
        }
        // This IT inserts synthetic txn/account/firm ids, so drop the V20 FK
        // enforcement the Flyway harness applied to these analytics tables.
        dropChestShopMarketForeignKeys();
    }

    // ── recordSale ──────────────────────────────────────────────────────────────

    @Test
    void recordSale_personalBuy_persistsAllFields() throws Exception {
        UUID customer = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        ChestShopSaleRecord sale = new ChestShopSaleRecord(
                42L, "BUY", customer, 100, "PERSONAL", null, owner, false,
                "DIAMOND", "DIAMOND", "Diamond", false, null,
                3, new BigDecimal("5.0000"), new BigDecimal("15.00"), new BigDecimal("0.30"),
                "world", 10, 64, -20, 61);

        market.recordSale(sale);

        List<Map<String, Object>> rows = query("SELECT * FROM chestshop_sale");
        assertEquals(1, rows.size());
        Map<String, Object> r = rows.get(0);
        assertEquals(42L, ((Number) r.get("txn_id")).longValue());
        assertEquals("BUY", r.get("direction"));
        assertEquals(customer, bytesToUuid((byte[]) r.get("customer_uuid_bin")));
        assertEquals(100, ((Number) r.get("shop_account_id")).intValue());
        assertEquals("PERSONAL", r.get("shop_account_type"));
        assertNull(r.get("shop_firm_id"));
        assertEquals(owner, bytesToUuid((byte[]) r.get("shop_owner_uuid_bin")));
        assertEquals(0, ((Number) r.get("is_admin_shop")).intValue());
        assertEquals("DIAMOND", r.get("material"));
        assertEquals("Diamond", r.get("item_name"));
        assertEquals(0, ((Number) r.get("item_custom")).intValue());
        assertNull(r.get("item_data"));
        assertEquals(3, ((Number) r.get("quantity")).intValue());
        assertEquals(0, new BigDecimal("15.00").compareTo((BigDecimal) r.get("total_price")));
        assertEquals(0, new BigDecimal("0.30").compareTo((BigDecimal) r.get("tax_amount")));
        assertEquals("world", r.get("world"));
        assertEquals(10, ((Number) r.get("sign_x")).intValue());
        assertEquals(61, ((Number) r.get("shop_stock")).intValue());
        assertNotNull(r.get("occurred_at"));
    }

    @Test
    void recordSale_businessSell_setsFirmId() throws Exception {
        market.recordSale(new ChestShopSaleRecord(
                null, "SELL", UUID.randomUUID(), 200, "BUSINESS", 7, null, false,
                "IRON_INGOT", "IRON_INGOT", "Iron Ingot", false, null,
                64, new BigDecimal("1.0000"), new BigDecimal("64.00"), BigDecimal.ZERO,
                "world", 1, 2, 3, 0));

        Map<String, Object> r = query("SELECT * FROM chestshop_sale").get(0);
        assertNull(r.get("txn_id"));
        assertEquals("SELL", r.get("direction"));
        assertEquals("BUSINESS", r.get("shop_account_type"));
        assertEquals(7, ((Number) r.get("shop_firm_id")).intValue());
        assertNull(r.get("shop_owner_uuid_bin"));
        assertEquals(0, ((Number) r.get("shop_stock")).intValue());
    }

    @Test
    void recordSale_adminShop_nullsOwnerAndStock() throws Exception {
        market.recordSale(new ChestShopSaleRecord(
                null, "BUY", UUID.randomUUID(), null, null, null, null, true,
                "DIRT", "DIRT", "Dirt", false, null,
                1, new BigDecimal("1.0000"), new BigDecimal("1.00"), BigDecimal.ZERO,
                "world", 0, 0, 0, null));

        Map<String, Object> r = query("SELECT * FROM chestshop_sale").get(0);
        assertEquals(1, ((Number) r.get("is_admin_shop")).intValue());
        assertNull(r.get("shop_account_id"));
        assertNull(r.get("shop_account_type"));
        assertNull(r.get("shop_firm_id"));
        assertNull(r.get("shop_owner_uuid_bin"));
        assertNull(r.get("shop_stock"));
    }

    @Test
    void recordSale_customItem_storesItemData() throws Exception {
        market.recordSale(new ChestShopSaleRecord(
                null, "BUY", UUID.randomUUID(), 100, "PERSONAL", null, UUID.randomUUID(), false,
                "DIAMOND_SWORD", "DIAMOND_SWORD#abc123", "Excalibur", true, "yaml: blob\ncustom: true",
                1, new BigDecimal("999.0000"), new BigDecimal("999.00"), BigDecimal.ZERO,
                "world", 5, 5, 5, 1));

        Map<String, Object> r = query("SELECT * FROM chestshop_sale").get(0);
        assertEquals(1, ((Number) r.get("item_custom")).intValue());
        assertEquals("DIAMOND_SWORD#abc123", r.get("item_key"));
        assertEquals("yaml: blob\ncustom: true", r.get("item_data"));
    }

    @Test
    void recordSale_failsSoft_onConstraintViolation() {
        // material is NOT NULL — passing null triggers a DB error that must be swallowed.
        ChestShopSaleRecord bad = new ChestShopSaleRecord(
                null, "BUY", UUID.randomUUID(), null, null, null, null, true,
                null, "k", "n", false, null,
                1, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, "w", 0, 0, 0, null);
        assertDoesNotThrow(() -> market.recordSale(bad));
        assertEquals(0, query("SELECT * FROM chestshop_sale").size());
    }

    @Test
    void recordSale_multiple_accumulate() {
        for (int i = 0; i < 5; i++) {
            market.recordSale(new ChestShopSaleRecord(
                    null, "BUY", UUID.randomUUID(), 100, "PERSONAL", null, UUID.randomUUID(), false,
                    "DIAMOND", "DIAMOND", "Diamond", false, null,
                    1, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, "world", i, 0, 0, i));
        }
        assertEquals(5, query("SELECT * FROM chestshop_sale").size());
    }

    // ── upsertShop ──────────────────────────────────────────────────────────────

    @Test
    void upsertShop_insertsNewRow() throws Exception {
        market.upsertShop(shop("world", 1, 2, 3, "PERSONAL", null, UUID.randomUUID(),
                new BigDecimal("10.00"), new BigDecimal("8.00"), 16, 128));

        Map<String, Object> r = query("SELECT * FROM chestshop_shop").get(0);
        assertEquals("world", r.get("world"));
        assertEquals("PERSONAL", r.get("shop_account_type"));
        assertEquals(0, new BigDecimal("10.00").compareTo((BigDecimal) r.get("buy_price")));
        assertEquals(16, ((Number) r.get("batch_qty")).intValue());
        assertEquals(128, ((Number) r.get("current_stock")).intValue());
        assertEquals(1, ((Number) r.get("active")).intValue());
        assertNotNull(r.get("stock_at"));
    }

    @Test
    void upsertShop_onDuplicateLocation_updatesInPlace() throws Exception {
        UUID owner = UUID.randomUUID();
        market.upsertShop(shop("world", 1, 2, 3, "PERSONAL", null, owner,
                new BigDecimal("10.00"), null, 16, 100));
        long firstId = ((Number) query("SELECT shop_id FROM chestshop_shop").get(0).get("shop_id")).longValue();

        // Same location, new price/stock/item → updates the existing row, same shop_id.
        market.upsertShop(new ChestShopShopRecord("world", 1, 2, 3, false, null, "PERSONAL", null, owner,
                "EMERALD", "EMERALD", "Emerald", false, null,
                new BigDecimal("12.50"), new BigDecimal("9.00"), 8, 64, 128, null));

        List<Map<String, Object>> rows = query("SELECT * FROM chestshop_shop");
        assertEquals(1, rows.size(), "upsert must not create a second row for the same location");
        Map<String, Object> r = rows.get(0);
        assertEquals(firstId, ((Number) r.get("shop_id")).longValue());
        assertEquals("EMERALD", r.get("material"));
        assertEquals(0, new BigDecimal("12.50").compareTo((BigDecimal) r.get("buy_price")));
        assertEquals(0, new BigDecimal("9.00").compareTo((BigDecimal) r.get("sell_price")));
        assertEquals(8, ((Number) r.get("batch_qty")).intValue());
        assertEquals(64, ((Number) r.get("current_stock")).intValue());
    }

    @Test
    void upsertShop_nullStock_leavesStockAtNull() throws Exception {
        market.upsertShop(new ChestShopShopRecord("world", 9, 9, 9, true, null, null, null, null,
                "DIRT", "DIRT", "Dirt", false, null,
                new BigDecimal("1.00"), null, 64, null, null, null));
        Map<String, Object> r = query("SELECT * FROM chestshop_shop").get(0);
        assertEquals(1, ((Number) r.get("is_admin_shop")).intValue());
        assertNull(r.get("current_stock"));
        assertNull(r.get("stock_at"));
    }

    @Test
    void upsertShop_reactivatesDeactivatedShop() throws Exception {
        market.upsertShop(shop("world", 4, 4, 4, "PERSONAL", null, UUID.randomUUID(),
                new BigDecimal("5.00"), null, 1, 10));
        market.deactivateShop("world", 4, 4, 4);
        assertEquals(0, activeFlag("world", 4, 4, 4));

        market.upsertShop(shop("world", 4, 4, 4, "PERSONAL", null, UUID.randomUUID(),
                new BigDecimal("5.00"), null, 1, 20));
        assertEquals(1, activeFlag("world", 4, 4, 4), "re-creating a shop sign must reactivate the row");
    }

    // ── deactivate / updateStock ─────────────────────────────────────────────────

    @Test
    void deactivateShop_setsInactiveByLocation() throws Exception {
        market.upsertShop(shop("world", 1, 1, 1, "PERSONAL", null, UUID.randomUUID(),
                new BigDecimal("5.00"), null, 1, 10));
        market.deactivateShop("world", 1, 1, 1);
        assertEquals(0, activeFlag("world", 1, 1, 1));
    }

    @Test
    void deactivateShop_missingLocation_isNoOp() {
        assertDoesNotThrow(() -> market.deactivateShop("nowhere", 0, 0, 0));
        assertEquals(0, query("SELECT * FROM chestshop_shop").size());
    }

    @Test
    void updateShopStock_updatesStockAndTimestamp() throws Exception {
        market.upsertShop(shop("world", 2, 2, 2, "PERSONAL", null, UUID.randomUUID(),
                new BigDecimal("5.00"), null, 1, 10));
        market.updateShopStock("world", 2, 2, 2, 99, 27);

        Map<String, Object> r = query("SELECT * FROM chestshop_shop").get(0);
        assertEquals(99, ((Number) r.get("current_stock")).intValue());
        assertEquals(27, ((Number) r.get("estimated_capacity")).intValue());
        assertNotNull(r.get("stock_at"));
    }

    @Test
    void updateShopStock_nullStock_clearsValue() throws Exception {
        market.upsertShop(shop("world", 3, 3, 3, "PERSONAL", null, UUID.randomUUID(),
                new BigDecimal("5.00"), null, 1, 10));
        market.updateShopStock("world", 3, 3, 3, null, null);
        Map<String, Object> r = query("SELECT * FROM chestshop_shop").get(0);
        assertNull(r.get("current_stock"));
    }

    @Test
    void updateShopStock_missingLocation_isNoOp() {
        assertDoesNotThrow(() -> market.updateShopStock("nowhere", 0, 0, 0, 5, 3));
        assertEquals(0, query("SELECT * FROM chestshop_shop").size());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static ChestShopShopRecord shop(String world, int x, int y, int z, String type,
                                            Integer firmId, UUID owner, BigDecimal buy, BigDecimal sell,
                                            int batch, Integer stock) {
        return new ChestShopShopRecord(world, x, y, z, false, 100, type, firmId, owner,
                "DIAMOND", "DIAMOND", "Diamond", false, null, buy, sell, batch, stock, 50, null);
    }

    private int activeFlag(String world, int x, int y, int z) {
        List<Map<String, Object>> rows = query(
                "SELECT active FROM chestshop_shop WHERE world='" + world + "' AND sign_x=" + x
                        + " AND sign_y=" + y + " AND sign_z=" + z);
        assertFalse(rows.isEmpty());
        return ((Number) rows.get(0).get("active")).intValue();
    }

    private List<Map<String, Object>> query(String sql) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<Map<String, Object>> out = new ArrayList<>();
            int cols = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                Map<String, Object> row = new HashMap<>();
                for (int i = 1; i <= cols; i++) {
                    Object v = rs.getObject(i);
                    // MariaDB maps TINYINT(1) -> Boolean; normalise to 0/1 so the
                    // flag-column assertions can read them as Numbers.
                    if (v instanceof Boolean b) v = b ? 1 : 0;
                    row.put(rs.getMetaData().getColumnLabel(i).toLowerCase(), v);
                }
                out.add(row);
            }
            return out;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static UUID bytesToUuid(byte[] b) {
        assertNotNull(b);
        ByteBuffer bb = ByteBuffer.wrap(b);
        return new UUID(bb.getLong(), bb.getLong());
    }

    private static final String CREATE_SALE = """
            CREATE TABLE IF NOT EXISTS chestshop_sale (
              sale_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
              txn_id BIGINT UNSIGNED NULL,
              occurred_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
              direction ENUM('BUY','SELL') NOT NULL,
              customer_uuid_bin BINARY(16) NOT NULL,
              shop_account_id INT UNSIGNED NULL,
              shop_account_type ENUM('PERSONAL','BUSINESS','GOVERNMENT','SYSTEM') NULL,
              shop_firm_id INT NULL,
              shop_owner_uuid_bin BINARY(16) NULL,
              is_admin_shop TINYINT(1) NOT NULL DEFAULT 0,
              material VARCHAR(64) NOT NULL,
              item_key VARCHAR(190) NOT NULL,
              item_name VARCHAR(255) NOT NULL,
              item_custom TINYINT(1) NOT NULL DEFAULT 0,
              item_data MEDIUMTEXT NULL,
              quantity INT NOT NULL,
              unit_price DECIMAL(19,4) NOT NULL,
              total_price DECIMAL(19,2) NOT NULL,
              tax_amount DECIMAL(19,2) NOT NULL DEFAULT 0.00,
              world VARCHAR(64) NULL,
              sign_x INT NULL, sign_y INT NULL, sign_z INT NULL,
              shop_stock INT NULL,
              PRIMARY KEY (sale_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;

    private static final String CREATE_SHOP = """
            CREATE TABLE IF NOT EXISTS chestshop_shop (
              shop_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
              world VARCHAR(64) NOT NULL,
              sign_x INT NOT NULL, sign_y INT NOT NULL, sign_z INT NOT NULL,
              is_admin_shop TINYINT(1) NOT NULL DEFAULT 0,
              shop_account_id INT UNSIGNED NULL,
              shop_account_type ENUM('PERSONAL','BUSINESS','GOVERNMENT','SYSTEM') NULL,
              shop_firm_id INT NULL,
              shop_owner_uuid_bin BINARY(16) NULL,
              material VARCHAR(64) NOT NULL,
              item_key VARCHAR(190) NOT NULL,
              item_name VARCHAR(255) NOT NULL,
              item_custom TINYINT(1) NOT NULL DEFAULT 0,
              item_data MEDIUMTEXT NULL,
              buy_price DECIMAL(19,2) NULL,
              sell_price DECIMAL(19,2) NULL,
              batch_qty INT NOT NULL,
              current_stock INT NULL,
              estimated_capacity INT NULL,
              world_uuid BINARY(16) NULL,
              active TINYINT(1) NOT NULL DEFAULT 1,
              visible TINYINT(1) NOT NULL DEFAULT 1,
              hologram TINYINT(1) NOT NULL DEFAULT 1,
              created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
              stock_at TIMESTAMP NULL,
              last_seen TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
              PRIMARY KEY (shop_id),
              UNIQUE KEY uq_shop_location (world, sign_x, sign_y, sign_z)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;

    static final String CREATE_PREVIEW = """
            CREATE TABLE IF NOT EXISTS chestshop_preview_preference (
              player_uuid_bin BINARY(16) NOT NULL,
              visible TINYINT(1) NOT NULL DEFAULT 1,
              PRIMARY KEY (player_uuid_bin)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """;

    static final String CREATE_SHOP_DDL = CREATE_SHOP;
    static final String CREATE_SALE_DDL = CREATE_SALE;
}
