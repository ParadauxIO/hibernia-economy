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
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Integer id = governmentAccountId(displayName).orElse(null);
            if (id != null) return id;
            sleep(150);
        }
        throw new AssertionError("Government account '" + displayName + "' not found within " + timeout);
    }

    /** Wait until the active fine count for a player equals {@code expected}. */
    public static void waitForActiveFineCount(java.util.UUID playerUuid, int expected, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        int last = -1;
        while (System.nanoTime() < deadline) {
            last = activeFinesForPlayer(playerUuid);
            if (last == expected) return;
            sleep(150);
        }
        throw new AssertionError("Active fines for " + playerUuid + " did not reach " + expected
                + " within " + timeout + " (last seen: " + last + ")");
    }

    /** Wait until the member count of an account reaches {@code expected}. */
    public static void waitForMemberCount(int accountId, int expected, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        int last = -1;
        while (System.nanoTime() < deadline) {
            last = memberCount(accountId);
            if (last == expected) return;
            sleep(150);
        }
        throw new AssertionError("Member count for account " + accountId + " did not reach " + expected
                + " within " + timeout + " (last seen: " + last + ")");
    }

    /** Wait until the authorizer count of an account reaches {@code expected}. */
    public static void waitForAuthorizerCount(int accountId, int expected, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        int last = -1;
        while (System.nanoTime() < deadline) {
            last = authorizerCount(accountId);
            if (last == expected) return;
            sleep(150);
        }
        throw new AssertionError("Authorizer count for account " + accountId + " did not reach " + expected
                + " within " + timeout + " (last seen: " + last + ")");
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
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            BigDecimal bal = personalBalance(ownerUuid).orElse(null);
            if (bal != null) return bal;
            sleep(150);
        }
        throw new AssertionError("Treasury did not seed a balance for " + ownerUuid + " within " + timeout);
    }

    /** Wait until the materialised balance for {@code accountId} equals {@code expected}. */
    public static void waitForAccountBalanceById(int accountId, BigDecimal expected, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        BigDecimal last = null;
        while (System.nanoTime() < deadline) {
            last = accountBalanceById(accountId).orElse(null);
            if (last != null && last.compareTo(expected) == 0) return;
            sleep(150);
        }
        throw new AssertionError(
                "Account " + accountId + " balance did not reach " + expected
                        + " within " + timeout + " (last seen: " + last + ")");
    }

    /** Wait until the personal balance equals {@code expected}, comparing scale-insensitively. */
    public static void waitForPersonalBalance(UUID ownerUuid, BigDecimal expected, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        BigDecimal last = null;
        while (System.nanoTime() < deadline) {
            last = personalBalance(ownerUuid).orElse(null);
            if (last != null && last.compareTo(expected) == 0) return;
            sleep(150);
        }
        throw new AssertionError(
                "Personal balance for " + ownerUuid + " did not reach " + expected + " within " + timeout
                        + " (last seen: " + last + ")");
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

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", e);
        }
    }

    /**
     * Test-helper "settle" delay between two consecutive Treasury commands.
     * Treasury's command handlers are {@code @Async} and run on a per-plugin
     * single-threaded scheduler; submitting commands faster than the
     * scheduler can process can cause the second command to silently miss
     * its chat response window. 750 ms is conservative; tighten if you have
     * evidence this is over-padded.
     */
    public static void asyncSettle() {
        sleep(750);
    }

    private Ledger() {}
}
