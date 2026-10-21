package net.democracycraft.inttests.restapi;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Player-issued account-scoped key crosses Paper -> JWT -> HTTP -> the shared ledger. */
class BusinessAccountScopeTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void accountKeyCanSpendAndReadOnlyItsAccountEvenAfterRotation() {
        String firm = Usernames.fresh("Scope");
        try (MockPlayer owner = MockPlayer.connectLocal(Usernames.fresh("Own"));
             MockPlayer recipient = MockPlayer.connectLocal(Usernames.fresh("Rec"))) {
            Ledger.waitForPersonalBalance(owner.getUuid(), TIMEOUT);
            BigDecimal recipientStart = Ledger.waitForPersonalBalance(recipient.getUuid(), TIMEOUT);
            Ops.run(owner.getUsername(), () -> {
                owner.chatCommand("/business create " + firm);
                owner.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firm, TIMEOUT);
                owner.chatCommand("/business account create " + firm + " Reserve");
                owner.awaitMessageContaining("created account", TIMEOUT);
            });
            int firmId = BusinessLedger.waitForFirm(firm, TIMEOUT);
            BusinessLedger.waitForFirmAccountCountAtLeast(firmId, 2, TIMEOUT);
            int main = BusinessLedger.firmDefaultAccountId(firmId).orElseThrow();
            int reserve = BusinessLedger.firmAccountIdAt(firmId, 1);
            int destination = Ledger.personalAccountId(recipient.getUuid()).orElseThrow();
            Ops.run(owner.getUsername(), () -> {
                owner.chatCommand("/business account deposit " + firm + " " + reserve + " 100");
                owner.awaitMessageContaining("Deposited", TIMEOUT);
                owner.chatCommand("/business account deposit " + firm + " " + main + " 200");
                owner.awaitMessageContaining("Deposited", TIMEOUT);
            });
            Barrier.settle();
            var key = ApiTokens.issueBusinessAccount(owner, firm, reserve);
            assertThat(key.accountId()).isEqualTo(reserve);
            assertThat(key.firmId()).isEqualTo(firmId);
            String token = key.token();
            for (int iteration = 0; iteration < 2; iteration++) {
                int mainPostings = Ledger.postingsCountForAccount(main);
                int reservePostings = Ledger.postingsCountForAccount(reserve);
                int recipientPostings = Ledger.postingsCountForAccount(destination);
                var denied = RestApi.postTransfer(token, transfer(main, destination), null);
                assertThat(denied.status()).as("denial: %s", denied.body()).isEqualTo(403);
                assertThat(RestApi.getTransactions(token, main, null, null).status()).isEqualTo(403);
                assertThat(Ledger.accountBalanceById(main).orElseThrow()).isEqualByComparingTo("200");
                assertThat(Ledger.postingsCountForAccount(main)).isEqualTo(mainPostings);
                assertThat(Ledger.postingsCountForAccount(reserve)).isEqualTo(reservePostings);
                assertThat(Ledger.postingsCountForAccount(destination)).isEqualTo(recipientPostings);

                var paid = RestApi.postTransfer(token, transfer(reserve, destination), null);
                assertThat(paid.status()).as("transfer: %s", paid.body()).isEqualTo(200);
                assertThat(paid.json().path("fromAccountId").asInt()).isEqualTo(reserve);
                assertThat(paid.json().path("toAccountId").asInt()).isEqualTo(destination);
                assertThat(Ledger.accountBalanceById(reserve).orElseThrow())
                        .isEqualByComparingTo(BigDecimal.valueOf(100 - 12L * (iteration + 1)));
                assertThat(Ledger.personalBalance(recipient.getUuid()).orElseThrow())
                        .isEqualByComparingTo(recipientStart.add(BigDecimal.valueOf(12L * (iteration + 1))));
                var history = RestApi.getTransactions(token, reserve, null, null);
                assertThat(history.status()).isEqualTo(200);
                assertThat(history.body()).contains("scoped-live-payment");
                if (iteration == 0) {
                    var rotated = RestApi.postRotate(token);
                    assertThat(rotated.status()).isEqualTo(200);
                    assertThat(RestApi.getTransactions(token, reserve, null, null).status()).isEqualTo(401);
                    token = rotated.json().path("token").asText();
                }
            }
        }
    }

    private static String transfer(int from, int to) {
        return "{\"fromAccountId\":" + from + ",\"toAccountId\":" + to
                + ",\"amount\":\"12.00\",\"memo\":\"scoped-live-payment\"}";
    }
}
