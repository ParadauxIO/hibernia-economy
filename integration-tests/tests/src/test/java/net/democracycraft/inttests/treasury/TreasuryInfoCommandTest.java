package net.democracycraft.inttests.treasury;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.Ops;
import net.democracycraft.inttests.support.Usernames;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Treasury's root {@code /treasury} command. Prints plugin info per the
 * {@code treasury.info} message key — flattens to "Treasury vX.Y.Z - Double-entry
 * economy system".
 */
class TreasuryInfoCommandTest {

    @Test
    void treasuryRootShowsPluginInfo() {
        String username = Usernames.fresh("Trs");
        try (MockPlayer p = MockPlayer.connectLocal(username)) {
            // Use the dedicated /tr alias — `/treasury` is also an alias for
            // /balance, /pay, /baltop, /transactions; Brigadier registers
            // them all under the same root, so plain `/treasury` ambiguously
            // resolves and the no-args info handler may not be reached.
            // /tr is unique to TreasuryCommand.
            p.drainMessages();
            p.chatCommand("/tr");
            String response = p.awaitMessageContaining("Treasury", Duration.ofSeconds(3));
            assertThat(response).contains("Double-entry");
        }
    }
}
