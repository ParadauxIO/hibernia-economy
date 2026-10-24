package net.democracycraft.inttests.treasury;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;

class BaltopOverflowProbeTest {
    @ParameterizedTest
    @ValueSource(ints = {214748366, 429496731, Integer.MAX_VALUE})
    void extremePageReturnsEmptyWithoutChangingMoney(int page) {
        try (MockPlayer player = MockPlayer.connectLocal(Usernames.fresh("RMax"))) {
            var balance = Ledger.waitForPersonalBalance(player.getUuid(), Duration.ofSeconds(10));
            Perms.grant(player.getUsername(), "treasury.baltop");
            long checkpoint = Ledger.transactionCheckpoint();
            player.drainMessages();
            player.chatCommand("/baltop " + page);
            Barrier.settle();
            assertThat(player.collectedMessages()).anyMatch(m -> m.contains("No balances to display."));
            assertThat(Ledger.personalBalance(player.getUuid()).orElseThrow()).isEqualByComparingTo(balance);
            assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint);
        }
    }
}
