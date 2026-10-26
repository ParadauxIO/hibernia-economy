package net.democracycraft.inttests.treasury;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.Ledger;
import net.democracycraft.inttests.support.Ops;
import net.democracycraft.inttests.support.Usernames;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Treasury's {@code /baltop} command.
 */
class BaltopCommandTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    @Test
    void baltopShowsTopBalancesHeader() {
        String username = Usernames.fresh("BTop");
        try (MockPlayer p = MockPlayer.connectLocal(username)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);

            // /baltop is op-only by default.
            Ops.run(username, () -> {
                p.drainMessages();
                p.chatCommand("/baltop");
                String response = p.awaitMessageContaining("Top Balances", TIMEOUT);
                assertThat(response).contains("Page");
            });
        }
    }

    @Test
    void baltopWithExplicitPageNumberWorks() {
        String username = Usernames.fresh("BTop");
        try (MockPlayer p = MockPlayer.connectLocal(username)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);

            Ops.run(username, () -> {
                p.drainMessages();
                p.chatCommand("/baltop 1");
                p.awaitMessageContaining("Top Balances", TIMEOUT);
            });
        }
    }
}
