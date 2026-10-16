package net.democracycraft.inttests.treasury;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.Barrier;
import net.democracycraft.inttests.support.Ledger;
import net.democracycraft.inttests.support.Ops;
import net.democracycraft.inttests.support.Usernames;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Treasury's {@code /balance} (alias {@code /bal}, {@code /money}) command.
 *
 * <ul>
 *   <li>{@code /balance} — own PERSONAL balance (default permission)</li>
 *   <li>{@code /balance &lt;target&gt;} — others' balance (requires
 *       {@code treasury.balance.others}, op-only by default)</li>
 * </ul>
 */
class BalanceCommandTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    @Test
    void selfBalanceShowsTheStartingBalance() {
        String username = Usernames.fresh("Bal");
        try (MockPlayer p = MockPlayer.connectLocal(username)) {
            // Wait for first-join seeding to complete.
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);

            p.drainMessages();
            p.chatCommand("/balance");
            String response = p.awaitMessageContaining("Your balance", TIMEOUT);

            // Treasury's MiniMessage output flattens to plain text containing
            // the formatted amount. Default starting balance is $10,000.00.
            assertThat(response).contains("10,000");
        }
    }

    @Test
    void otherBalanceRequiresOpAndShowsTheTargetsBalance() {
        String alice = Usernames.fresh("BalA");
        String bob = Usernames.fresh("BalB");

        try (MockPlayer aliceClient = MockPlayer.connectLocal(alice);
             MockPlayer bobClient = MockPlayer.connectLocal(bob)) {

            Ledger.waitForPersonalBalance(aliceClient.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(bobClient.getUuid(), TIMEOUT);

            Ops.run(alice, () -> {
                aliceClient.drainMessages();
                aliceClient.chatCommand("/balance " + bob);
                String response = aliceClient.awaitMessageContaining(bob, TIMEOUT);
                assertThat(response).contains("balance").contains("10,000");
            });
        }
    }

    @Test
    void otherBalanceRejectsNeverJoinedPlayer() {
        // `/balance <target>` resolves <target> through Hibernia's
        // OfflinePlayerResolver, which reads the server's player cache only. A
        // name that never connected is rejected by the framework before
        // BalanceCommand runs ("Invalid target: <name>").
        // Asserts the balance path is never reached for phantom UUIDs (which
        // would otherwise mint a starting-balance row via the faucet — a real
        // exploit class the BalanceCommand comment calls out).
        String op = Usernames.fresh("Bun");
        try (MockPlayer p = MockPlayer.connectLocal(op)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(op, () -> {
                p.drainMessages();
                // ≤16 chars so it is a well-formed player name, not rejected for length.
                String neverJoined = "NvrJ" + Long.toHexString(System.nanoTime() & 0xFFFFFFFFFFL);
                p.chatCommand("/balance " + neverJoined);
                String r = p.awaitMessageContaining("Invalid target", TIMEOUT);
                assertThat(r).contains(neverJoined);
                UUID phantom = UUID.nameUUIDFromBytes(
                        ("OfflinePlayer:" + neverJoined).getBytes(StandardCharsets.UTF_8));
                assertThat(Ledger.personalAccountExists(phantom))
                        .as("a balance lookup must not mint an account for a never-joined name")
                        .isFalse();
            });
        }
    }

    @Test
    void otherBalanceDeniedWithoutPermission() {
        // /balance <target> is gated by treasury.balance.others (default: op).
        // Without Ops.run the brigadier requires-predicate hides the subroute
        // entirely; Paper sends the vanilla "unknown command" translation.
        String alice = Usernames.fresh("BdA");
        String bob = Usernames.fresh("BdB");
        try (MockPlayer a = MockPlayer.connectLocal(alice);
             MockPlayer b = MockPlayer.connectLocal(bob)) {
            Ledger.waitForPersonalBalance(a.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(b.getUuid(), TIMEOUT);
            a.drainMessages();
            a.chatCommand("/balance " + bob);
            // Either an unknown-command message or the vanilla translation key
            // — both are acceptable; the assertion is "denied, not the target's
            // balance".
            String r = a.awaitMessage(m -> !m.isBlank(), TIMEOUT);
            Barrier.settle();
            assertThat(r).doesNotContain("10,000");
        }
    }
}
