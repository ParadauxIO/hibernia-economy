package net.democracycraft.inttests;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.DevServer;
import net.democracycraft.inttests.support.Ledger;
import net.democracycraft.inttests.support.Ops;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end for the explorer in-game linking command. The test seeds a code
 * into {@code explorer_link_code} (as the web {@code /link/start} would issue),
 * then a player redeems it with {@code /treasuryapi ui link <code>}; we assert
 * the {@code explorer_identity} row is written for the player's UUID.
 *
 * <p>The seeded sub is fake, so the optional Keycloak attribute push fails and
 * the plugin replies "Account linked" (partial) — the identity link is written
 * regardless, which is what we assert.
 */
class UiLinkE2ETest {

    private static final Duration TIMEOUT = Duration.ofSeconds(8);
    static final String SEEDED_SUB = "e2e-link-test-sub";
    static final String SEEDED_CODE = "E2ELINK1";

    @Test
    void inGameLinkWritesIdentityRow() {
        seedLinkCode();
        try (MockPlayer p = MockPlayer.connectLocal("LinkE2E")) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);

            Ops.run(p.getUsername(), () -> {
                p.chatCommand("/treasuryapi ui link " + SEEDED_CODE);
                p.awaitMessageContaining("linked", TIMEOUT);
            });

            String linkedUuid = Ledger.explorerIdentityUuid(SEEDED_SUB).orElse(null);
            assertThat(linkedUuid).isEqualTo(p.getUuid().toString());
            // Codes are single-use: redeeming must consume the row.
            assertThat(linkCodeExists()).as("link code consumed on redemption").isFalse();
        }
    }

    /**
     * Issue the code the way the web UI would: an unexpired row for
     * {@link #SEEDED_SUB}. Any identity left by an earlier run is cleared so the
     * assertion proves THIS redemption wrote the link.
     */
    private static void seedLinkCode() {
        try (Connection c = connect()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM explorer_identity WHERE keycloak_sub = ?")) {
                ps.setString(1, SEEDED_SUB);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "REPLACE INTO explorer_link_code (code, keycloak_sub, minecraft_name, expires_at) "
                            + "VALUES (?, ?, NULL, NOW() + INTERVAL 10 MINUTE)")) {
                ps.setString(1, SEEDED_CODE);
                ps.setString(2, SEEDED_SUB);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean linkCodeExists() {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM explorer_link_code WHERE code = ?")) {
            ps.setString(1, SEEDED_CODE);
            try (var rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(DevServer.DB_URL, DevServer.DB_USER, DevServer.DB_PASSWORD);
    }
}
