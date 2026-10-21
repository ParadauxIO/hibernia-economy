package net.democracycraft.inttests.restapi;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "IT_SERVER_DIR", matches = ".+")
class AdminDisbandMissingProprietorTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void absentPersonalAccountNeverMintsFundsAndFundedRetryWorksAfterJoin(boolean funded) throws Exception {
        try (MockPlayer creator = MockPlayer.connectLocal(Usernames.fresh("MpCr"))) {
            Ledger.waitForPersonalBalance(creator.getUuid(), TIMEOUT);
            String futureName = Usernames.fresh("MpNew");
            UUID futureId = UUID.nameUUIDFromBytes(("OfflinePlayer:" + futureName).getBytes(StandardCharsets.UTF_8));
            String firm = Usernames.fresh("MpFrm");
            setup(creator, "/business create " + firm);
            int id = BusinessLedger.waitForFirm(firm, TIMEOUT);
            int primary = BusinessLedger.firmDefaultAccountId(id).orElseThrow();
            setup(creator, "/business account create " + firm + " Reserve");
            int reserve = BusinessLedger.firmAccountIdAt(id, 1);
            if (funded) setup(creator, "/paya account " + reserve + " 23.45 MissingProprietor");
            // Model a legacy/imported firm owned by somebody who has never joined.
            // This is fixture state, not a test of the ownership-transfer workflow.
            try (var c = DriverManager.getConnection(DevServer.DB_URL, DevServer.DB_USER, DevServer.DB_PASSWORD);
                 var q = c.prepareStatement("UPDATE firm SET proprietor_uuid_bin = UUID_TO_BIN(?) WHERE firm_id = ?")) {
                q.setString(1, futureId.toString()); q.setInt(2, id);
                assertThat(q.executeUpdate()).isEqualTo(1);
            }
            assertThat(BusinessLedger.firmProprietor(id)).hasValue(futureId);
            assertThat(personalAccounts(futureId)).isZero();
            String service = ApiTokens.mintServiceKey(creator.getUuid()).token();
            long checkpoint = Ledger.transactionCheckpoint();
            var response = disband(service, id);
            assertThat(personalAccounts(futureId)).isZero();
            assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint);
            if (!funded) {
                assertThat(response.status()).as("%s", response.body()).isEqualTo(200);
                assertThat(response.json().path("accounts").size()).isEqualTo(2);
                for (var row : response.json().path("accounts")) {
                    assertThat(row.path("sweptAmount").isNull()).isTrue();
                    assertThat(row.path("toAccountId").isNull()).isTrue();
                    assertThat(row.path("archived").asBoolean()).isTrue();
                }
                assertThat(Ledger.personalBalance(creator.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
            } else {
                assertThat(response.status()).as("%s", response.body()).isEqualTo(422);
                assertThat(response.error()).isEqualTo("PROPRIETOR_NO_PERSONAL_ACCOUNT");
                assertThat(BusinessLedger.firmIsArchived(firm)).isFalse();
                assertThat(BusinessLedger.firmDefaultAccountId(id)).hasValue(primary);
                assertThat(BusinessLedger.activeFirmAccountCount(id)).isEqualTo(2);
                archived(primary, false);
                archived(reserve, false);
                assertThat(Ledger.accountBalanceById(reserve).orElseThrow()).isEqualByComparingTo("23.45");
                assertThat(Ledger.postingsCountForAccount(reserve)).isEqualTo(1);
                assertThat(Ledger.personalBalance(creator.getUuid()).orElseThrow()).isEqualByComparingTo("9976.55");
                try (MockPlayer proprietor = MockPlayer.connectLocal(futureName)) {
                    assertThat(proprietor.getUuid()).isEqualTo(futureId);
                    Ledger.waitForPersonalBalance(futureId, TIMEOUT);
                    assertThat(personalAccounts(futureId)).isEqualTo(1);
                    long beforeRetry = Ledger.transactionCheckpoint();
                    var retried = disband(service, id);
                    assertThat(retried.status()).as("%s", retried.body()).isEqualTo(200);
                    int destination = Ledger.personalAccountId(futureId).orElseThrow();
                    assertThat(retried.json().path("accounts").size()).isEqualTo(2);
                    var payout = retried.json().path("accounts").get(1);
                    assertThat(payout.path("accountId").asInt()).isEqualTo(reserve);
                    assertThat(payout.path("sweptAmount").asText()).isEqualTo("23.45");
                    assertThat(payout.path("toAccountId").asInt()).isEqualTo(destination);
                    assertThat(Ledger.personalBalance(futureId).orElseThrow()).isEqualByComparingTo("10023.45");
                    assertThat(Ledger.personalBalance(creator.getUuid()).orElseThrow()).isEqualByComparingTo("9976.55");
                    var postings = Ledger.initiatedPostingsAfter(futureId, beforeRetry);
                    assertThat(postings).hasSize(2);
                    assertThat(postings.stream().filter(p -> p.accountId() == reserve).map(Ledger.Posting::amount).toList())
                            .containsExactly(new BigDecimal("-23.45"));
                    assertThat(postings.stream().filter(p -> p.accountId() == destination).map(Ledger.Posting::amount).toList())
                            .containsExactly(new BigDecimal("23.45"));
                    assertThat(Ledger.initiatedTransactionCountAfter(futureId, beforeRetry)).isEqualTo(1);
                }
            }
            assertThat(BusinessLedger.firmIsArchived(firm)).isTrue();
            assertThat(BusinessLedger.firmDefaultAccountId(id)).isEmpty();
            assertThat(BusinessLedger.activeFirmAccountCount(id)).isZero();
            assertThat(BusinessLedger.firmAccountCount(id)).isEqualTo(2);
            archived(primary, true);
            archived(reserve, true);
            assertThat(Ledger.accountBalanceById(primary).orElseThrow()).isEqualByComparingTo("0");
            assertThat(Ledger.accountBalanceById(reserve).orElseThrow()).isEqualByComparingTo("0");
        }
    }
    private static int personalAccounts(UUID owner) throws Exception {
        try (var c = DriverManager.getConnection(DevServer.DB_URL, DevServer.DB_USER, DevServer.DB_PASSWORD);
             var q = c.prepareStatement("SELECT COUNT(*) FROM accounts WHERE account_type = 'PERSONAL' AND owner_uuid_bin = UUID_TO_BIN(?)")) {
            q.setString(1, owner.toString());
            try (var r = q.executeQuery()) { assertThat(r.next()).isTrue(); return r.getInt(1); }
        }
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
