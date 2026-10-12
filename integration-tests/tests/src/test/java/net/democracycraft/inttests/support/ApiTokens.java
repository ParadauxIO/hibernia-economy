package net.democracycraft.inttests.support;

import io.paradaux.mockclient.MockPlayer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Helpers for issuing and inspecting Treasury REST API JWTs.
 *
 * <p>Tokens are shown to the player exactly once and are not stored: the
 * {@code api_keys} row keeps only the key's metadata (id, jti, revoked…). So the
 * token itself is taken from the issue / reissue chat receipt, and everything
 * else from the row.
 */
public final class ApiTokens {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    /** A compact JWS as it appears in the issue / reissue receipt. */
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");

    /** Issues a PERSONAL key for the connected player. Caller drives the player; this
     *  utility issues the command from inside an Ops.run block. Returns the issued key. */
    public static IssuedKey issuePersonal(String username, MockPlayer player) {
        UUID uuid = player.getUuid();
        String token = commandToken(username, player, "/treasuryapi personal issue", "Personal API key");
        return waitForLatestKey(uuid, "PERSONAL", TIMEOUT).withToken(token);
    }

    /** Issues a BUSINESS key bound to the named firm. Sender must be the proprietor. */
    public static IssuedKey issueBusiness(String username, MockPlayer player, String firmName) {
        UUID uuid = player.getUuid();
        String token = commandToken(username, player, "/treasuryapi business issue " + firmName, "Business API key");
        return waitForLatestKey(uuid, "BUSINESS", TIMEOUT).withToken(token);
    }

    /** Reissue a PERSONAL key by id (rotates jti server-side). */
    public static IssuedKey reissuePersonal(String username, MockPlayer player, int keyId) {
        return reissue(username, player, "personal", keyId);
    }

    /** Reissue a key by id; {@code scope} is {@code personal} or {@code business}. */
    public static IssuedKey reissue(String username, MockPlayer player, String scope, int keyId) {
        String token = commandToken(username, player, "/treasuryapi " + scope + " reissue " + keyId, "reissued");
        return waitForKeyById(keyId, TIMEOUT).withToken(token);
    }

    /**
     * Runs a token-minting command and returns the token from its chat receipt: the
     * confirmation ({@code receiptMarker}) followed by the token, in the same chat
     * message or — if the server splits the lines — in the next one.
     */
    private static String commandToken(String username, MockPlayer player, String command, String receiptMarker) {
        String[] receipt = new String[1];
        Ops.run(username, () -> {
            player.drainMessages();
            player.chatCommand(command);
            receipt[0] = player.awaitMessageContaining(receiptMarker, TIMEOUT);
            if (!JWT.matcher(receipt[0]).find()) {
                String confirmation = receipt[0];
                try {
                    receipt[0] = player.awaitMessage(line -> JWT.matcher(line).find(), TIMEOUT);
                } catch (AssertionError e) {
                    throw new AssertionError("'" + command + "' confirmed but showed no token. Receipt: " + confirmation, e);
                }
            }
        });
        Matcher m = JWT.matcher(receipt[0]);
        if (!m.find()) {
            throw new AssertionError("No token in the receipt for '" + command + "': " + receipt[0]);
        }
        return m.group();
    }

    public static void revokePersonal(String username, MockPlayer player, int keyId) {
        Ops.run(username, () -> {
            player.drainMessages();
            player.chatCommand("/treasuryapi personal revoke " + keyId);
            player.awaitMessageContaining("revoked", TIMEOUT);
        });
    }

    public static IssuedKey waitForLatestKey(UUID ownerUuid, String type, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            IssuedKey k = latestKeyFor(ownerUuid, type);
            if (k != null) return k;
            sleep(150);
        }
        throw new AssertionError("No " + type + " key for " + ownerUuid + " within " + timeout);
    }

    public static IssuedKey waitForKeyById(int keyId, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            IssuedKey k = keyById(keyId);
            if (k != null) return k;
            sleep(150);
        }
        throw new AssertionError("Key " + keyId + " not found within " + timeout);
    }

    public static IssuedKey latestKeyFor(UUID ownerUuid, String type) {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT key_id, account_id, firm_id, jwt_id, revoked "
                             + "FROM api_keys "
                             + "WHERE owner_uuid_bin = UNHEX(REPLACE(?, '-', '')) AND key_type = ? "
                             + "ORDER BY key_id DESC LIMIT 1")) {
            ps.setString(1, ownerUuid.toString());
            ps.setString(2, type);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return mapRow(rs, ownerUuid);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static IssuedKey keyById(int keyId) {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT key_id, account_id, firm_id, BIN_TO_UUID(owner_uuid_bin) AS owner_uuid, "
                             + "jwt_id, revoked "
                             + "FROM api_keys WHERE key_id = ?")) {
            ps.setInt(1, keyId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                String uuidStr = rs.getString("owner_uuid");
                return mapRow(rs, UUID.fromString(uuidStr));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** account_id is INT UNSIGNED; firm_id is INT (nullable). Read via getInt + wasNull. */
    private static IssuedKey mapRow(ResultSet rs, UUID owner) throws SQLException {
        int aid = rs.getInt("account_id");
        Integer accountId = rs.wasNull() ? null : aid;
        int fid = rs.getInt("firm_id");
        Integer firmId = rs.wasNull() ? null : fid;
        return new IssuedKey(
                rs.getInt("key_id"),
                accountId,
                firmId,
                owner,
                rs.getString("jwt_id"),
                null,
                rs.getBoolean("revoked"));
    }

    /** Set revoked=1 directly (for testing the revoked-token rejection branch
     *  without needing /treasuryapi personal revoke between two players). */
    public static void revokeInDb(int keyId) {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE api_keys SET revoked = 1 WHERE key_id = ?")) {
            ps.setInt(1, keyId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** Mutate the stored jti so a still-valid signed token fails the
     *  TOKEN_SUPERSEDED check at step 7. */
    public static void poisonJtiInDb(int keyId) {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE api_keys SET jwt_id = ? WHERE key_id = ?")) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setInt(2, keyId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** {@code token} is null for a key read back from the database (it isn't stored). */
    public record IssuedKey(int keyId, Integer accountId, Integer firmId, UUID ownerUuid,
                            String jwtId, String token, boolean revoked) {

        IssuedKey withToken(String token) {
            return new IssuedKey(keyId, accountId, firmId, ownerUuid, jwtId, token, revoked);
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(DevServer.DB_URL, DevServer.DB_USER, DevServer.DB_PASSWORD);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", e);
        }
    }

    private ApiTokens() {}
}
