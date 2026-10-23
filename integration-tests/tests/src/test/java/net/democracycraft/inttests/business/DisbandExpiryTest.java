package net.democracycraft.inttests.business;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class DisbandExpiryTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Test
    void expiredConfirmationDoesNotDrainAndFreshPromptWorksAcrossAliases() {
        try (MockPlayer owner = MockPlayer.connectLocal(Usernames.fresh("DExp"))) {
            Ledger.waitForPersonalBalance(owner.getUuid(), TIMEOUT);
            String firm = Usernames.fresh("DExpF");
            Ops.run(owner.getUsername(), () -> {
                command(owner, "/business create " + firm);
                BusinessLedger.waitForFirm(firm, TIMEOUT);
                command(owner, "/paya business " + firm + " 23.45 ExpirySeed");
            });
            int firmId = BusinessLedger.waitForFirm(firm, TIMEOUT);
            int account = BusinessLedger.firmDefaultAccountId(firmId).orElseThrow();
            Ledger.waitForAccountBalanceById(account, new BigDecimal("23.45"), TIMEOUT);
            Perms.grant(owner.getUsername(), "business.disband");
            Perms.grant(owner.getUsername(), "griefprevention.spam");
            long checkpoint = Ledger.transactionCheckpoint();
            assertThat(command(owner, "/business disband " + firm))
                    .anyMatch(m -> m.contains("is permanent") && m.contains("$23.45") && m.contains("within 60s"));
            // The contract is real wall-clock expiry. Do not replace the production
            // clock, retry the confirm, or shorten the configured/default lifetime.
            long promptedAt = System.nanoTime();
            Await.untilTrue("61 real seconds after the delivered confirmation prompt",
                    () -> System.nanoTime() - promptedAt >= Duration.ofSeconds(61).toNanos(),
                    Duration.ofSeconds(70));
            assertThat(command(owner, "/firm disband " + firm + " confirm"))
                    .anyMatch(m -> m.contains("No pending disband for " + firm));
            assertThat(BusinessLedger.firmIsArchived(firm)).isFalse();
            assertThat(BusinessLedger.activeFirmAccountCount(firmId)).isEqualTo(1);
            assertThat(BusinessLedger.firmDefaultAccountId(firmId)).hasValue(account);
            assertThat(Ledger.accountBalanceById(account).orElseThrow()).isEqualByComparingTo("23.45");
            assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("9976.55");
            assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint);

            assertThat(command(owner, "/company disband " + firm)).anyMatch(m -> m.contains("is permanent"));
            assertThat(command(owner, "/db disband " + firm + " confirm"))
                    .anyMatch(m -> m.contains("has been disbanded, and funds have been returned"));
            assertThat(BusinessLedger.firmIsArchived(firm)).isTrue();
            assertThat(BusinessLedger.activeFirmAccountCount(firmId)).isZero();
            assertThat(Ledger.accountBalanceById(account).orElseThrow()).isEqualByComparingTo("0");
            assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
            var postings = Ledger.initiatedPostingsAfter(owner.getUuid(), checkpoint);
            assertThat(postings).hasSize(2);
            assertThat(postings.stream().filter(p -> p.accountId() == account).map(Ledger.Posting::amount).toList())
                    .containsExactly(new BigDecimal("-23.45"));
            int personal = Ledger.personalAccountId(owner.getUuid()).orElseThrow();
            assertThat(postings.stream().filter(p -> p.accountId() == personal).map(Ledger.Posting::amount).toList())
                    .containsExactly(new BigDecimal("23.45"));
        }
    }

    private static List<String> command(MockPlayer player, String command) {
        player.drainMessages();
        player.chatCommand(command);
        Barrier.settle();
        return player.collectedMessages();
    }
}
