package net.democracycraft.inttests.treasury;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GovernmentHistoryAccessTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @ParameterizedTest
    @ValueSource(strings = {"gov", "government"})
    void viewerReadsExactHistoryButCannotSpendAndRemovalRevokesBothReads(String root) {
        try (MockPlayer viewer = MockPlayer.connectLocal(Usernames.fresh("GView"))) {
            Ledger.waitForPersonalBalance(viewer.getUuid(), TIMEOUT);
            for (String node : List.of("treasury.gov", "treasury.gov.account.view", "griefprevention.spam")) {
                Perms.grant(viewer.getUsername(), node);
            }
            String accountName = Usernames.fresh("GHist");
            UUID funder;
            long checkpoint;
            int account;
            // Keep the fixture's repeated funding commands on a separate operator connection.
            try (MockPlayer admin = MockPlayer.connectLocal(Usernames.fresh("GAdm"))) {
                Ledger.waitForPersonalBalance(admin.getUuid(), TIMEOUT);
                funder = admin.getUuid();
                RconClient.send("op " + admin.getUsername());
                Perms.grant(admin.getUsername(), "treasury.gov.*");
                try {
                    admin.chatCommand("/" + root + " account create " + accountName);
                    admin.awaitMessageContaining("Created", TIMEOUT);
                    account = Ledger.waitForGovernmentAccount(accountName, TIMEOUT);
                    admin.chatCommand("/" + root + " account viewer add " + accountName + " " + viewer.getUsername());
                    admin.awaitMessageContaining("read-only view", TIMEOUT);
                    Barrier.settle();
                    checkpoint = Ledger.transactionCheckpoint();
                    BigDecimal total = BigDecimal.ZERO;
                    for (int i = 1; i <= 12; i++) {
                        BigDecimal amount = BigDecimal.valueOf(100 + i, 2);
                        total = total.add(amount);
                        admin.chatCommand("/paya government " + accountName + " " + amount + " GovHistory" + i);
                        Ledger.waitForGovernmentBalance(accountName, total, TIMEOUT);
                        Barrier.settle();
                    }
                } finally {
                    Perms.revoke(admin.getUsername(), "treasury.gov.*");
                    RconClient.send("deop " + admin.getUsername());
                }
            }
            var postings = Ledger.initiatedPostingsAfter(funder, checkpoint);
            assertThat(postings).hasSize(24);
            int accountId = account;
            var credits = postings.stream().filter(p -> p.accountId() == accountId).toList();
            assertThat(credits).hasSize(12);
            long readCheckpoint = Ledger.transactionCheckpoint();
            var first = command(viewer, "/" + root + " account history " + accountName);
            assertThat(first).anyMatch(m -> m.contains(accountName + " Transactions") && m.contains("Page 1/2"));
            assertThat(rows(first)).hasSize(10);
            for (int j = 0; j < 10; j++) assertCredit(rows(first).get(j), credits.get(11 - j), 12 - j);
            assertThat(first).anyMatch(m -> m.contains("for the next page"));
            var second = command(viewer, "/" + root + " account history " + accountName + " 2");
            assertThat(second).anyMatch(m -> m.contains("Page 2/2"));
            assertThat(rows(second)).hasSize(2);
            assertCredit(rows(second).get(0), credits.get(1), 2);
            assertCredit(rows(second).get(1), credits.get(0), 1);
            assertThat(second).noneMatch(m -> m.contains("for the next page"));
            assertThat(rows(command(viewer, "/" + root + " account history " + accountName + " 0"))).isEqualTo(rows(first));
            var empty = command(viewer, "/" + root + " account history " + accountName + " 2147483647");
            assertThat(empty).anyMatch(m -> m.contains("No transactions found for that account."));
            assertThat(rows(empty)).isEmpty();
            assertThat(command(viewer, "/" + root + " account balance " + accountName))
                    .anyMatch(m -> m.contains(accountName + " balance: $12.78"));
            assertDenied(command(viewer, "/" + root + " payout " + accountName + " " + viewer.getUsername() + " 1"));
            RconClient.send(root + " account viewer remove " + accountName + " " + viewer.getUsername());
            Barrier.settle();
            assertDenied(command(viewer, "/" + root + " account balance " + accountName));
            assertDenied(command(viewer, "/" + root + " account history " + accountName));
            assertThat(Ledger.governmentBalance(accountName).orElseThrow()).isEqualByComparingTo("12.78");
            assertThat(Ledger.personalBalance(viewer.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
            assertThat(Ledger.personalBalance(funder).orElseThrow()).isEqualByComparingTo("9987.22");
            assertThat(Ledger.initiatedPostingsAfter(funder, checkpoint)).isEqualTo(postings);
            assertThat(Ledger.transactionCheckpoint()).isEqualTo(readCheckpoint);
        }
    }

    private static List<String> command(MockPlayer player, String command) {
        player.drainMessages();
        player.chatCommand(command);
        Barrier.settle();
        return player.collectedMessages();
    }

    private static List<String> rows(List<String> messages) {
        return messages.stream().filter(m -> m.startsWith("#")).toList();
    }

    private static void assertCredit(String line, Ledger.Posting posting, int number) {
        assertThat(line).startsWith("#" + posting.transactionId() + " ")
                .contains("+$" + BigDecimal.valueOf(100 + number, 2).toPlainString(), "GovHistory" + number + " ");
    }

    private static void assertDenied(List<String> messages) {
        assertThat(messages).anyMatch(m -> m.contains("You do not have access to that account."));
        assertThat(messages).noneMatch(m -> m.startsWith("#") || m.contains("balance:") || m.contains("GovHistory"));
    }
}
