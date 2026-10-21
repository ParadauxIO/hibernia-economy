package net.democracycraft.inttests.business;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "IT_SERVER_DIR", matches = ".+")
class PartialDisbandRecoveryTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void proprietorCanResumeAfterDatabaseRecovers(int failedPosition) {
        runRecovery(failedPosition, false);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void administratorCanResumeWithoutRepayingDrainedAccount(int failedPosition) {
        runRecovery(failedPosition, true);
    }

    private void runRecovery(int failedPosition, boolean administrator) {
        try (MockPlayer owner = MockPlayer.connectLocal(Usernames.fresh("DRec"))) {
            Ledger.waitForPersonalBalance(owner.getUuid(), TIMEOUT);
            Ops.run(owner.getUsername(), () -> {
                String firm = Usernames.fresh("DRecF");
                command(owner, "/business create " + firm);
                int firmId = BusinessLedger.waitForFirm(firm, TIMEOUT);
                int primary = BusinessLedger.firmDefaultAccountId(firmId).orElseThrow();
                command(owner, "/business account create " + firm + " Reserve");
                BusinessLedger.waitForFirmAccountCountAtLeast(firmId, 2, TIMEOUT);
                int reserve = BusinessLedger.firmAccountIdAt(firmId, 1);
                command(owner, "/paya account " + primary + " 23.45 PrimarySeed");
                command(owner, "/paya account " + reserve + " 67.89 ReserveSeed");
                Ledger.waitForAccountBalanceById(primary, new BigDecimal("23.45"), TIMEOUT);
                Ledger.waitForAccountBalanceById(reserve, new BigDecimal("67.89"), TIMEOUT);
                int failed = failedPosition == 0 ? primary : reserve;
                int drained = failedPosition == 0 ? reserve : primary;
                BigDecimal retained = new BigDecimal(failedPosition == 0 ? "23.45" : "67.89");
                long checkpoint = Ledger.transactionCheckpoint();
                try (var fault = LedgerFaults.rejectDebits(failed)) {
                    assertPartial(disband(owner, firm), firm);
                    assertThat(BusinessLedger.firmIsArchived(firm)).isTrue();
                    assertThat(BusinessLedger.firmDefaultAccountId(firmId)).isEmpty();
                    assertThat(BusinessLedger.activeFirmAccountCount(firmId)).isEqualTo(1);
                    assertAccount(failed, firmId, retained, false, true);
                    assertAccount(drained, firmId, BigDecimal.ZERO, true, false);
                    assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow())
                            .isEqualByComparingTo(new BigDecimal("10000").subtract(retained));
                    assertThat(Ledger.postingsCountForAccount(failed)).isEqualTo(1);
                    assertThat(Ledger.postingsCountForAccount(drained)).isEqualTo(2);
                    var partial = Ledger.initiatedPostingsAfter(owner.getUuid(), checkpoint);
                    assertThat(partial).hasSize(2);
                    assertThat(Ledger.initiatedTransactionCountAfter(owner.getUuid(), checkpoint)).isEqualTo(1);
                    assertThat(partial).noneMatch(p -> p.accountId() == failed);
                    // A resumed attempt under the same fault must not pay the other account again.
                    if (administrator) assertPartial(resume(owner, firm, true), firm);
                    assertThat(Ledger.initiatedPostingsAfter(owner.getUuid(), checkpoint)).isEqualTo(partial);
                    assertThat(Ledger.initiatedTransactionCountAfter(owner.getUuid(), checkpoint)).isEqualTo(1);
                    assertAccount(failed, firmId, retained, false, true);
                    assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow())
                            .isEqualByComparingTo(new BigDecimal("10000").subtract(retained));
                } catch (Exception e) {
                    throw new RuntimeException("Scoped disband fault failed", e);
                }
                assertThat(resume(owner, firm, administrator)).anyMatch(m -> m.contains("has been disbanded, and funds have been returned"));
                assertThat(BusinessLedger.activeFirmAccountCount(firmId)).isZero();
                assertThat(BusinessLedger.firmAccountCount(firmId)).isEqualTo(2);
                assertAccount(primary, firmId, BigDecimal.ZERO, true, false);
                assertAccount(reserve, firmId, BigDecimal.ZERO, true, false);
                assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
                var postings = Ledger.initiatedPostingsAfter(owner.getUuid(), checkpoint);
                assertThat(postings).hasSize(4);
                assertThat(Ledger.initiatedTransactionCountAfter(owner.getUuid(), checkpoint)).isEqualTo(2);
                assertThat(postings.stream().filter(p -> p.accountId() == primary).map(Ledger.Posting::amount).toList())
                        .containsExactly(new BigDecimal("-23.45"));
                assertThat(postings.stream().filter(p -> p.accountId() == reserve).map(Ledger.Posting::amount).toList())
                        .containsExactly(new BigDecimal("-67.89"));
                int personal = Ledger.personalAccountId(owner.getUuid()).orElseThrow();
                assertThat(postings.stream().filter(p -> p.accountId() == personal).map(Ledger.Posting::amount).toList())
                        .containsExactlyInAnyOrder(new BigDecimal("23.45"), new BigDecimal("67.89"));
                assertThat(postings.stream().map(Ledger.Posting::transactionId).distinct().count()).isEqualTo(2);
            });
        }
    }

    private static List<String> resume(MockPlayer owner, String firm, boolean administrator) {
        return administrator ? command(owner, "/business admin disband " + firm) : disband(owner, firm);
    }

    private static List<String> disband(MockPlayer owner, String firm) {
        assertThat(command(owner, "/business disband " + firm)).anyMatch(m -> m.contains("is permanent"));
        return command(owner, "/firm disband " + firm + " confirm");
    }

    private static void assertPartial(List<String> messages, String firm) {
        assertThat(messages).anyMatch(m -> m.contains(firm + " has been disbanded, but 1 of its account(s) could not be emptied")
                && m.contains("Their funds are untouched") && m.contains("again to finish"));
        assertThat(messages).noneMatch(m -> m.contains("funds have been returned"));
    }

    private static void assertAccount(int account, int firmId, BigDecimal balance, boolean archived, boolean linked) {
        assertThat(Ledger.accountBalanceById(account).orElseThrow()).isEqualByComparingTo(balance);
        try (var c = DriverManager.getConnection(DevServer.DB_URL, DevServer.DB_USER, DevServer.DB_PASSWORD);
             var q = c.prepareStatement("SELECT a.is_archived, EXISTS (SELECT 1 FROM firm_accounts f WHERE f.account_id=a.account_id AND f.firm_id=? AND f.removed_at IS NULL) FROM accounts a WHERE a.account_id=?")) {
            q.setInt(1, firmId); q.setInt(2, account);
            try (var r = q.executeQuery()) {
                assertThat(r.next()).isTrue();
                assertThat(r.getBoolean(1)).isEqualTo(archived);
                assertThat(r.getBoolean(2)).isEqualTo(linked);
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    private static List<String> command(MockPlayer player, String command) {
        player.drainMessages();
        player.chatCommand(command);
        Barrier.settle();
        return player.collectedMessages();
    }
}
