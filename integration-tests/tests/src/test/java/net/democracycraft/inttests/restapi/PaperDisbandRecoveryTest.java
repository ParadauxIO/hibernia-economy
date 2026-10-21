package net.democracycraft.inttests.restapi;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.math.BigDecimal;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@EnabledIfEnvironmentVariable(named = "IT_SERVER_DIR", matches = ".+")
class PaperDisbandRecoveryTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Test
    void restRetryOfCompletedPaperDisbandDoesNotPayAgain() throws Exception { run(false); }

    @Test
    void restRetryOfPartialPaperDisbandRecoversRetainedAccount() throws Exception { run(true); }

    private void run(boolean partial) throws Exception {
        try (MockPlayer owner = MockPlayer.connectLocal(Usernames.fresh("PrOwn"))) {
            Ledger.waitForPersonalBalance(owner.getUuid(), TIMEOUT);
            String firm = Usernames.fresh("PrFrm");
            setup(owner, "/business create " + firm);
            int id = BusinessLedger.waitForFirm(firm, TIMEOUT);
            int account = BusinessLedger.firmDefaultAccountId(id).orElseThrow();
            int personal = Ledger.personalAccountId(owner.getUuid()).orElseThrow();
            setup(owner, "/paya account " + account + " 23.45 CrossSurfaceRecovery");
            if (partial) {
                try (var fault = LedgerFaults.rejectDebits(account)) {
                    setup(owner, "/business admin disband " + id);
                }
            } else {
                setup(owner, "/business admin disband " + id);
            }
            assertThat(BusinessLedger.firmIsArchived(firm)).isTrue();
            assertThat(BusinessLedger.firmDefaultAccountId(id)).isEmpty();
            assertThat(BusinessLedger.activeFirmAccountCount(id)).isEqualTo(partial ? 1 : 0);
            assertThat(Ledger.accountBalanceById(account).orElseThrow()).isEqualByComparingTo(partial ? "23.45" : "0");
            assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo(partial ? "9976.55" : "10000");
            long checkpoint = Ledger.transactionCheckpoint();
            String service = ApiTokens.mintServiceKey(owner.getUuid()).token();
            var response = RestApi.call("POST", "/admin/firms/" + id + "/disband", service, null);
            assertThat(response.status()).as("%s", response.body()).isEqualTo(200);
            assertThat(response.json().path("firmId").asInt()).isEqualTo(id);
            assertThat(response.json().path("displayName").asText()).isEqualTo(firm);
            assertThat(response.json().path("archived").asBoolean()).isTrue();
            var postings = Ledger.initiatedPostingsAfter(owner.getUuid(), checkpoint);
            assertAll(
                    () -> assertThat(response.json().path("accounts").size()).isEqualTo(partial ? 1 : 0),
                    () -> assertThat(BusinessLedger.activeFirmAccountCount(id)).isZero(),
                    () -> assertThat(Ledger.accountBalanceById(account).orElseThrow()).isEqualByComparingTo("0"),
                    () -> assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("10000"),
                    () -> assertThat(Ledger.postingsCountForAccount(account)).isEqualTo(2),
                    () -> assertThat(postings).hasSize(partial ? 2 : 0),
                    () -> assertThat(Ledger.initiatedTransactionCountAfter(owner.getUuid(), checkpoint)).isEqualTo(partial ? 1 : 0));
            if (partial) {
                assertThat(response.json().path("accounts").get(0).path("sweptAmount").asText()).isEqualTo("23.45");
                assertThat(response.json().path("accounts").get(0).path("toAccountId").asInt()).isEqualTo(personal);
                assertThat(postings.stream().filter(p -> p.accountId() == account).map(Ledger.Posting::amount).toList()).containsExactly(new BigDecimal("-23.45"));
                assertThat(postings.stream().filter(p -> p.accountId() == personal).map(Ledger.Posting::amount).toList()).containsExactly(new BigDecimal("23.45"));
            } else {
                assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint);
            }
            assertThat(BusinessLedger.firmAccountCount(id)).isEqualTo(1);
        }
    }
    private static void setup(MockPlayer player, String command) {
        Ops.run(player.getUsername(), () -> { player.chatCommand(command); Barrier.settle(); });
    }
}
