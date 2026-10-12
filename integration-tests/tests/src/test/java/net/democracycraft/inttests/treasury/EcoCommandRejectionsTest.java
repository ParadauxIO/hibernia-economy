package net.democracycraft.inttests.treasury;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.Ledger;
import net.democracycraft.inttests.support.Ops;
import net.democracycraft.inttests.support.Usernames;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Negative-path coverage for /eco give|take|set|reset. Verifies the four
 * rejection branches: unknown-player (rejected at argument resolution), amount
 * &lt;= 0, insufficient funds (take only). Each rejection asserts both the chat reply AND that the target's
 * balance is unchanged.
 */
class EcoCommandRejectionsTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final String NEVER_JOINED = "NeverJoined123";

    /** The reply names the unknown target, and no account was minted for it. */
    private static void assertRejectedAsUnknownTarget(MockPlayer admin) {
        String r = admin.awaitMessageContaining("Invalid target", TIMEOUT);
        assertThat(r).contains(NEVER_JOINED);
        // On this offline-mode server a fabricated OfflinePlayer for the name
        // would carry exactly this UUID — the account a faucet bug would mint.
        UUID phantom = UUID.nameUUIDFromBytes(
                ("OfflinePlayer:" + NEVER_JOINED).getBytes(StandardCharsets.UTF_8));
        assertThat(Ledger.personalAccountExists(phantom))
                .as("no PERSONAL account may be created for a never-joined name")
                .isFalse();
    }

    @Test
    void ecoGiveRejectsUnknownPlayer() {
        // Hibernia's OfflinePlayerResolver resolves from the server's player
        // cache only (no Bukkit.getOfflinePlayer(name) fallback, which would
        // fabricate a UUID). A name that never joined is rejected by the
        // framework before the handler runs: "Invalid target: <name>".
        String admin = Usernames.fresh("EgU");
        try (MockPlayer a = MockPlayer.connectLocal(admin)) {
            Ops.run(admin, () -> {
                a.drainMessages();
                a.chatCommand("/eco give " + NEVER_JOINED + " 100");
                assertRejectedAsUnknownTarget(a);
            });
        }
    }

    @Test
    void ecoGiveRejectsZeroAmount() {
        String admin = Usernames.fresh("EgZ");
        String target = Usernames.fresh("EgZt");
        try (MockPlayer a = MockPlayer.connectLocal(admin);
             MockPlayer t = MockPlayer.connectLocal(target)) {
            BigDecimal start = Ledger.waitForPersonalBalance(t.getUuid(), TIMEOUT);
            Ops.run(admin, () -> {
                a.drainMessages();
                a.chatCommand("/eco give " + target + " 0");
                a.awaitMessageContaining("greater than zero", TIMEOUT);
            });
            assertThat(Ledger.personalBalance(t.getUuid()).orElseThrow())
                    .isEqualByComparingTo(start);
        }
    }

    @Test
    void ecoGiveRejectsNegativeAmount() {
        String admin = Usernames.fresh("EgN");
        String target = Usernames.fresh("EgNt");
        try (MockPlayer a = MockPlayer.connectLocal(admin);
             MockPlayer t = MockPlayer.connectLocal(target)) {
            BigDecimal start = Ledger.waitForPersonalBalance(t.getUuid(), TIMEOUT);
            Ops.run(admin, () -> {
                a.drainMessages();
                a.chatCommand("/eco give " + target + " -50");
                a.awaitMessageContaining("greater than zero", TIMEOUT);
            });
            assertThat(Ledger.personalBalance(t.getUuid()).orElseThrow())
                    .isEqualByComparingTo(start);
        }
    }

    @Test
    void ecoTakeRejectsUnknownPlayer() {
        String admin = Usernames.fresh("EtU");
        try (MockPlayer a = MockPlayer.connectLocal(admin)) {
            Ops.run(admin, () -> {
                a.drainMessages();
                a.chatCommand("/eco take " + NEVER_JOINED + " 100");
                assertRejectedAsUnknownTarget(a);
            });
        }
    }

    @Test
    void ecoTakeRejectsNegativeAmount() {
        String admin = Usernames.fresh("EtN");
        String target = Usernames.fresh("EtNt");
        try (MockPlayer a = MockPlayer.connectLocal(admin);
             MockPlayer t = MockPlayer.connectLocal(target)) {
            BigDecimal start = Ledger.waitForPersonalBalance(t.getUuid(), TIMEOUT);
            Ops.run(admin, () -> {
                a.drainMessages();
                a.chatCommand("/eco take " + target + " -10");
                a.awaitMessageContaining("greater than zero", TIMEOUT);
            });
            assertThat(Ledger.personalBalance(t.getUuid()).orElseThrow())
                    .isEqualByComparingTo(start);
        }
    }

    @Test
    void ecoTakeRejectsInsufficientFunds() {
        String admin = Usernames.fresh("EtI");
        String target = Usernames.fresh("EtIt");
        try (MockPlayer a = MockPlayer.connectLocal(admin);
             MockPlayer t = MockPlayer.connectLocal(target)) {
            BigDecimal start = Ledger.waitForPersonalBalance(t.getUuid(), TIMEOUT);
            Ops.run(admin, () -> {
                a.drainMessages();
                a.chatCommand("/eco take " + target + " " + start.add(new BigDecimal("100000")).toPlainString());
                a.awaitMessageContaining("insufficient", TIMEOUT);
            });
            // Balance unchanged.
            assertThat(Ledger.personalBalance(t.getUuid()).orElseThrow())
                    .isEqualByComparingTo(start);
        }
    }

    @Test
    void ecoSetRejectsUnknownPlayer() {
        String admin = Usernames.fresh("EsU");
        try (MockPlayer a = MockPlayer.connectLocal(admin)) {
            Ops.run(admin, () -> {
                a.drainMessages();
                a.chatCommand("/eco set " + NEVER_JOINED + " 1000");
                assertRejectedAsUnknownTarget(a);
            });
        }
    }

    @Test
    void ecoSetRejectsNegativeAmount() {
        String admin = Usernames.fresh("EsN");
        String target = Usernames.fresh("EsNt");
        try (MockPlayer a = MockPlayer.connectLocal(admin);
             MockPlayer t = MockPlayer.connectLocal(target)) {
            BigDecimal start = Ledger.waitForPersonalBalance(t.getUuid(), TIMEOUT);
            Ops.run(admin, () -> {
                a.drainMessages();
                a.chatCommand("/eco set " + target + " -1");
                a.awaitMessageContaining("greater than zero", TIMEOUT);
            });
            // Set semantically allows zero — verify this rejection didn't pass through.
            assertThat(Ledger.personalBalance(t.getUuid()).orElseThrow())
                    .isEqualByComparingTo(start);
        }
    }

    @Test
    void ecoSetAcceptsZeroAmount() {
        // /eco set <p> 0 is valid (set balance to zero), unlike give/take.
        String admin = Usernames.fresh("EsZ");
        String target = Usernames.fresh("EsZt");
        try (MockPlayer a = MockPlayer.connectLocal(admin);
             MockPlayer t = MockPlayer.connectLocal(target)) {
            Ledger.waitForPersonalBalance(t.getUuid(), TIMEOUT);
            Ops.run(admin, () -> {
                a.drainMessages();
                a.chatCommand("/eco set " + target + " 0");
                a.awaitMessageContaining("Set", TIMEOUT);
            });
            Ledger.waitForPersonalBalance(t.getUuid(), BigDecimal.ZERO, TIMEOUT);
        }
    }

    @Test
    void ecoResetRejectsUnknownPlayer() {
        String admin = Usernames.fresh("ErU");
        try (MockPlayer a = MockPlayer.connectLocal(admin)) {
            Ops.run(admin, () -> {
                a.drainMessages();
                a.chatCommand("/eco reset " + NEVER_JOINED);
                assertRejectedAsUnknownTarget(a);
            });
        }
    }
}
