package io.paradaux.treasuryrestapi.testsupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.util.UUID;

/**
 * Base for integration tests that need a real database: a full Spring context
 * pointed at the shared {@link EmbeddedMariaDb}, a {@link MockMvc}, and seed
 * builders for accounts / firms / players. Tables are truncated before each test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Tag("integration")
public abstract class EmbeddedDbIT {

    @Autowired protected MockMvc mvc;
    @Autowired protected DataSource dataSource;

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        EmbeddedMariaDb.ensureStarted();
        registry.add("spring.datasource.url", EmbeddedMariaDb::jdbcUrl);
        registry.add("spring.datasource.username", EmbeddedMariaDb::username);
        registry.add("spring.datasource.password", EmbeddedMariaDb::password);
        registry.add("spring.datasource.driver-class-name", () -> "org.mariadb.jdbc.Driver");
    }

    @BeforeEach
    void truncate() {
        EmbeddedMariaDb.truncateAll();
    }

    // ── seed: economy stand-ins ─────────────────────────────────────────────────

    protected void insertAccount(int accountId, String type, UUID ownerUuid, String displayName) {
        exec("INSERT INTO accounts (account_id, account_type, owner_uuid_bin, display_name) VALUES (?,?,?,?)",
                ps -> {
                    ps.setInt(1, accountId);
                    ps.setString(2, type);
                    setUuid(ps, 3, ownerUuid);
                    ps.setString(4, displayName);
                });
    }

    protected void insertFirm(int firmId, String displayName) {
        exec("INSERT INTO firm (firm_id, display_name) VALUES (?,?)", ps -> {
            ps.setInt(1, firmId);
            ps.setString(2, displayName);
        });
    }

    /** Seed a firm with a proprietor (and optional default account) for admin firm tests. */
    protected void insertFirm(int firmId, String displayName, UUID proprietorUuid, Integer defaultAccountId) {
        exec("INSERT INTO firm (firm_id, display_name, proprietor_uuid_bin, default_account_id) VALUES (?,?,?,?)",
                ps -> {
                    ps.setInt(1, firmId);
                    ps.setString(2, displayName);
                    setUuid(ps, 3, proprietorUuid);
                    if (defaultAccountId == null) ps.setNull(4, Types.INTEGER); else ps.setInt(4, defaultAccountId);
                });
    }

    /** Link an account to a firm (live link — removed_at NULL). */
    protected void linkFirmAccount(int firmId, int accountId) {
        exec("INSERT INTO firm_accounts (firm_id, account_id) VALUES (?,?)", ps -> {
            ps.setInt(1, firmId);
            ps.setInt(2, accountId);
        });
    }

    /** Mark a firm archived up-front (for idempotency tests). */
    protected void archiveFirm(int firmId) {
        exec("UPDATE firm SET is_archived = 1 WHERE firm_id = ?", ps -> ps.setInt(1, firmId));
    }

    protected boolean isFirmArchived(int firmId) {
        return queryInt("SELECT is_archived FROM firm WHERE firm_id = ?", firmId) == 1;
    }

    protected boolean isAccountArchived(int accountId) {
        return queryInt("SELECT is_archived FROM accounts WHERE account_id = ?", accountId) == 1;
    }

    /** Count of live (removed_at IS NULL) firm↔account links for a firm. */
    protected long liveFirmAccountLinks(int firmId) {
        return queryInt("SELECT COUNT(*) FROM firm_accounts WHERE firm_id = ? AND removed_at IS NULL", firmId);
    }

    private int queryInt(String sql, int arg) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, arg);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    protected void insertPlayer(UUID uuid, String currentName) {
        exec("INSERT INTO economy_players (player_uuid_bin, current_name) VALUES (?,?)", ps -> {
            ps.setBytes(1, EmbeddedMariaDb.uuidBytes(uuid));
            ps.setString(2, currentName);
        });
    }

    /** Seed a materialised balance row (transfers lock account_balances_mat FOR UPDATE). */
    protected void seedBalance(int accountId, String balance) {
        exec("INSERT INTO account_balances_mat (account_id, balance, version) VALUES (?,?,0)", ps -> {
            ps.setInt(1, accountId);
            ps.setBigDecimal(2, new java.math.BigDecimal(balance));
        });
    }

    // ── seed: api keys ──────────────────────────────────────────────────────────

    /** Seed an api_keys row; returns nothing (keyId is the caller-supplied PK). */
    protected void insertApiKey(int keyId, String keyType, UUID owner, String jwtId,
                                boolean revoked, java.time.LocalDateTime expiresAt) {
        exec("INSERT INTO api_keys (key_id, key_type, owner_uuid_bin, jwt_id, revoked, issued_at, expires_at) "
                + "VALUES (?,?,?,?,?, CURRENT_TIMESTAMP, ?)", ps -> {
            ps.setInt(1, keyId);
            ps.setString(2, keyType);
            ps.setBytes(3, EmbeddedMariaDb.uuidBytes(owner));
            ps.setString(4, jwtId);
            ps.setInt(5, revoked ? 1 : 0);
            ps.setTimestamp(6, java.sql.Timestamp.valueOf(expiresAt));
        });
    }

    protected String jwtIdOf(int keyId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT jwt_id FROM api_keys WHERE key_id = ?")) {
            ps.setInt(1, keyId);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getString(1); }
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    protected boolean isKeyRevoked(int keyId) {
        return queryInt("SELECT revoked FROM api_keys WHERE key_id = ?", keyId) == 1;
    }

    // ── seed: firm roster ───────────────────────────────────────────────────────

    protected void insertFirmRole(int roleId, int firmId, String name, int rankOrder,
                                  boolean proprietorLike, boolean defaultRole, boolean deleted) {
        exec("INSERT INTO firm_role (role_id, firm_id, name, rank_order, is_proprietor_like, is_default, deleted_at) "
                + "VALUES (?,?,?,?,?,?, ?)", ps -> {
            ps.setInt(1, roleId);
            ps.setInt(2, firmId);
            ps.setString(3, name);
            ps.setInt(4, rankOrder);
            ps.setInt(5, proprietorLike ? 1 : 0);
            ps.setInt(6, defaultRole ? 1 : 0);
            if (deleted) ps.setTimestamp(7, java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()));
            else ps.setNull(7, Types.TIMESTAMP);
        });
    }

    protected void insertRolePermission(int roleId, String permission, boolean deleted) {
        exec("INSERT INTO firm_role_permission (role_id, permission, deleted_at) VALUES (?,?, ?)", ps -> {
            ps.setInt(1, roleId);
            ps.setString(2, permission);
            if (deleted) ps.setTimestamp(3, java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()));
            else ps.setNull(3, Types.TIMESTAMP);
        });
    }

    protected void insertEmployee(int firmId, UUID player, int roleId, boolean left) {
        exec("INSERT INTO firm_employee (firm_id, player_uuid_bin, role_id, joined_at, left_at) "
                + "VALUES (?,?,?, CURRENT_TIMESTAMP, ?)", ps -> {
            ps.setInt(1, firmId);
            ps.setBytes(2, EmbeddedMariaDb.uuidBytes(player));
            ps.setInt(3, roleId);
            if (left) ps.setTimestamp(4, java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()));
            else ps.setNull(4, Types.TIMESTAMP);
        });
    }

    // ── seed: webhooks ──────────────────────────────────────────────────────────

    /** Seed a webhook_subscription; returns the generated subscription_id. */
    protected long insertSubscription(UUID owner, String keyType, Integer accountId, Integer firmId,
                                      String targetUrl, String secret, boolean active,
                                      long consecutiveFailures) {
        return execReturningKey("INSERT INTO webhook_subscription "
                + "(owner_uuid_bin, key_type, account_id, firm_id, target_url, secret, active, consecutive_failures) "
                + "VALUES (?,?,?,?,?,?,?,?)", ps -> {
            ps.setBytes(1, EmbeddedMariaDb.uuidBytes(owner));
            ps.setString(2, keyType);
            if (accountId == null) ps.setNull(3, Types.INTEGER); else ps.setInt(3, accountId);
            if (firmId == null) ps.setNull(4, Types.INTEGER); else ps.setInt(4, firmId);
            ps.setString(5, targetUrl);
            ps.setString(6, secret);
            ps.setInt(7, active ? 1 : 0);
            ps.setLong(8, consecutiveFailures);
        });
    }

    /** Seed a webhook_delivery due now (next_attempt_at in the past). Returns delivery_id. */
    protected long insertDueDelivery(long subscriptionId, long txnId, int accountId, int attempts) {
        return execReturningKey("INSERT INTO webhook_delivery "
                + "(subscription_id, txn_id, account_id, status, attempts, next_attempt_at) "
                + "VALUES (?,?,?, 'PENDING', ?, NOW() - INTERVAL 1 MINUTE)", ps -> {
            ps.setLong(1, subscriptionId);
            ps.setLong(2, txnId);
            ps.setInt(3, accountId);
            ps.setInt(4, attempts);
        });
    }

    /** Seed a minimal settled ledger txn + one posting for the given account. Returns txn_id. */
    protected long insertSettledTxn(int accountId, String amount, String message, UUID initiator) {
        long txnId = execReturningKey("INSERT INTO ledger_txns "
                + "(message, settlement_time, initiator_uuid_bin, plugin_system) "
                + "VALUES (?, NOW() - INTERVAL 1 HOUR, ?, 'test')", ps -> {
            ps.setString(1, message);
            ps.setBytes(2, EmbeddedMariaDb.uuidBytes(initiator));
        });
        exec("INSERT INTO ledger_postings (txn_id, account_id, amount, memo) VALUES (?,?,?, ?)", ps -> {
            ps.setLong(1, txnId);
            ps.setInt(2, accountId);
            ps.setBigDecimal(3, new java.math.BigDecimal(amount));
            ps.setString(4, message);
        });
        return txnId;
    }

    /** Read a single string/int/timestamp column from a webhook_delivery row. */
    protected String deliveryStatus(long deliveryId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT status FROM webhook_delivery WHERE delivery_id = ?")) {
            ps.setLong(1, deliveryId);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getString(1); }
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    protected int deliveryAttempts(long deliveryId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT attempts FROM webhook_delivery WHERE delivery_id = ?")) {
            ps.setLong(1, deliveryId);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getInt(1); }
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    /** Seconds from now until this delivery's next_attempt_at (negative if already due). */
    protected long secondsUntilNextAttempt(long deliveryId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT TIMESTAMPDIFF(SECOND, NOW(), next_attempt_at) FROM webhook_delivery WHERE delivery_id = ?")) {
            ps.setLong(1, deliveryId);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getLong(1); }
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    protected boolean subscriptionActive(long subscriptionId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT active FROM webhook_subscription WHERE subscription_id = ?")) {
            ps.setLong(1, subscriptionId);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getInt(1) == 1; }
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    protected long subscriptionFailures(long subscriptionId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT consecutive_failures FROM webhook_subscription WHERE subscription_id = ?")) {
            ps.setLong(1, subscriptionId);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getLong(1); }
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    protected long deliveryCount(long subscriptionId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM webhook_delivery WHERE subscription_id = ?")) {
            ps.setLong(1, subscriptionId);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getLong(1); }
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    protected long cursorValue() {
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT last_dispatched_txn_id FROM webhook_cursor WHERE id = 1")) {
            rs.next();
            return rs.getLong(1);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    // ── seed: ChestShop analytics ───────────────────────────────────────────────

    /**
     * Insert one {@code chestshop_sale} row. World/sign default to {@code world}
     * at the origin; tax 0; unit price derived from total/quantity.
     */
    protected void insertSale(String direction, UUID customer, String accountType, Integer firmId,
                              UUID ownerUuid, boolean adminShop, String material, String itemKey,
                              String itemName, int quantity, String totalPrice,
                              java.time.LocalDateTime occurredAt) {
        java.math.BigDecimal total = new java.math.BigDecimal(totalPrice);
        java.math.BigDecimal unit = quantity > 0
                ? total.divide(java.math.BigDecimal.valueOf(quantity), 4, java.math.RoundingMode.HALF_UP)
                : java.math.BigDecimal.ZERO;
        exec("INSERT INTO chestshop_sale (occurred_at, direction, customer_uuid_bin, shop_account_type, "
                + "shop_firm_id, shop_owner_uuid_bin, is_admin_shop, material, item_key, item_name, "
                + "quantity, unit_price, total_price, world, sign_x, sign_y, sign_z) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?, 'world', 0, 0, 0)", ps -> {
            ps.setTimestamp(1, java.sql.Timestamp.valueOf(occurredAt));
            ps.setString(2, direction);
            ps.setBytes(3, EmbeddedMariaDb.uuidBytes(customer));
            ps.setString(4, accountType);
            if (firmId == null) ps.setNull(5, Types.INTEGER); else ps.setInt(5, firmId);
            if (ownerUuid == null) ps.setNull(6, Types.BINARY); else ps.setBytes(6, EmbeddedMariaDb.uuidBytes(ownerUuid));
            ps.setInt(7, adminShop ? 1 : 0);
            ps.setString(8, material);
            ps.setString(9, itemKey);
            ps.setString(10, itemName);
            ps.setInt(11, quantity);
            ps.setBigDecimal(12, unit);
            ps.setBigDecimal(13, total);
        });
    }

    /** Insert one {@code chestshop_shop} row. */
    protected void insertShop(String world, int x, int y, int z, boolean adminShop, String accountType,
                              Integer firmId, UUID ownerUuid, String material, String itemKey,
                              String itemName, String buyPrice, String sellPrice, int batchQty,
                              Integer currentStock, boolean active) {
        exec("INSERT INTO chestshop_shop (world, sign_x, sign_y, sign_z, is_admin_shop, shop_account_type, "
                + "shop_firm_id, shop_owner_uuid_bin, material, item_key, item_name, buy_price, sell_price, "
                + "batch_qty, current_stock, active) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", ps -> {
            ps.setString(1, world);
            ps.setInt(2, x);
            ps.setInt(3, y);
            ps.setInt(4, z);
            ps.setInt(5, adminShop ? 1 : 0);
            ps.setString(6, accountType);
            if (firmId == null) ps.setNull(7, Types.INTEGER); else ps.setInt(7, firmId);
            if (ownerUuid == null) ps.setNull(8, Types.BINARY); else ps.setBytes(8, EmbeddedMariaDb.uuidBytes(ownerUuid));
            ps.setString(9, material);
            ps.setString(10, itemKey);
            ps.setString(11, itemName);
            if (buyPrice == null) ps.setNull(12, Types.DECIMAL); else ps.setBigDecimal(12, new java.math.BigDecimal(buyPrice));
            if (sellPrice == null) ps.setNull(13, Types.DECIMAL); else ps.setBigDecimal(13, new java.math.BigDecimal(sellPrice));
            ps.setInt(14, batchQty);
            if (currentStock == null) ps.setNull(15, Types.INTEGER); else ps.setInt(15, currentStock);
            ps.setInt(16, active ? 1 : 0);
        });
    }

    // ── tiny read helpers for assertions ────────────────────────────────────────

    protected long rowCount(String table) {
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    protected java.math.BigDecimal sumPostings(int accountId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COALESCE(SUM(amount), 0) FROM ledger_postings WHERE account_id = ?")) {
            ps.setInt(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBigDecimal(1);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ── tiny JDBC plumbing ──────────────────────────────────────────────────────

    @FunctionalInterface protected interface Binder { void bind(PreparedStatement ps) throws Exception; }

    protected void exec(String sql, Binder binder) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    protected long execReturningKey(String sql, Binder binder) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            binder.bind(ps);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void setUuid(PreparedStatement ps, int i, UUID u) throws Exception {
        if (u == null) ps.setNull(i, Types.BINARY); else ps.setBytes(i, EmbeddedMariaDb.uuidBytes(u));
    }
}
