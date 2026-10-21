package net.democracycraft.inttests.business;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

class FirmBalanceOverflowTest {
    @ParameterizedTest
    @ValueSource(ints = {214748366, 429496731, Integer.MAX_VALUE})
    void extremePageReturnsEmptyWithoutChangingLedger(int page) {
        try (MockPlayer player = MockPlayer.connectLocal(Usernames.fresh("FMax"))) {
            Ledger.waitForPersonalBalance(player.getUuid(), Duration.ofSeconds(10));
            Perms.grant(player.getUsername(), "business.baltop");
            long checkpoint = Ledger.transactionCheckpoint();
            player.drainMessages();
            player.chatCommand("/business baltop " + page);
            Barrier.settle();
            assertAll(
                    () -> assertThat(player.collectedMessages()).anyMatch(m -> m.contains("No firms to display.")),
                    () -> assertThat(Ledger.personalBalance(player.getUuid()).orElseThrow()).isEqualByComparingTo("10000"),
                    () -> assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint));
        }
    }
}
