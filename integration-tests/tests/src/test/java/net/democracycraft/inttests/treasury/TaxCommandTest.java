package net.democracycraft.inttests.treasury;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.Ledger;
import net.democracycraft.inttests.support.Ops;
import net.democracycraft.inttests.support.RconClient;
import net.democracycraft.inttests.support.Usernames;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Treasury's {@code /tax} admin command.
 *
 * <ul>
 *   <li>{@code /tax status} — current cycle schedule overview (chat output)</li>
 *   <li>{@code /tax trigger &lt;cycle&gt;} — fire DAILY/WEEKLY/MONTHLY immediately</li>
 * </ul>
 */
class TaxCommandTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void taxStatusReturnsTheCurrentSchedule() {
        String admin = Usernames.fresh("Tx1");
        try (MockPlayer a = MockPlayer.connectLocal(admin)) {
            Ops.run(admin, () -> {
                a.drainMessages();
                a.chatCommand("/tax status");
                // Treasury sends a "Tax Status" header followed by per-cycle
                // detail lines; one chat line is enough to confirm the
                // command is wired up and dispatching.
                String response = a.awaitMessageContaining("Tax Status", TIMEOUT);
                assertThat(response).contains("Tax Status");
            });
        }
    }

    @Test
    void taxTriggerDailyDoesNotErrorOnEmptyState() {
        // Trigger via RCON (console has treasury.admin.tax via op-bypass for op
        // permissions, and 'tax trigger' is op-default).
        String out = RconClient.send("tax trigger daily");
        assertThat(out).doesNotContain("Exception").doesNotContain("Internal");
    }

}
