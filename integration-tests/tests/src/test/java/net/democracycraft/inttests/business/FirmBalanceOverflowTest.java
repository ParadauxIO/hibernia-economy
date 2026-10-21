package net.democracycraft.inttests.business;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

class FirmBalanceOverflowTest {
    @Test
    void maximumIntegerPageReturnsEmptyWithoutChangingLedger() {
        try (MockPlayer player = MockPlayer.connectLocal(Usernames.fresh("FMax"))) {
            Ledger.waitForPersonalBalance(player.getUuid(), Duration.ofSeconds(10));
            Perms.grant(player.getUsername(), "business.baltop");
            long checkpoint = Ledger.transactionCheckpoint();
            player.drainMessages();
            player.chatCommand("/business baltop 2147483647");
            Barrier.settle();
            assertAll(
                    () -> assertThat(player.collectedMessages()).anyMatch(m -> m.contains("No firms to display.")),
                    () -> assertThat(Ledger.personalBalance(player.getUuid()).orElseThrow()).isEqualByComparingTo("10000"),
                    () -> assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint));
        }
    }
}
