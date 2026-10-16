package net.democracycraft.inttests;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.Ledger;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Foundational integration test: when a new player joins for the first time,
 * Treasury fires FirstPlayerJoinEvent and seeds their PERSONAL account with
 * {@code economy.starting-balance} (Treasury config; default 10000).
 *
 * <p>Verifies the whole stack end-to-end:
 * <ul>
 *   <li>MCProtocolLib mock client can complete the offline-mode handshake</li>
 *   <li>Paper accepts the connection, fires the join event</li>
 *   <li>Treasury's listener creates the PERSONAL account row</li>
 *   <li>Treasury transfers the starting balance from {@code starting-balances}
 *       GOVERNMENT → the new PERSONAL account</li>
 *   <li>DB triggers maintain {@code account_balances_mat}</li>
 * </ul>
 *
 * <p>If this passes, the integration-test infrastructure is sound; subsequent
 * tests can layer on commands and cross-plugin assertions.
 */
class FirstJoinSeedsBalanceTest {

    private static final BigDecimal EXPECTED_STARTING_BALANCE = new BigDecimal("10000.00");

    @Test
    void newPlayerGetsStartingBalanceOnFirstJoin() {
        // Use a per-run username so the test always exercises the
        // first-join code path even on repeated runs against the same DB.
        String username = "Mock_" + Long.toHexString(Instant.now().toEpochMilli());
        UUID expectedUuid = offlinePlayerUuid(username);

        // Sanity check: this is a fresh UUID, no prior account.
        assertThat(Ledger.personalAccountExists(expectedUuid))
                .as("PERSONAL account for fresh username should not exist before connect")
                .isFalse();

        try (MockPlayer player = MockPlayer.connectLocal(username)) {
            assertThat(player.isConnected()).isTrue();

            // FirstPlayerJoinEvent fires asynchronously; give Treasury a
            // moment to seed. Polling beats a blind sleep — exit as soon as
            // the row appears, fail if it never does.
            BigDecimal balance = Ledger.waitForPersonalBalance(expectedUuid, Duration.ofSeconds(10));
            assertThat(balance)
                    .as("starting balance for newly joined player %s", username)
                    .isEqualByComparingTo(EXPECTED_STARTING_BALANCE);
        }
    }

    /** Offline-mode UUID — same derivation Bukkit uses. */
    private static UUID offlinePlayerUuid(String username) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes());
    }
}
