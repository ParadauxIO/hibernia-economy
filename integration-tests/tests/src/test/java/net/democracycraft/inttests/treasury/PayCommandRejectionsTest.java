package net.democracycraft.inttests.treasury;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.Ledger;
import net.democracycraft.inttests.support.Ops;
import net.democracycraft.inttests.support.Usernames;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Additional negative-path coverage for /pay beyond {@link PayCommandTest}.
 */
class PayCommandRejectionsTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void payRejectsZeroAmount() {
        String sender = Usernames.fresh("PzS");
        String target = Usernames.fresh("PzT");
        try (MockPlayer s = MockPlayer.connectLocal(sender);
             MockPlayer t = MockPlayer.connectLocal(target)) {
            BigDecimal start = Ledger.waitForPersonalBalance(s.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(t.getUuid(), TIMEOUT);

            s.drainMessages();
            s.chatCommand("/pay " + target + " 0");
            s.awaitMessageContaining("greater than zero", TIMEOUT);

            assertThat(Ledger.personalBalance(s.getUuid()).orElseThrow())
                    .isEqualByComparingTo(start);
        }
    }

    @Test
    void payRejectsNegativeAmount() {
        String sender = Usernames.fresh("PnS");
        String target = Usernames.fresh("PnT");
        try (MockPlayer s = MockPlayer.connectLocal(sender);
             MockPlayer t = MockPlayer.connectLocal(target)) {
            BigDecimal start = Ledger.waitForPersonalBalance(s.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(t.getUuid(), TIMEOUT);

            s.drainMessages();
            s.chatCommand("/pay " + target + " -50");
            s.awaitMessageContaining("greater than zero", TIMEOUT);

            assertThat(Ledger.personalBalance(s.getUuid()).orElseThrow())
                    .isEqualByComparingTo(start);
        }
    }

    @Test
    void payRejectsUnknownTarget() {
        String sender = Usernames.fresh("PuS");
        try (MockPlayer s = MockPlayer.connectLocal(sender)) {
            BigDecimal start = Ledger.waitForPersonalBalance(s.getUuid(), TIMEOUT);

            s.drainMessages();
            s.chatCommand("/pay NeverHasJoined " + "10");
            s.awaitMessageContaining("never joined", TIMEOUT);

            assertThat(Ledger.personalBalance(s.getUuid()).orElseThrow())
                    .isEqualByComparingTo(start);
        }
    }

    @Test
    void payToGovernmentAccountTransfers() {
        // Positive path: /pay <gov-account-name> debits sender + credits the
        // gov account. The gov account is created by an admin mock-player
        // before the sender pays into it (RCON sender lacks the perms).
        String sender = Usernames.fresh("PgS");
        String admin = Usernames.fresh("PgA");
        String account = "PayGov" + Long.toHexString(System.nanoTime());
        try (MockPlayer s = MockPlayer.connectLocal(sender);
             MockPlayer a = MockPlayer.connectLocal(admin)) {
            BigDecimal start = Ledger.waitForPersonalBalance(s.getUuid(), TIMEOUT);

            Ops.run(admin, () -> {
                a.drainMessages();
                a.chatCommand("/gov account create " + account);
                a.awaitMessageContaining("Created", TIMEOUT);
                Ledger.waitForGovernmentAccount(account, TIMEOUT);
            });

            s.drainMessages();
            s.chatCommand("/pay " + account + " 100");
            s.awaitMessageContaining("Paid", TIMEOUT);

            Ledger.waitForPersonalBalance(s.getUuid(), start.subtract(new BigDecimal("100.00")), TIMEOUT);
        }
    }
}
