package net.democracycraft.inttests.support;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only assertion helpers for the shared MariaDB ledger. Goes through the
 * same tables Treasury maintains, but uses raw JDBC so the tests don't have a
 * Treasury Java dependency.
 *
 * <p>Connections are short-lived per call — the DB handles a few queries per
 * test fine, and tests aren't latency-sensitive.
 */
public final class Ledger {

    // ----------------------------------------------------------------- accounts

    /**
     * The materialised balance of a player's PERSONAL account, or empty if
     * no PERSONAL account exists for that owner.
     */
    public static Optional<BigDecimal> personalBalance(UUID ownerUuid) {
        return queryBalance(
                "SELECT abm.balance FROM accounts a "
                        + "JOIN account_balances_mat abm ON abm.account_id = a.account_id "
                        + "WHERE a.account_type = 'PERSONAL' "
                        + "  AND a.owner_uuid_bin = UNHEX(REPLACE(?, '-', ''))",
                ownerUuid.toString());
    }

    public static boolean personalAccountExists(UUID ownerUuid) {
        return existsBy(
                "SELECT 1 FROM accounts WHERE account_type = 'PERSONAL' "
                        + "AND owner_uuid_bin = UNHEX(REPLACE(?, '-', ''))",
                ownerUuid.toString());
    }

    /** The internal {@code account_id} of a PERSONAL account, or empty. */
    public static Optional<Integer> personalAccountId(UUID ownerUuid) {
        return queryInt(
                "SELECT account_id FROM accounts WHERE account_type = 'PERSONAL' "
                        + "AND owner_uuid_bin = UNHEX(REPLACE(?, '-', ''))",
                ownerUuid.toString());
    }

    /**
     * The materialised balance of a GOVERNMENT account by display name (e.g.
     * "DCGovernment", or a custom account created via {@code /gov account create}).
     */
    public static Optional<BigDecimal> governmentBalance(String displayName) {
        return queryBalance(
                "SELECT abm.balance FROM accounts a "
                        + "JOIN account_balances_mat abm ON abm.account_id = a.account_id "
                        + "WHERE a.account_type = 'GOVERNMENT' AND a.display_name = ?",
                displayName);
    }

    public static Optional<Integer> governmentAccountId(String displayName) {
        return queryInt(
                "SELECT account_id FROM accounts WHERE account_type = 'GOVERNMENT' "
                        + "AND display_name = ? AND is_archived = 0",
                displayName);
    }

