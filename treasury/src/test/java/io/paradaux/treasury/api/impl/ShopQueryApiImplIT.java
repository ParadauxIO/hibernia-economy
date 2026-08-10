package io.paradaux.treasury.api.impl;

import io.paradaux.treasury.api.market.ShopLocation;
import io.paradaux.treasury.api.market.ShopResult;
import io.paradaux.treasury.api.market.ShopSearchQuery;
import io.paradaux.treasury.testsupport.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for the {@code /find} read side: {@link ShopQueryApiImpl}
 * delegating to {@code ShopQueryMapper} against a real (embedded) MariaDB.
 * Exercises the actual SQL — exact vs fuzzy item match, the active/visible gates,
 * world scoping, chunk bounding-box, owner-name resolution, item-key suggestions,
 * the BINARY(16) UUID binding, and the {@link ShopResult} record mapping.
 */
class ShopQueryApiImplIT extends IntegrationTestBase {

    private ShopQueryApiImpl shopQuery;

    @BeforeEach
    void setUpQuery() throws Exception {
        shopQuery = injector.getInstance(ShopQueryApiImpl.class);
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("TRUNCATE TABLE chestshop_shop");
            st.execute("TRUNCATE TABLE chestshop_preview_preference");
        }
        // Insert synthetic account ids without seeding accounts/firm rows.
        dropChestShopMarketForeignKeys();
    }

    // ── searchShops ───────────────────────────────────────────────────────────

    @Test
    void searchShops_exactMatch_returnsOnlyThatItem_activeAndVisible() {
        insertShop("world", 10, 64, 10, "DIAMOND", "Diamond", bd("5.00"), null, 64, 0, 1, 1);
        insertShop("world", 11, 64, 10, "DIAMOND_BLOCK", "Diamond Block", bd("9.00"), null, 8, 0, 1, 1);
        insertShop("world", 12, 64, 10, "DIAMOND", "Diamond", bd("4.50"), null, 30, 0, 0, 1); // inactive
        insertShop("world", 13, 64, 10, "DIAMOND", "Diamond", bd("6.00"), null, 30, 0, 1, 0); // hidden

        List<ShopResult> results = shopQuery.searchShops(
                ShopSearchQuery.builder().itemKey("DIAMOND").build());

        assertEquals(1, results.size(), "only the active+visible DIAMOND shop");
        ShopResult r = results.get(0);
        assertEquals("DIAMOND", r.itemKey());
        assertEquals(10, r.signX());
        assertEquals(0, bd("5.00").compareTo(r.buyPrice()));
        assertEquals(64, r.currentStock());
    }

    @Test
    void searchShops_fuzzy_matchesSubstring() {
        insertShop("world", 10, 64, 10, "DIAMOND", "Diamond", bd("5.00"), null, 64, 0, 1, 1);
        insertShop("world", 11, 64, 10, "DIAMOND_BLOCK", "Diamond Block", bd("9.00"), null, 8, 0, 1, 1);
        insertShop("world", 12, 64, 10, "EMERALD", "Emerald", bd("7.00"), null, 8, 0, 1, 1);

        List<ShopResult> exact = shopQuery.searchShops(
                ShopSearchQuery.builder().itemKey("DIAMOND").fuzzy(false).build());
        assertEquals(1, exact.size());

        List<ShopResult> fuzzy = shopQuery.searchShops(
                ShopSearchQuery.builder().itemKey("DIAMOND").fuzzy(true).build());
        assertEquals(2, fuzzy.size(), "fuzzy matches DIAMOND and DIAMOND_BLOCK, not EMERALD");
        assertTrue(fuzzy.stream().allMatch(s -> s.itemKey().contains("DIAMOND")));
    }

    @Test
    void searchShops_worldFilter_scopesToWorld() {
        insertShop("world", 10, 64, 10, "DIAMOND", "Diamond", bd("5.00"), null, 64, 0, 1, 1);
        insertShop("nether", 10, 64, 10, "DIAMOND", "Diamond", bd("5.00"), null, 64, 0, 1, 1);

        List<ShopResult> all = shopQuery.searchShops(
                ShopSearchQuery.builder().itemKey("DIAMOND").build());
        assertEquals(2, all.size());

        List<ShopResult> scoped = shopQuery.searchShops(
                ShopSearchQuery.builder().itemKey("DIAMOND").world("nether").build());
        assertEquals(1, scoped.size());
        assertEquals("nether", scoped.get(0).world());
    }

    @Test
    void searchShops_sellShop_nullBuyPrice_mapsCleanly() {
        insertShop("world", 10, 64, 10, "DIAMOND", "Diamond", null, bd("3.00"), null, 27, 1, 1);

        ShopResult r = shopQuery.searchShops(
                ShopSearchQuery.builder().itemKey("DIAMOND").build()).get(0);
        assertEquals(null, r.buyPrice());
        assertEquals(0, bd("3.00").compareTo(r.sellPrice()));
        assertEquals(27, r.estimatedCapacity());
        assertTrue(r.visible());
        assertTrue(r.hologram());
    }

    @Test
    void searchShops_resolvesOwnerNameFromAccount() throws Exception {
        int accountId = insertGovernmentAccount("Town Hall");
        insertOwnedShop("world", 10, 64, 10, "DIAMOND", accountId, "GOVERNMENT");

        ShopResult r = shopQuery.searchShops(
                ShopSearchQuery.builder().itemKey("DIAMOND").build()).get(0);
        assertEquals("Town Hall", r.ownerName());
    }

    // ── shopsInChunk ──────────────────────────────────────────────────────────

    @Test
    void shopsInChunk_returnsShopsInRange_includingHidden() {
        // chunk (0,0) spans block 0..15; chunk (1,0) spans 16..31.
        insertShop("world", 3, 64, 7, "DIAMOND", "Diamond", bd("5.00"), null, 10, 0, 1, 1);
        insertShop("world", 15, 70, 15, "IRON", "Iron", bd("1.00"), null, 10, 0, 1, 0); // hidden, still in chunk
        insertShop("world", 16, 64, 0, "GOLD", "Gold", bd("2.00"), null, 10, 0, 1, 1);   // chunk (1,0)
        insertShop("nether", 3, 64, 7, "GOLD", "Gold", bd("2.00"), null, 10, 0, 1, 1);    // other world

        List<ShopResult> chunk00 = shopQuery.shopsInChunk("world", 0, 0);
        assertEquals(2, chunk00.size(), "both world shops in chunk (0,0), hidden included");
        assertTrue(chunk00.stream().anyMatch(s -> s.itemKey().equals("IRON")));
    }

    // ── activeShopLocations ─────────────────────────────────────────────────────

    @Test
    void activeShopLocations_listsActive_optionallyByWorld() {
        insertShop("world", 1, 64, 1, "DIAMOND", "Diamond", bd("5.00"), null, 10, 0, 1, 1);
        insertShop("world", 2, 64, 2, "IRON", "Iron", bd("1.00"), null, 10, 0, 0, 1); // inactive
        insertShop("nether", 3, 64, 3, "GOLD", "Gold", bd("2.00"), null, 10, 0, 1, 1);

        List<ShopLocation> all = shopQuery.activeShopLocations(null);
        assertEquals(2, all.size());

        List<ShopLocation> scoped = shopQuery.activeShopLocations("nether");
        assertEquals(1, scoped.size());
        assertEquals(3, scoped.get(0).signX());
    }

    // ── matchingItemKeys ────────────────────────────────────────────────────────

    @Test
    void matchingItemKeys_distinctSubstring() {
        insertShop("world", 1, 64, 1, "DIAMOND", "Diamond", bd("5.00"), null, 10, 0, 1, 1);
        insertShop("world", 2, 64, 2, "DIAMOND", "Diamond", bd("4.00"), null, 10, 0, 1, 1); // dup key
        insertShop("world", 3, 64, 3, "DIAMOND_BLOCK", "Diamond Block", bd("9.00"), null, 10, 0, 1, 1);
        insertShop("world", 4, 64, 4, "EMERALD", "Emerald", bd("7.00"), null, 10, 0, 1, 1);

        List<String> keys = shopQuery.matchingItemKeys("DIAMOND", 10);
        assertEquals(2, keys.size(), "distinct: DIAMOND, DIAMOND_BLOCK");
        assertTrue(keys.contains("DIAMOND"));
        assertTrue(keys.contains("DIAMOND_BLOCK"));
        assertFalse(keys.contains("EMERALD"));
    }

    // ── previewVisible ──────────────────────────────────────────────────────────

    @Test
    void previewVisible_defaultsWhenAbsent_thenReflectsStored() throws Exception {
        UUID player = UUID.randomUUID();
        assertTrue(shopQuery.previewVisible(player, true), "absent → default true");
        assertFalse(shopQuery.previewVisible(player, false), "absent → default false");

        insertPreference(player, false);
        assertFalse(shopQuery.previewVisible(player, true), "stored false overrides default");
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    private void insertShop(String world, int x, int y, int z, String itemKey, String itemName,
                            BigDecimal buy, BigDecimal sell, Integer stock, Integer capacity,
                            int active, int visible) {
        insertShop(world, x, y, z, itemKey, itemName, buy, sell, stock, capacity, active, visible, 1);
    }

    private void insertShop(String world, int x, int y, int z, String itemKey, String itemName,
                            BigDecimal buy, BigDecimal sell, Integer stock, Integer capacity,
                            int active, int visible, int hologram) {
        String sql = """
                INSERT INTO chestshop_shop
                  (world, sign_x, sign_y, sign_z, is_admin_shop, material, item_key, item_name,
                   item_custom, buy_price, sell_price, batch_qty, current_stock, estimated_capacity,
                   active, visible, hologram)
                VALUES (?, ?, ?, ?, 0, ?, ?, ?, 0, ?, ?, 1, ?, ?, ?, ?, ?)
                """;
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, world);
            ps.setInt(2, x); ps.setInt(3, y); ps.setInt(4, z);
            ps.setString(5, itemKey); ps.setString(6, itemKey); ps.setString(7, itemName);
            ps.setBigDecimal(8, buy); ps.setBigDecimal(9, sell);
            if (stock == null) ps.setNull(10, java.sql.Types.INTEGER); else ps.setInt(10, stock);
            if (capacity == null) ps.setNull(11, java.sql.Types.INTEGER); else ps.setInt(11, capacity);
            ps.setInt(12, active); ps.setInt(13, visible); ps.setInt(14, hologram);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void insertOwnedShop(String world, int x, int y, int z, String itemKey,
                                 int accountId, String accountType) {
        String sql = """
                INSERT INTO chestshop_shop
                  (world, sign_x, sign_y, sign_z, is_admin_shop, shop_account_id, shop_account_type,
                   material, item_key, item_name, item_custom, buy_price, batch_qty, current_stock,
                   active, visible, hologram)
                VALUES (?, ?, ?, ?, 0, ?, ?, ?, ?, ?, 0, 5.00, 1, 10, 1, 1, 1)
                """;
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, world);
            ps.setInt(2, x); ps.setInt(3, y); ps.setInt(4, z);
            ps.setInt(5, accountId); ps.setString(6, accountType);
            ps.setString(7, itemKey); ps.setString(8, itemKey); ps.setString(9, itemKey);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private int insertGovernmentAccount(String displayName) throws Exception {
        String sql = "INSERT INTO accounts (account_type, owner_uuid_bin, display_name) VALUES ('GOVERNMENT', ?, ?)";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setBytes(1, uuidToBytes(UUID.randomUUID()));
            ps.setString(2, displayName);
            ps.executeUpdate();
            try (var rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private void insertPreference(UUID player, boolean visible) throws Exception {
        String sql = "INSERT INTO chestshop_preview_preference (player_uuid_bin, visible) VALUES (?, ?)";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setBytes(1, uuidToBytes(player));
            ps.setBoolean(2, visible);
            ps.executeUpdate();
        }
    }

    private static byte[] uuidToBytes(UUID uuid) {
        ByteBuffer bb = ByteBuffer.allocate(16);
        bb.putLong(uuid.getMostSignificantBits());
        bb.putLong(uuid.getLeastSignificantBits());
        return bb.array();
    }
}
