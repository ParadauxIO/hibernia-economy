package net.democracycraft.inttests.support;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Business-side DB assertion helpers — counterpart to {@link Ledger}, for
 * firms, employees, role permissions, account mappings.
 *
 * <p>Most Business commands are {@code @Async}, so direct asserts after a
 * chat receipt race the transaction commit. Use the {@code waitForX}
 * variants in tests.
 */
public final class BusinessLedger {

    // -------------------------------------------------------------- firm

    /** Numeric firm_id by display name; empty if not found or archived. */
    public static Optional<Integer> firmId(String displayName) {
        return queryInt(
                "SELECT firm_id FROM firm WHERE display_name = ? AND is_archived = 0",
                displayName);
    }

    public static boolean firmExists(String displayName) {
        return firmId(displayName).isPresent();
    }

    public static boolean firmIsArchived(String displayName) {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT is_archived FROM firm WHERE display_name = ?")) {
            ps.setString(1, displayName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static Optional<UUID> firmProprietor(int firmId) {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT BIN_TO_UUID(proprietor_uuid_bin) FROM firm WHERE firm_id = ?")) {
            ps.setInt(1, firmId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String uuidStr = rs.getString(1);
                    return uuidStr == null ? Optional.empty() : Optional.of(UUID.fromString(uuidStr));
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static Optional<String> firmHqRegion(int firmId) {
        return queryString("SELECT hq_region FROM firm WHERE firm_id = ?", firmId);
    }

    public static Optional<String> firmDiscordUrl(int firmId) {
        return queryString("SELECT discord_url FROM firm WHERE firm_id = ?", firmId);
    }

    public static Optional<Integer> firmDefaultAccountId(int firmId) {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT default_account_id FROM firm WHERE firm_id = ?")) {
            ps.setInt(1, firmId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    int v = rs.getInt(1);
                    return rs.wasNull() ? Optional.empty() : Optional.of(v);
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static Optional<BigDecimal> firmDefaultBalance(int firmId) {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT abm.balance FROM firm f "
                             + "JOIN account_balances_mat abm ON abm.account_id = f.default_account_id "
                             + "WHERE f.firm_id = ?")) {
            ps.setInt(1, firmId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getBigDecimal(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // -------------------------------------------------------------- employees

    public static int currentEmployeeCount(int firmId) {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM firm_employee WHERE firm_id = ? AND left_at IS NULL")) {
            ps.setInt(1, firmId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static boolean isCurrentEmployee(int firmId, UUID playerUuid) {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM firm_employee "
                             + "WHERE firm_id = ? AND player_uuid_bin = UNHEX(REPLACE(?, '-', '')) "
                             + "  AND left_at IS NULL")) {
            ps.setInt(1, firmId);
            ps.setString(2, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static Optional<String> currentEmployeeRoleName(int firmId, UUID playerUuid) {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT r.name FROM firm_employee e "
                             + "JOIN firm_role r ON r.role_id = e.role_id "
                             + "WHERE e.firm_id = ? AND e.player_uuid_bin = UNHEX(REPLACE(?, '-', '')) "
                             + "  AND e.left_at IS NULL")) {
            ps.setInt(1, firmId);
            ps.setString(2, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // -------------------------------------------------------------- roles

    public static int roleCount(int firmId) {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM firm_role WHERE firm_id = ?")) {
            ps.setInt(1, firmId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static boolean roleExists(int firmId, String roleName) {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM firm_role WHERE firm_id = ? AND name = ? "
                             + "AND deleted_at IS NULL")) {
            ps.setInt(1, firmId);
            ps.setString(2, roleName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static int rolePermissionCount(int firmId, String roleName) {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM firm_role_permission rp "
                             + "JOIN firm_role r ON r.role_id = rp.role_id "
                             + "WHERE r.firm_id = ? AND r.name = ? "
                             + "AND r.deleted_at IS NULL "
                             + "AND rp.deleted_at IS NULL")) {
            ps.setInt(1, firmId);
            ps.setString(2, roleName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // -------------------------------------------------------------- firm-account mapping

    public static int firmAccountCount(int firmId) {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM firm_accounts WHERE firm_id = ?")) {
            ps.setInt(1, firmId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** All Treasury account_ids associated with a firm via firm_accounts. */
    public static int firmAccountIdAt(int firmId, int ordinal /* 0-based */) {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT account_id FROM firm_accounts WHERE firm_id = ? "
                             + "ORDER BY added_at, account_id LIMIT 1 OFFSET ?")) {
            ps.setInt(1, firmId);
            ps.setInt(2, ordinal);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
                throw new AssertionError("no firm_accounts row for firm " + firmId + " at ordinal " + ordinal);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // -------------------------------------------------------------- account access
    // V18 folded account_members / account_authorizers into one account_access
    // row per (account, subject): level VIEWER | MEMBER | AUTHORIZER, soft-removed
    // via removed_at. An AUTHORIZER is also a member; a VIEWER is neither.

    private static final String ACTIVE_MEMBER =
            "level IN ('MEMBER','AUTHORIZER') AND removed_at IS NULL";
    private static final String ACTIVE_AUTHORIZER =
            "level = 'AUTHORIZER' AND removed_at IS NULL";

    /** Active members (MEMBER or AUTHORIZER) of a Treasury account. */
    public static int accountMemberCount(int accountId) {
        return countAccess(accountId, ACTIVE_MEMBER);
    }

    /** Active authorizers of a Treasury account. */
    public static int accountAuthorizerCount(int accountId) {
        return countAccess(accountId, ACTIVE_AUTHORIZER);
    }

    public static boolean isAccountMember(int accountId, UUID playerUuid) {
        return hasAccess(accountId, playerUuid, ACTIVE_MEMBER);
    }

    public static boolean isAccountAuthorizer(int accountId, UUID playerUuid) {
        return hasAccess(accountId, playerUuid, ACTIVE_AUTHORIZER);
    }

    public static void waitForAccountMemberCount(int accountId, int expected, Duration timeout) {
        Await.untilEquals("member count of account " + accountId, () -> accountMemberCount(accountId),
                expected, timeout);
    }

    public static void waitForAccountAuthorizerCount(int accountId, int expected, Duration timeout) {
        Await.untilEquals("authorizer count of account " + accountId, () -> accountAuthorizerCount(accountId),
                expected, timeout);
    }

    private static int countAccess(int accountId, String activeClause) {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM account_access WHERE account_id = ? AND " + activeClause)) {
            ps.setInt(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean hasAccess(int accountId, UUID playerUuid, String activeClause) {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM account_access WHERE account_id = ? "
                             + "AND subject_uuid_bin = UNHEX(REPLACE(?, '-', '')) AND " + activeClause)) {
            ps.setInt(1, accountId);
            ps.setString(2, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // -------------------------------------------------------------- transfer requests

    public static Optional<String> activeTransferToken(int firmId, UUID toUuid) {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT token FROM firm_transfer_requests "
                             + "WHERE firm_id = ? AND to_uuid_bin = UNHEX(REPLACE(?, '-', '')) "
                             + "  AND status IN ('PENDING','CONFIRMED') "
                             + "ORDER BY created_at DESC LIMIT 1")) {
            ps.setInt(1, firmId);
            ps.setString(2, toUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // -------------------------------------------------------------- waiters

    public static int waitForFirm(String displayName, Duration timeout) {
        return Await.until("firm '" + displayName + "' in the DB",
                () -> firmId(displayName).orElse(null), id -> id != null, timeout);
    }

    /** Wait until the firm has at least {@code min} accounts; returns the count seen. */
    public static int waitForFirmAccountCountAtLeast(int firmId, int min, Duration timeout) {
        return Await.until("firm " + firmId + " to have at least " + min + " accounts",
                () -> firmAccountCount(firmId), n -> n >= min, timeout);
    }

    /** Wait until the firm's default account is {@code accountId}. */
    public static void waitForFirmDefaultAccount(int firmId, int accountId, Duration timeout) {
        Await.untilEquals("default account of firm " + firmId, () -> firmDefaultAccountId(firmId).orElse(null),
                accountId, timeout);
    }

    /** Wait until the firm's default-account balance equals {@code expected} (scale-insensitive). */
    public static void waitForFirmDefaultBalance(int firmId, BigDecimal expected, Duration timeout) {
        Await.until("default-account balance of firm " + firmId + " to reach " + expected,
                () -> firmDefaultBalance(firmId).orElse(null), Ledger.sameAmount(expected), timeout);
    }

    public static void waitForEmployeeCount(int firmId, int expected, Duration timeout) {
        Await.untilEquals("employee count of firm " + firmId, () -> currentEmployeeCount(firmId), expected, timeout);
    }

    public static void waitForEmployeeRole(int firmId, UUID playerUuid, String expected, Duration timeout) {
        Await.untilEquals("role of employee " + playerUuid + " in firm " + firmId,
                () -> currentEmployeeRoleName(firmId, playerUuid).orElse(null), expected, timeout);
    }

    public static void waitForRoleCount(int firmId, int expected, Duration timeout) {
        Await.untilEquals("role count of firm " + firmId, () -> roleCount(firmId), expected, timeout);
    }

    public static void waitForFirmProprietor(int firmId, UUID expected, Duration timeout) {
        Await.untilEquals("proprietor of firm " + firmId, () -> firmProprietor(firmId).orElse(null),
                expected, timeout);
    }

    public static void waitForActiveTransferToken(int firmId, UUID toUuid, Duration timeout) {
        Await.until("an active transfer token for firm " + firmId + " → " + toUuid,
                () -> activeTransferToken(firmId, toUuid).orElse(null), t -> t != null, timeout);
    }

    // -------------------------------------------------------------- internals

    private static Optional<Integer> queryInt(String sql, String param) {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getInt(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static Optional<String> queryString(String sql, int param) {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(DevServer.DB_URL, DevServer.DB_USER, DevServer.DB_PASSWORD);
    }

    private BusinessLedger() {}
}
