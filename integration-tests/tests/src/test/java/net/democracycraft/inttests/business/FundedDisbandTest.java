package net.democracycraft.inttests.business;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class FundedDisbandTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @ParameterizedTest
    @ValueSource(strings = {"business", "firm", "company", "db", "democracybusiness"})
    void confirmationDrainsEveryAccountExactlyOnceAndArchivesLinks(String root) {
        try (MockPlayer owner = MockPlayer.connectLocal(Usernames.fresh("Disb"))) {
            Ledger.waitForPersonalBalance(owner.getUuid(), TIMEOUT);
            Ops.run(owner.getUsername(), () -> {
                String firm = Usernames.fresh("Drain");
                command(owner, "/" + root + " create " + firm);
                int firmId = BusinessLedger.waitForFirm(firm, TIMEOUT);
                int primary = BusinessLedger.firmDefaultAccountId(firmId).orElseThrow();
                command(owner, "/" + root + " account create " + firm + " Reserve");
                BusinessLedger.waitForFirmAccountCountAtLeast(firmId, 2, TIMEOUT);
                int reserve = BusinessLedger.firmAccountIdAt(firmId, 1);
                assertThat(reserve).isNotEqualTo(primary);
                command(owner, "/paya account " + primary + " 23.45 PrimarySeed");
                command(owner, "/paya account " + reserve + " 67.89 ReserveSeed");
                Ledger.waitForAccountBalanceById(primary, new BigDecimal("23.45"), TIMEOUT);
                Ledger.waitForAccountBalanceById(reserve, new BigDecimal("67.89"), TIMEOUT);
                assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("9908.66");
                long checkpoint = Ledger.transactionCheckpoint();
                assertThat(command(owner, "/" + root + " disband " + firm + " confirm"))
                        .anyMatch(m -> m.contains("No pending disband for " + firm));
                assertThat(BusinessLedger.firmIsArchived(firm)).isFalse();
                assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint);
                assertThat(command(owner, "/" + root + " disband " + firm))
                        .anyMatch(m -> m.contains("is permanent") && m.contains("$91.34") && m.contains("name cannot be reused"));
                assertThat(BusinessLedger.firmIsArchived(firm)).isFalse();
                assertThat(BusinessLedger.firmAccountCount(firmId)).isEqualTo(2);
                assertThat(Ledger.accountBalanceById(primary).orElseThrow()).isEqualByComparingTo("23.45");
                assertThat(Ledger.accountBalanceById(reserve).orElseThrow()).isEqualByComparingTo("67.89");
                assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint);
                assertThat(command(owner, "/" + root + " disband " + firm + " confirm"))
                        .anyMatch(m -> m.contains("has been disbanded, and funds have been returned"));
                assertThat(BusinessLedger.firmIsArchived(firm)).isTrue();
                assertThat(BusinessLedger.activeFirmAccountCount(firmId)).isZero();
                assertThat(BusinessLedger.firmAccountCount(firmId)).isEqualTo(2);
                assertThat(BusinessLedger.firmDefaultAccountId(firmId)).isEmpty();
                assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
                for (int account : List.of(primary, reserve)) {
                    assertThat(Ledger.accountBalanceById(account).orElseThrow()).isEqualByComparingTo("0");
                    assertThat(isArchived(account)).isTrue();
                    assertThat(Ledger.postingsCountForAccount(account)).isEqualTo(2);
                }
                var postings = Ledger.initiatedPostingsAfter(owner.getUuid(), checkpoint);
                assertThat(postings).hasSize(4);
                int personal = Ledger.personalAccountId(owner.getUuid()).orElseThrow();
                assertThat(postings.stream().filter(p -> p.accountId() == primary).map(Ledger.Posting::amount).toList())
                        .containsExactly(new BigDecimal("-23.45"));
                assertThat(postings.stream().filter(p -> p.accountId() == reserve).map(Ledger.Posting::amount).toList())
                        .containsExactly(new BigDecimal("-67.89"));
                assertThat(postings.stream().filter(p -> p.accountId() == personal).map(Ledger.Posting::amount).toList())
                        .containsExactlyInAnyOrder(new BigDecimal("23.45"), new BigDecimal("67.89"));
                assertThat(postings.stream().map(Ledger.Posting::transactionId).distinct().count()).isEqualTo(2);
                assertThat(command(owner, "/" + root + " disband " + firm + " confirm"))
                        .anyMatch(m -> m.contains("has already been disbanded"));
                assertThat(Ledger.initiatedPostingsAfter(owner.getUuid(), checkpoint)).isEqualTo(postings);
                assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
            });
        }
    }

    private static boolean isArchived(int account) {
        try (var c = DriverManager.getConnection(DevServer.DB_URL, DevServer.DB_USER, DevServer.DB_PASSWORD);
             var q = c.prepareStatement("SELECT is_archived FROM accounts WHERE account_id = ?")) {
            q.setInt(1, account);
            try (var r = q.executeQuery()) { assertThat(r.next()).isTrue(); return r.getBoolean(1); }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    private static List<String> command(MockPlayer player, String command) {
        player.drainMessages();
        player.chatCommand(command);
        Barrier.settle();
        return player.collectedMessages();
    }
}
