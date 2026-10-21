package net.democracycraft.inttests.business;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

class FirmListOverflowTest {
    @Test
    void maximumIntegerPageReturnsEmptyWithoutChangingLedger() {
        try (MockPlayer player = MockPlayer.connectLocal(Usernames.fresh("FLMax"))) {
            Ledger.waitForPersonalBalance(player.getUuid(), Duration.ofSeconds(10));
            Perms.grant(player.getUsername(), "business.list.all");
            long checkpoint = Ledger.transactionCheckpoint();
            player.drainMessages();
            player.chatCommand("/business list all 2147483647");
            Barrier.settle();
            assertAll(
                    () -> assertThat(player.collectedMessages()).anyMatch(m -> m.contains("No firms on page 2147483647.")),
                    () -> assertThat(Ledger.personalBalance(player.getUuid()).orElseThrow()).isEqualByComparingTo("10000"),
                    () -> assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint));
        }
    }
}