    public static boolean governmentAccountIsArchived(String displayName) {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT is_archived FROM accounts WHERE account_type = 'GOVERNMENT' AND display_name = ?")) {
            ps.setString(1, displayName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** Top-N personal balances ordered descending. */
    public static List<BigDecimal> topPersonalBalances(int limit) {
        List<BigDecimal> out = new ArrayList<>();
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT abm.balance FROM accounts a "
                             + "JOIN account_balances_mat abm ON abm.account_id = a.account_id "
                             + "WHERE a.account_type = 'PERSONAL' AND a.is_archived = 0 "
                             + "ORDER BY abm.balance DESC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getBigDecimal(1));
            }
            return out;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // ----------------------------------------------------------------- ledger

    /** Materialised balance for an account by its internal {@code account_id}. */
    public static Optional<BigDecimal> accountBalanceById(int accountId) {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT balance FROM account_balances_mat WHERE account_id = ?")) {
            ps.setInt(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getBigDecimal(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** Number of postings against an account. */
    public static int postingsCountForAccount(int accountId) {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM ledger_postings WHERE account_id = ?")) {
            ps.setInt(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // ----------------------------------------------------------------- fines

    /** Number of (un-revoked) fines against a player. */
    public static int activeFinesForPlayer(UUID playerUuid) {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM government_fines "
                             + "WHERE player_uuid_bin = UNHEX(REPLACE(?, '-', '')) AND revoked = 0")) {
            ps.setString(1, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** The most recent fine ID issued to a player, or empty. */
    public static Optional<Long> mostRecentFineFor(UUID playerUuid) {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT fine_id FROM government_fines "
                             + "WHERE player_uuid_bin = UNHEX(REPLACE(?, '-', '')) "
                             + "ORDER BY fine_id DESC LIMIT 1")) {
            ps.setString(1, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** Number of fines (revoked or not) recorded against a player. */
    public static int totalFinesForPlayer(UUID playerUuid) {
        return queryInt("SELECT COUNT(*) FROM government_fines "
                        + "WHERE player_uuid_bin = UNHEX(REPLACE(?, '-', ''))",
                playerUuid.toString()).orElse(0);
    }

    /**
     * The most recent fine levied against an account ({@code debtor_account_id}) —
     * the only handle on a firm fine, which has no player UUID.
     */
    public static Optional<Long> mostRecentFineForDebtorAccount(int debtorAccountId) {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT fine_id FROM government_fines WHERE debtor_account_id = ? "
                             + "ORDER BY fine_id DESC LIMIT 1")) {
            ps.setInt(1, debtorAccountId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** The government account a fine was paid into ({@code gov_account_id}), or empty. */
    public static Optional<Integer> fineGovAccountId(long fineId) {
        return queryInt("SELECT gov_account_id FROM government_fines WHERE fine_id = ?",
                Long.toString(fineId));
    }

    public static boolean fineIsRevoked(long fineId) {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT revoked FROM government_fines WHERE fine_id = ?")) {
            ps.setLong(1, fineId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // ----------------------------------------------------------------- members

    // Access is one row per (account, player) in account_access (V18) carrying the
    // highest level on the ordered scale VIEWER < MEMBER < AUTHORIZER; removal is
    // soft (removed_at). An authorizer is a member who can also manage access, so
    // it counts as a member here — the same nesting the old account_members /
    // account_authorizers pair expressed with two rows.

    /** Active players with spend access (level MEMBER or AUTHORIZER) on an account. */
    public static int memberCount(int accountId) {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM account_access "
                             + "WHERE account_id = ? AND removed_at IS NULL "
                             + "AND level IN ('MEMBER', 'AUTHORIZER')")) {
            ps.setInt(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** Active players at level AUTHORIZER on an account. */
    public static int authorizerCount(int accountId) {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM account_access "
                             + "WHERE account_id = ? AND removed_at IS NULL "
                             + "AND level = 'AUTHORIZER'")) {
            ps.setInt(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // ----------------------------------------------------------------- waiters
    // Treasury commands are mostly @Async — the chat response can arrive before
    // the DB transaction commits, so tests need polling helpers rather than
    // direct asserts.

    /** Wait until a non-archived GOVERNMENT account with this name exists. */
    public static int waitForGovernmentAccount(String displayName, Duration timeout) {
        return Await.until("government account '" + displayName + "'",
                () -> governmentAccountId(displayName).orElse(null), id -> id != null, timeout);
    }

    /** Wait until the active fine count for a player equals {@code expected}. */
    public static void waitForActiveFineCount(java.util.UUID playerUuid, int expected, Duration timeout) {
        Await.untilEquals("active fines for " + playerUuid, () -> activeFinesForPlayer(playerUuid), expected, timeout);
    }

    /** Wait until the member count of an account reaches {@code expected}. */
    public static void waitForMemberCount(int accountId, int expected, Duration timeout) {
        Await.untilEquals("member count of account " + accountId, () -> memberCount(accountId), expected, timeout);
    }

    /** Wait until the authorizer count of an account reaches {@code expected}. */
    public static void waitForAuthorizerCount(int accountId, int expected, Duration timeout) {
        Await.untilEquals("authorizer count of account " + accountId, () -> authorizerCount(accountId),
                expected, timeout);
    }

    // -------------------------------------------------------- player balance waiters

    /**
     * Block until the player's PERSONAL balance becomes available (typically
     * fired by Treasury's {@code FirstPlayerJoinEvent} listener), or
     * {@code timeout} elapses.
     *
     * @return the seeded balance
     * @throws AssertionError if no balance materialises in time
     */
    public static BigDecimal waitForPersonalBalance(UUID ownerUuid, Duration timeout) {
        return Await.until("Treasury to seed a balance for " + ownerUuid,
                () -> personalBalance(ownerUuid).orElse(null), bal -> bal != null, timeout);
    }

    /** Wait until the materialised balance for {@code accountId} equals {@code expected}. */
    public static void waitForAccountBalanceById(int accountId, BigDecimal expected, Duration timeout) {
        Await.until("balance of account " + accountId + " to reach " + expected,
                () -> accountBalanceById(accountId).orElse(null), sameAmount(expected), timeout);
    }

    /** Wait until the personal balance equals {@code expected}, comparing scale-insensitively. */
    public static void waitForPersonalBalance(UUID ownerUuid, BigDecimal expected, Duration timeout) {
        Await.until("personal balance of " + ownerUuid + " to reach " + expected,
                () -> personalBalance(ownerUuid).orElse(null), sameAmount(expected), timeout);
    }

    /** Wait until a fine exists for the player; returns the most recent fine's id. */
    public static long waitForMostRecentFine(UUID playerUuid, Duration timeout) {
        return Await.until("a fine recorded for " + playerUuid,
                () -> mostRecentFineFor(playerUuid).orElse(null), id -> id != null, timeout);
    }

    /** Wait until the named GOVERNMENT account's balance equals {@code expected} (scale-insensitive). */
    public static void waitForGovernmentBalance(String displayName, BigDecimal expected, Duration timeout) {
        Await.until("balance of gov account " + displayName + " to reach " + expected,
                () -> governmentBalance(displayName).orElse(null), sameAmount(expected), timeout);
    }

    /** Scale-insensitive, null-safe "equals this amount" predicate for {@link Await}. */
    public static java.util.function.Predicate<BigDecimal> sameAmount(BigDecimal expected) {
        return v -> v != null && v.compareTo(expected) == 0;
    }

    /**
     * Recorded last-login epoch (Unix seconds) for a player, or empty if no
     * row exists in {@code player_login_times}. Treasury writes to this
     * table on every player join; TreasuryIngest seeds it from legacy
     * sources during migration.
     */
    public static Optional<Long> lastLoginEpoch(UUID playerUuid) {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT last_login_epoch FROM player_login_times "
                             + "WHERE player_uuid_bin = UNHEX(REPLACE(?, '-', ''))")) {
            ps.setString(1, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // ----------------------------------------------------------------- internals

    private static Optional<BigDecimal> queryBalance(String sql, String uuidParam) {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuidParam);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getBigDecimal(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read balance: " + e.getMessage(), e);
        }
    }

    /** Hyphenated lowercase UUID linked to a Keycloak subject in explorer_identity, or empty. */
    public static Optional<String> explorerIdentityUuid(String keycloakSub) {
        String sql = "SELECT LOWER(CONCAT_WS('-', "
                + "SUBSTR(HEX(player_uuid_bin),1,8), SUBSTR(HEX(player_uuid_bin),9,4), "
                + "SUBSTR(HEX(player_uuid_bin),13,4), SUBSTR(HEX(player_uuid_bin),17,4), "
                + "SUBSTR(HEX(player_uuid_bin),21,12))) "
                + "FROM explorer_identity WHERE keycloak_sub = ?";
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, keycloakSub);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

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

    private static boolean existsBy(String sql, String param) {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(DevServer.DB_URL, DevServer.DB_USER, DevServer.DB_PASSWORD);
    }

    private Ledger() {}
}
