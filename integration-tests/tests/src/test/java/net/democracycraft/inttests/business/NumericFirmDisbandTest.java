package net.democracycraft.inttests.business;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

class NumericFirmDisbandTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void proprietorCanDisbandUsingNumericIdForPromptOrConfirmation(boolean numericConfirmation) {
        try (MockPlayer owner = MockPlayer.connectLocal(Usernames.fresh("DNum"))) {
            Ledger.waitForPersonalBalance(owner.getUuid(), TIMEOUT);
            String firm = Usernames.fresh("DNumF");
            Ops.run(owner.getUsername(), () -> {
                command(owner, "/business create " + firm);
                BusinessLedger.waitForFirm(firm, TIMEOUT);
                command(owner, "/paya business " + firm + " 23.45 NumericSeed");
            });
            int firmId = BusinessLedger.waitForFirm(firm, TIMEOUT);
            int account = BusinessLedger.firmDefaultAccountId(firmId).orElseThrow();
            Ledger.waitForAccountBalanceById(account, new BigDecimal("23.45"), TIMEOUT);
            Perms.grant(owner.getUsername(), "business.disband");
            long checkpoint = Ledger.transactionCheckpoint();
            String promptTarget = numericConfirmation ? firm : Integer.toString(firmId);
            var prompt = command(owner, "/business disband " + promptTarget);
            if (!numericConfirmation) {
                assertAll(
                        () -> assertThat(prompt).anyMatch(m -> m.contains("is permanent") && m.contains("$23.45")),
                        () -> assertThat(BusinessLedger.firmIsArchived(firm)).isFalse(),
                        () -> assertThat(Ledger.accountBalanceById(account).orElseThrow()).isEqualByComparingTo("23.45"),
                        () -> assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint));
            } else {
                assertThat(prompt).anyMatch(m -> m.contains("is permanent"));
            }
            var confirmed = command(owner, "/firm disband " + (numericConfirmation ? firmId : firm) + " confirm");
            assertAll(
                    () -> assertThat(confirmed).anyMatch(m -> m.contains("has been disbanded, and funds have been returned")),
                    () -> assertThat(BusinessLedger.firmIsArchived(firm)).isTrue(),
                    () -> assertThat(BusinessLedger.activeFirmAccountCount(firmId)).isZero(),
                    () -> assertThat(Ledger.accountBalanceById(account).orElseThrow()).isEqualByComparingTo("0"),
                    () -> assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("10000"),
                    () -> assertThat(Ledger.initiatedPostingsAfter(owner.getUuid(), checkpoint)).hasSize(2),
                    () -> assertThat(Ledger.initiatedTransactionCountAfter(owner.getUuid(), checkpoint)).isEqualTo(1));
        }
    }

    private static List<String> command(MockPlayer player, String command) {
        player.drainMessages();
        player.chatCommand(command);
        Barrier.settle();
        return player.collectedMessages();
    }
}
