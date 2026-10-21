package net.democracycraft.inttests.treasury;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;

class BaltopOverflowProbeTest {
    @Test
    void maximumIntegerPageReturnsEmptyWithoutChangingMoney() {
        try (MockPlayer player = MockPlayer.connectLocal(Usernames.fresh("RMax"))) {
            var balance = Ledger.waitForPersonalBalance(player.getUuid(), Duration.ofSeconds(10));
            Perms.grant(player.getUsername(), "treasury.baltop");
            long checkpoint = Ledger.transactionCheckpoint();
            player.drainMessages();
            player.chatCommand("/baltop 2147483647");
            Barrier.settle();
            assertThat(player.collectedMessages()).anyMatch(m -> m.contains("No balances to display."));
            assertThat(Ledger.personalBalance(player.getUuid()).orElseThrow()).isEqualByComparingTo(balance);
            assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint);
        }
    }
}
