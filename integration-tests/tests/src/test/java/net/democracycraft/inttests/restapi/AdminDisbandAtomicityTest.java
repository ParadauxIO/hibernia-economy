package net.democracycraft.inttests.restapi;

import com.fasterxml.jackson.databind.JsonNode;
import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "IT_SERVER_DIR", matches = ".+")
class AdminDisbandAtomicityTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void failedSweepRollsBackEntireDisbandThenRetryPaysProprietorOnce(int failedPosition) throws Exception {
        try (MockPlayer owner = MockPlayer.connectLocal(Usernames.fresh("DaOwn"));
             MockPlayer operator = MockPlayer.connectLocal(Usernames.fresh("DaOp"))) {
            Ledger.waitForPersonalBalance(owner.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(operator.getUuid(), TIMEOUT);
            String firm = Usernames.fresh("DaFrm");
            setup(owner, "/business create " + firm);
            int id = BusinessLedger.waitForFirm(firm, TIMEOUT);
            int primary = BusinessLedger.firmDefaultAccountId(id).orElseThrow();
            setup(owner, "/business account create " + firm + " Reserve");
            setup(owner, "/business account create " + firm + " Empty");
            int reserve = BusinessLedger.firmAccountIdAt(id, 1);
            int empty = BusinessLedger.firmAccountIdAt(id, 2);
            setup(owner, "/paya account " + primary + " 23.45 AtomicPrimary");
            setup(owner, "/paya account " + reserve + " 67.89 AtomicReserve");
            int personal = Ledger.personalAccountId(owner.getUuid()).orElseThrow();
            String token = ApiTokens.mintServiceKey(operator.getUuid()).token();
            long checkpoint = Ledger.transactionCheckpoint();
            try (var fault = LedgerFaults.rejectDebits(failedPosition == 0 ? primary : reserve)) {
                var response = disband(token, id);
                assertThat(response.status()).as("%s", response.body()).isEqualTo(500);
                assertThat(BusinessLedger.firmIsArchived(firm)).isFalse();
                assertThat(BusinessLedger.firmDefaultAccountId(id)).hasValue(primary);
                assertThat(BusinessLedger.activeFirmAccountCount(id)).isEqualTo(3);
                assertThat(BusinessLedger.firmAccountCount(id)).isEqualTo(3);
                assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("9908.66");
                assertThat(Ledger.accountBalanceById(primary).orElseThrow()).isEqualByComparingTo("23.45");
                assertThat(Ledger.accountBalanceById(reserve).orElseThrow()).isEqualByComparingTo("67.89");
                for (int account : List.of(primary, reserve, empty)) archived(account, false);
                assertThat(Ledger.postingsCountForAccount(primary)).isEqualTo(1);
                assertThat(Ledger.postingsCountForAccount(reserve)).isEqualTo(1);
                assertThat(Ledger.postingsCountForAccount(empty)).isZero();
                assertThat(Ledger.initiatedPostingsAfter(owner.getUuid(), checkpoint)).isEmpty();
                assertThat(Ledger.initiatedTransactionCountAfter(owner.getUuid(), checkpoint)).isZero();
                assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint);
            }
            var response = disband(token, id);
            assertThat(response.status()).as("%s", response.body()).isEqualTo(200);
            JsonNode receipt = response.json();
            assertThat(receipt.size()).isEqualTo(4);
            assertThat(receipt.path("firmId").asInt()).isEqualTo(id);
            assertThat(receipt.path("displayName").asText()).isEqualTo(firm);
            assertThat(receipt.path("archived").asBoolean()).isTrue();
            assertThat(receipt.path("accounts").size()).isEqualTo(3);
            swept(receipt.path("accounts").get(0), primary, "23.45", personal);
            swept(receipt.path("accounts").get(1), reserve, "67.89", personal);
            JsonNode noFunds = receipt.path("accounts").get(2);
            assertThat(noFunds.size()).isEqualTo(4);
            assertThat(noFunds.path("accountId").asInt()).isEqualTo(empty);
            assertThat(noFunds.path("sweptAmount").isNull()).isTrue();
            assertThat(noFunds.path("toAccountId").isNull()).isTrue();
            assertThat(noFunds.path("archived").asBoolean()).isTrue();
            assertThat(BusinessLedger.firmIsArchived(firm)).isTrue();
            assertThat(BusinessLedger.firmDefaultAccountId(id)).isEmpty();
            assertThat(BusinessLedger.activeFirmAccountCount(id)).isZero();
            assertThat(BusinessLedger.firmAccountCount(id)).isEqualTo(3);
            for (int account : List.of(primary, reserve, empty)) {
                archived(account, true);
                assertThat(Ledger.accountBalanceById(account).orElseThrow()).isEqualByComparingTo("0");
            }
            assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
            assertThat(Ledger.personalBalance(operator.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
            var postings = Ledger.initiatedPostingsAfter(owner.getUuid(), checkpoint);
            assertThat(postings).hasSize(4);
            assertThat(Ledger.initiatedTransactionCountAfter(owner.getUuid(), checkpoint)).isEqualTo(2);
            assertThat(postings.stream().filter(p -> p.accountId() == primary).map(Ledger.Posting::amount).toList()).containsExactly(new BigDecimal("-23.45"));
            assertThat(postings.stream().filter(p -> p.accountId() == reserve).map(Ledger.Posting::amount).toList()).containsExactly(new BigDecimal("-67.89"));
            assertThat(postings.stream().filter(p -> p.accountId() == personal).map(Ledger.Posting::amount).toList())
                    .containsExactlyInAnyOrder(new BigDecimal("23.45"), new BigDecimal("67.89"));
            long afterDisband = Ledger.transactionCheckpoint();
            var repeated = disband(token, id);
            assertThat(repeated.status()).isEqualTo(200);
            assertThat(repeated.json().path("firmId").asInt()).isEqualTo(id);
            assertThat(repeated.json().path("archived").asBoolean()).isTrue();
            assertThat(repeated.json().path("accounts").size()).isZero();
            assertThat(Ledger.transactionCheckpoint()).isEqualTo(afterDisband);
            assertThat(Ledger.initiatedPostingsAfter(owner.getUuid(), checkpoint)).isEqualTo(postings);
        }
    }
    private static void swept(JsonNode row, int account, String amount, int destination) {
        assertThat(row.size()).isEqualTo(4);
        assertThat(row.path("accountId").asInt()).isEqualTo(account);
        assertThat(row.path("sweptAmount").asText()).isEqualTo(amount);
        assertThat(row.path("toAccountId").asInt()).isEqualTo(destination);
        assertThat(row.path("archived").asBoolean()).isTrue();
    }
    private static void archived(int account, boolean expected) throws Exception {
        try (var c = DriverManager.getConnection(DevServer.DB_URL, DevServer.DB_USER, DevServer.DB_PASSWORD);
             var q = c.prepareStatement("SELECT is_archived FROM accounts WHERE account_id = ?")) {
            q.setInt(1, account);
            try (var r = q.executeQuery()) { assertThat(r.next()).isTrue(); assertThat(r.getBoolean(1)).isEqualTo(expected); }
        }
    }
    private static RestApi.Response disband(String token, int id) {
        return RestApi.call("POST", "/admin/firms/" + id + "/disband", token, null);
    }
    private static void setup(MockPlayer player, String command) {
        Ops.run(player.getUsername(), () -> { player.chatCommand(command); Barrier.settle(); });
    }
}
