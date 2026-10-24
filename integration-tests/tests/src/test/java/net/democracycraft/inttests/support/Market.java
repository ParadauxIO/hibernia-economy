package net.democracycraft.inttests.support;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC helpers for the ChestShop sales tracker tables ({@code chestshop_sale},
 * {@code chestshop_shop}) on the dev-server economy DB. Used by the end-to-end
 * tests to assert that an in-game trade actually flowed ChestShop → Treasury
 * MarketApi → DB.
 */
public final class Market {

    private Market() {}

    /** A captured sale row, with the type-aware shop-owner identity decoded. */
    public record Sale(long saleId, String direction, String shopAccountType, Integer shopFirmId,
                       UUID shopOwnerUuid, boolean adminShop, String material, String itemKey,
                       String itemName, boolean itemCustom, int quantity, BigDecimal unitPrice,
                       BigDecimal totalPrice, Integer shopStock, String world, Integer signX,
                       Integer signY, Integer signZ) {}

    /** A live shop registry row. */
    public record Shop(long shopId, String shopAccountType, Integer shopFirmId, UUID shopOwnerUuid,
                       boolean adminShop, String material, String itemKey, BigDecimal buyPrice,
                       BigDecimal sellPrice, int batchQty, Integer currentStock, boolean active, boolean visible, boolean hologram) {}

    private static Connection conn() throws SQLException {
        return DriverManager.getConnection(DevServer.DB_URL, DevServer.DB_USER, DevServer.DB_PASSWORD);
    }

    private static UUID uuid(byte[] b) {
        if (b == null || b.length != 16) return null;
        ByteBuffer bb = ByteBuffer.wrap(b);
        return new UUID(bb.getLong(), bb.getLong());
    }

    // ── sales ───────────────────────────────────────────────────────────────────

    public static Optional<Sale> latestSaleForCustomer(UUID customer) {
        String sql = "SELECT sale_id, direction, shop_account_type, shop_firm_id, shop_owner_uuid_bin, "
                + "is_admin_shop, material, item_key, item_name, item_custom, quantity, unit_price, "
                + "total_price, shop_stock, world, sign_x, sign_y, sign_z "
                + "FROM chestshop_sale WHERE customer_uuid_bin = UNHEX(REPLACE(?, '-', '')) "
                + "ORDER BY sale_id DESC LIMIT 1";
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, customer.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new Sale(
                        rs.getLong("sale_id"), rs.getString("direction"), rs.getString("shop_account_type"),
                        (Integer) rs.getObject("shop_firm_id"), uuid(rs.getBytes("shop_owner_uuid_bin")),
                        rs.getBoolean("is_admin_shop"), rs.getString("material"), rs.getString("item_key"),
                        rs.getString("item_name"), rs.getBoolean("item_custom"), rs.getInt("quantity"),
                        rs.getBigDecimal("unit_price"), rs.getBigDecimal("total_price"),
                        (Integer) rs.getObject("shop_stock"), rs.getString("world"),
                        (Integer) rs.getObject("sign_x"), (Integer) rs.getObject("sign_y"),
                        (Integer) rs.getObject("sign_z")));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static int saleCountForCustomer(UUID customer) {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM chestshop_sale WHERE customer_uuid_bin = UNHEX(REPLACE(?, '-', ''))")) {
            ps.setString(1, customer.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static Sale waitForSaleForCustomer(UUID customer, Duration timeout) {
        return Await.until("a chestshop_sale row for customer " + customer,
                () -> latestSaleForCustomer(customer).orElse(null), s -> s != null, timeout);
    }

    public static void waitForSaleCount(UUID customer, int expected, Duration timeout) {
        Await.until("chestshop_sale count for " + customer + " to reach >= " + expected,
                () -> saleCountForCustomer(customer), n -> n >= expected, timeout);
    }

    // ── shops ───────────────────────────────────────────────────────────────────

    public static Optional<Shop> shopAt(String world, int x, int y, int z) {
        String sql = "SELECT shop_id, shop_account_type, shop_firm_id, shop_owner_uuid_bin, is_admin_shop, "
                + "material, item_key, buy_price, sell_price, batch_qty, current_stock, active, visible, hologram "
                + "FROM chestshop_shop WHERE world = ? AND sign_x = ? AND sign_y = ? AND sign_z = ?";
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, world);
            ps.setInt(2, x);
            ps.setInt(3, y);
            ps.setInt(4, z);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new Shop(
                        rs.getLong("shop_id"), rs.getString("shop_account_type"),
                        (Integer) rs.getObject("shop_firm_id"), uuid(rs.getBytes("shop_owner_uuid_bin")),
                        rs.getBoolean("is_admin_shop"), rs.getString("material"), rs.getString("item_key"),
                        rs.getBigDecimal("buy_price"), rs.getBigDecimal("sell_price"), rs.getInt("batch_qty"),
                        (Integer) rs.getObject("current_stock"), rs.getBoolean("active"), rs.getBoolean("visible"), rs.getBoolean("hologram")));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static Shop waitForShopAt(String world, int x, int y, int z, Duration timeout) {
        return Await.until("a chestshop_shop row at " + world + " " + x + "," + y + "," + z,
                () -> shopAt(world, x, y, z).orElse(null), s -> s != null, timeout);
    }
}
