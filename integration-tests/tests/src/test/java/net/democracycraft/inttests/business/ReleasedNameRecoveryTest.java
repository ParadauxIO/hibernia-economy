package net.democracycraft.inttests.business;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@EnabledIfEnvironmentVariable(named = "IT_SERVER_DIR", matches = ".+")
class ReleasedNameRecoveryTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Test
    void numericRecoveryOfReleasedFirmCannotDrainItsReplacement() throws Exception {
        recovery(false);
    }

    @Test
    void paymentByReusedNameMustReachReplacementWhileOldFirmRetainsFunds() throws Exception {
        recovery(true);
    }

    private void recovery(boolean paymentByName) throws Exception {
        try (MockPlayer owner = MockPlayer.connectLocal(Usernames.fresh("RrOwn"));
             MockPlayer successor = MockPlayer.connectLocal(Usernames.fresh("RrNew"));
             MockPlayer admin = MockPlayer.connectLocal(Usernames.fresh("RrAdm"))) {
            for (MockPlayer p : List.of(owner, successor, admin)) Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            String name = Usernames.fresh("RrFrm");
            setup(owner, "/business create " + name);
            int oldId = BusinessLedger.waitForFirm(name, TIMEOUT);
            int oldAccount = BusinessLedger.firmDefaultAccountId(oldId).orElseThrow();
            setup(owner, "/paya account " + oldAccount + " 23.45 RetainedFunds");
            Perms.grant(admin.getUsername(), "business.admin.disband");
            Perms.grant(admin.getUsername(), "business.admin.releasename");
            long beforeFault = Ledger.transactionCheckpoint();
            int newId;
            int newAccount;
            try (var fault = LedgerFaults.rejectDebits(oldAccount)) {
                assertThat(command(admin, "/business admin disband " + oldId))
                        .anyMatch(m -> m.contains("1 of its account(s) could not be emptied"));
                assertThat(BusinessLedger.firmIsArchived(name)).isTrue();
                assertThat(BusinessLedger.activeFirmAccountCount(oldId)).isEqualTo(1);
                assertThat(BusinessLedger.firmDefaultAccountId(oldId)).isEmpty();
                assertThat(Ledger.accountBalanceById(oldAccount).orElseThrow()).isEqualByComparingTo("23.45");
                assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("9976.55");
                assertThat(Ledger.initiatedPostingsAfter(owner.getUuid(), beforeFault)).isEmpty();
                assertThat(Ledger.initiatedTransactionCountAfter(owner.getUuid(), beforeFault)).isZero();
                // Characterize the existing release policy; the recovery/isolation contract follows.
                assertThat(command(admin, "/business admin releasename " + oldId))
                        .anyMatch(m -> m.contains("Released the name " + name));
                setup(successor, "/business create " + name);
                newId = BusinessLedger.waitForFirm(name, TIMEOUT);
                assertThat(newId).isNotEqualTo(oldId);
                newAccount = BusinessLedger.firmDefaultAccountId(newId).orElseThrow();
                assertThat(newAccount).isNotEqualTo(oldAccount);
                setup(successor, "/paya " + (paymentByName ? "business " + name : "account " + newAccount) + " 11 ReplacementFunds");
                int replacement = newAccount;
                assertAll(
                        () -> assertThat(Ledger.accountBalanceById(replacement).orElseThrow()).isEqualByComparingTo("11"),
                        () -> assertThat(Ledger.accountBalanceById(oldAccount).orElseThrow()).isEqualByComparingTo("23.45"),
                        () -> assertThat(Ledger.personalBalance(successor.getUuid()).orElseThrow()).isEqualByComparingTo("9989"));
            }
            long beforeRecovery = Ledger.transactionCheckpoint();
            var replacementHistory = Ledger.initiatedPostingsAfter(successor.getUuid(), 0);
            assertThat(command(admin, "/business admin disband " + oldId))
                    .anyMatch(m -> m.contains("has been disbanded, and funds have been returned"));
            assertThat(BusinessLedger.activeFirmAccountCount(oldId)).isZero();
            assertThat(BusinessLedger.firmAccountCount(oldId)).isEqualTo(1);
            assertThat(Ledger.accountBalanceById(oldAccount).orElseThrow()).isEqualByComparingTo("0");
            assertThat(Ledger.postingsCountForAccount(oldAccount)).isEqualTo(2);
            assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
            var recovered = Ledger.initiatedPostingsAfter(owner.getUuid(), beforeRecovery);
            assertThat(recovered).hasSize(2);
            assertThat(recovered.stream().filter(p -> p.accountId() == oldAccount).map(Ledger.Posting::amount).toList())
                    .containsExactly(new BigDecimal("-23.45"));
            int ownerAccount = Ledger.personalAccountId(owner.getUuid()).orElseThrow();
            assertThat(recovered.stream().filter(p -> p.accountId() == ownerAccount).map(Ledger.Posting::amount).toList())
                    .containsExactly(new BigDecimal("23.45"));
            assertThat(Ledger.initiatedTransactionCountAfter(owner.getUuid(), beforeRecovery)).isEqualTo(1);
            long afterRecovery = Ledger.transactionCheckpoint();
            assertThat(command(admin, "/business admin disband " + oldId))
                    .anyMatch(m -> m.contains("has already been disbanded"));
            assertThat(Ledger.transactionCheckpoint()).isEqualTo(afterRecovery);
            assertThat(BusinessLedger.firmId(name)).hasValue(newId);
            assertArchived(oldId, true);
            assertArchived(newId, false);
            assertThat(BusinessLedger.firmDefaultAccountId(newId)).hasValue(newAccount);
            assertThat(BusinessLedger.activeFirmAccountCount(newId)).isEqualTo(1);
            assertThat(BusinessLedger.firmProprietor(newId)).hasValue(successor.getUuid());
            assertThat(Ledger.accountBalanceById(newAccount).orElseThrow()).isEqualByComparingTo("11");
            assertThat(Ledger.initiatedPostingsAfter(successor.getUuid(), 0)).isEqualTo(replacementHistory);
            assertThat(Ledger.personalBalance(successor.getUuid()).orElseThrow()).isEqualByComparingTo("9989");
            assertThat(Ledger.personalBalance(admin.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
        }
    }
    private static void assertArchived(int firmId, boolean expected) throws java.sql.SQLException {
        try (var c = java.sql.DriverManager.getConnection(DevServer.DB_URL, DevServer.DB_USER, DevServer.DB_PASSWORD);
             var q = c.prepareStatement("SELECT is_archived FROM firm WHERE firm_id = ?")) {
            q.setInt(1, firmId);
            try (var r = q.executeQuery()) { assertThat(r.next()).isTrue(); assertThat(r.getBoolean(1)).isEqualTo(expected); }
        }
    }
    private static void setup(MockPlayer player, String command) {
        Ops.run(player.getUsername(), () -> command(player, command));
    }
    private static List<String> command(MockPlayer player, String command) {
        player.drainMessages(); player.chatCommand(command); Barrier.settle();
        return player.collectedMessages();
    }
}
