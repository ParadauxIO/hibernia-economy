package net.democracycraft.inttests.treasury;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.Ledger;
import net.democracycraft.inttests.support.Ops;
import net.democracycraft.inttests.support.Usernames;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Negative-path coverage for /tax trigger, exercising the i18n keys. */
class TaxCommandRejectionsTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void triggerRejectsUnknownCycleType() {
        String admin = Usernames.fresh("Ttu");
        try (MockPlayer a = MockPlayer.connectLocal(admin)) {
            Ops.run(admin, () -> {
                a.drainMessages();
                a.chatCommand("/tax trigger annually");
                a.awaitMessageContaining("Unknown cycle type 'annually'", TIMEOUT);
            });
        }
    }

    @Test
    void triggerRejectsRemovedBalanceSubcommand() {
        // `/tax trigger balance <player>` (force one player's balance tax) was
        // removed: under the unified login clock it had to rewrite the player's
        // last-login epoch. `/tax trigger` now takes exactly one <cycle> word, so
        // "balance" is just an unknown cycle type, and the old two-argument form
        // no longer parses at all (vanilla "incorrect argument" — the mock client
        // flattens the translatable to its key). Neither may fire a cycle.
        String admin = Usernames.fresh("Tbu");
        try (MockPlayer a = MockPlayer.connectLocal(admin)) {
            Ops.run(admin, () -> {
                a.drainMessages();
                a.chatCommand("/tax trigger balance");
                a.awaitMessageContaining("Unknown cycle type 'balance'", TIMEOUT);

                a.drainMessages();
                a.chatCommand("/tax trigger balance NeverJoinedTax");
                String r = a.awaitMessage(m -> !m.isBlank(), TIMEOUT);
                assertThat(r).contains("command.unknown.argument");
                assertThat(r).doesNotContain("Firing");
            });
        }
    }
}
