package net.democracycraft.inttests.treasury;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class TransactionHistoryPaginationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @ParameterizedTest
    @ValueSource(strings = {"transactions", "txns"})
    void historyPagesContainExactOrderedPaymentsAndRevocationStopsReading(String root) {
        try (MockPlayer sender = MockPlayer.connectLocal(Usernames.fresh("Hist"));
             MockPlayer recipient = MockPlayer.connectLocal(Usernames.fresh("Dst"))) {
            Ledger.waitForPersonalBalance(sender.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(recipient.getUuid(), TIMEOUT);
            int account = Ledger.personalAccountId(sender.getUuid()).orElseThrow();
            long checkpoint = Ledger.transactionCheckpoint();
            Perms.grant(sender.getUsername(), "treasury.transactions");
            Perms.grant(sender.getUsername(), "griefprevention.spam");
            RconClient.send("op " + recipient.getUsername());
            BigDecimal balance = new BigDecimal("10000");
            try {
                for (int i = 1; i <= 12; i++) {
                    BigDecimal amount = BigDecimal.valueOf(100 + i, 2);
                    recipient.chatCommand("/paya player " + sender.getUsername() + " " + amount + " HistoryRow" + i);
                    balance = balance.subtract(amount);
                    Ledger.waitForPersonalBalance(recipient.getUuid(), balance, TIMEOUT);
                    Barrier.settle();
                }
            } finally {
                RconClient.send("deop " + recipient.getUsername());
            }
            var postings = Ledger.initiatedPostingsAfter(recipient.getUuid(), checkpoint);
            assertThat(postings).hasSize(24);
            var credits = postings.stream().filter(p -> p.accountId() == account).toList();
            assertThat(credits).hasSize(12);
            List<String> first = read(sender, root, "Page 1/2");
            assertThat(first).hasSize(10);
            for (int j = 0; j < 10; j++) assertPayment(first.get(j), credits.get(11 - j), 12 - j);
            assertThat(sender.collectedMessages()).anyMatch(m -> m.contains("/transactions 2"));
            List<String> last = read(sender, root + " 2", "Page 2/2");
            assertThat(last).hasSize(3);
            assertPayment(last.get(0), credits.get(1), 2);
            assertPayment(last.get(1), credits.get(0), 1);
            assertThat(last.get(2)).contains("+$10,000.00");
            assertThat(sender.collectedMessages()).noneMatch(m -> m.contains("for the next page"));
            assertThat(read(sender, root + " 0", "Page 1/2")).isEqualTo(first);
            sender.drainMessages();
            sender.chatCommand("/" + root + " 2147483647");
            sender.awaitMessageContaining("No transactions", TIMEOUT);
            Barrier.settle();
            assertThat(sender.collectedMessages()).noneMatch(m -> m.startsWith("#"));
            sender.drainMessages();
            sender.chatCommand("/" + root + " help");
            assertThat(sender.awaitMessageContaining("Transactions Help", TIMEOUT)).contains("export");
            RconClient.send("lp user " + sender.getUsername() + " permission set treasury.transactions false");
            Await.until("history permission revoked", () -> Perms.check(sender.getUsername(), "treasury.transactions"),
                    r -> r.contains("has=false"), TIMEOUT);
            sender.drainMessages();
            sender.chatCommand("/" + root);
            Barrier.settle();
            assertThat(sender.collectedMessages()).noneMatch(m -> m.startsWith("#") || m.contains("HistoryRow"));
            assertThat(Ledger.personalBalance(sender.getUuid()).orElseThrow()).isEqualByComparingTo("10012.78");
            assertThat(Ledger.personalBalance(recipient.getUuid()).orElseThrow()).isEqualByComparingTo("9987.22");
            assertThat(Ledger.initiatedPostingsAfter(recipient.getUuid(), checkpoint)).isEqualTo(postings);
        }
    }

    private static List<String> read(MockPlayer player, String command, String page) {
        player.drainMessages();
        player.chatCommand("/" + command);
        assertThat(player.awaitMessageContaining("Transactions", TIMEOUT)).contains(page);
        Barrier.settle();
        return player.collectedMessages().stream().filter(m -> m.startsWith("#")).toList();
    }

    private static void assertPayment(String line, Ledger.Posting posting, int number) {
        assertThat(line).startsWith("#" + posting.transactionId() + " ")
                .contains("+$" + BigDecimal.valueOf(100 + number, 2).toPlainString(), "HistoryRow" + number + " ");
    }
}
