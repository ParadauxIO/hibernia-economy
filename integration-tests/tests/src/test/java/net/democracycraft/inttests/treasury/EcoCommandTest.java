package net.democracycraft.inttests.treasury;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.Ledger;
import net.democracycraft.inttests.support.Ops;
import net.democracycraft.inttests.support.Usernames;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Admin {@code /eco} subcommands: give, take, set, reset.
 *
 * <p>All four are op-only by default. Tests use a mock player + {@link Ops#run}
 * to bracket each command — RCON's output channel for these commands is empty
 * (responses go to the issuing player, not the console sender), so we drive
 * via the player and assert via the DB.
 */
class EcoCommandTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void ecoGiveCreditsTheTarget() {
        String admin = Usernames.fresh("EgA");
        String target = Usernames.fresh("EgT");
        try (MockPlayer a = MockPlayer.connectLocal(admin);
             MockPlayer t = MockPlayer.connectLocal(target)) {

            BigDecimal start = Ledger.waitForPersonalBalance(t.getUuid(), TIMEOUT);

            Ops.run(admin, () -> {
                a.drainMessages();
                a.chatCommand("/eco give " + target + " 1234.56");
                a.awaitMessageContaining("Gave", TIMEOUT);
            });

            Ledger.waitForPersonalBalance(t.getUuid(),
                    start.add(new BigDecimal("1234.56")), TIMEOUT);
        }
    }

    @Test
    void ecoTakeDebitsTheTarget() {
        String admin = Usernames.fresh("EtA");
        String target = Usernames.fresh("EtT");
        try (MockPlayer a = MockPlayer.connectLocal(admin);
             MockPlayer t = MockPlayer.connectLocal(target)) {

            BigDecimal start = Ledger.waitForPersonalBalance(t.getUuid(), TIMEOUT);

            Ops.run(admin, () -> {
                a.drainMessages();
                a.chatCommand("/eco take " + target + " 100");
                a.awaitMessageContaining("Took", TIMEOUT);
            });

            Ledger.waitForPersonalBalance(t.getUuid(),
                    start.subtract(new BigDecimal("100.00")), TIMEOUT);
        }
    }

    @Test
    void ecoSetReplacesTheBalance() {
        String admin = Usernames.fresh("EsA");
        String target = Usernames.fresh("EsT");
        try (MockPlayer a = MockPlayer.connectLocal(admin);
             MockPlayer t = MockPlayer.connectLocal(target)) {

            Ledger.waitForPersonalBalance(t.getUuid(), TIMEOUT);

            Ops.run(admin, () -> {
                a.drainMessages();
                a.chatCommand("/eco set " + target + " 42.42");
                a.awaitMessageContaining("Set", TIMEOUT);
            });

            Ledger.waitForPersonalBalance(t.getUuid(), new BigDecimal("42.42"), TIMEOUT);
        }
    }

    @Test
    void ecoResetReturnsBalanceToStartingValue() {
        String admin = Usernames.fresh("ErA");
        String target = Usernames.fresh("ErT");
        try (MockPlayer a = MockPlayer.connectLocal(admin);
             MockPlayer t = MockPlayer.connectLocal(target)) {

            BigDecimal start = Ledger.waitForPersonalBalance(t.getUuid(), TIMEOUT);

            Ops.run(admin, () -> {
                a.chatCommand("/eco set " + target + " 5");
                a.awaitMessageContaining("Set", TIMEOUT);
                a.drainMessages();
                a.chatCommand("/eco reset " + target);
                a.awaitMessageContaining("Reset", TIMEOUT);
            });

            Ledger.waitForPersonalBalance(t.getUuid(), start, TIMEOUT);
        }
    }
}
