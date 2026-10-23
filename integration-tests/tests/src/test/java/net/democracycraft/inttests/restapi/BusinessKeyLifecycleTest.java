package net.democracycraft.inttests.restapi;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;

class BusinessKeyLifecycleTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void adminInvalidationAndPaperReissuePreserveFirmAndAccountScope(boolean scoped) {
        String firm = Usernames.fresh("BkFir");
        try (var owner = MockPlayer.connectLocal(Usernames.fresh("BkOwn"));
             var peer = MockPlayer.connectLocal(Usernames.fresh("BkPe"))) {
            Ledger.waitForPersonalBalance(owner.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(peer.getUuid(), TIMEOUT);
            Ops.run(owner.getUsername(), () -> {
                owner.chatCommand("/business create " + firm);
                owner.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firm, TIMEOUT);
                owner.chatCommand("/business account create " + firm + " Reserve" + firm);
                owner.awaitMessageContaining("created account", TIMEOUT);
            });
            int firmId = BusinessLedger.waitForFirm(firm, TIMEOUT);
            BusinessLedger.waitForFirmAccountCountAtLeast(firmId, 2, TIMEOUT);
            int primary = BusinessLedger.firmDefaultAccountId(firmId).orElseThrow();
            int reserve = BusinessLedger.firmAccountIdAt(firmId, 1);
            int destination = Ledger.personalAccountId(peer.getUuid()).orElseThrow();
            Ops.run(owner.getUsername(), () -> {
                owner.chatCommand("/business account deposit " + firm + " " + primary + " 23.45");
                owner.awaitMessageContaining("Deposited", TIMEOUT);
                owner.chatCommand("/business account deposit " + firm + " " + reserve + " 67.89");
                owner.awaitMessageContaining("Deposited", TIMEOUT);
            });
            Barrier.settle();
            var key = scoped ? ApiTokens.issueBusinessAccount(owner, firm, reserve)
                    : ApiTokens.issueBusiness(owner.getUsername(), owner, firm);
            String service = ApiTokens.mintServiceKey(peer.getUuid()).token();
            String path = "/admin/api-keys/" + key.keyId();
            assertThat(RestApi.getTransactions(key.token(), reserve, null, null).status()).isEqualTo(200);
            long firstTxn = pay(key.token(), reserve, destination, "1.25");
            feedPayment(key.token(), reserve, firstTxn, "-1.25", destination);
            assertThat(RestApi.getTransactionFeed(key.token(), primary, 0L, 10).status()).isEqualTo(scoped ? 403 : 200);
            long checkpoint = Ledger.transactionCheckpoint();
            var rotated = RestApi.call("POST", path + "/rotate", service, null);
            assertThat(rotated.status()).as("%s", rotated.body()).isEqualTo(200);
            var stored = ApiTokens.keyById(key.keyId());
            assertThat(stored.firmId()).isEqualTo(firmId);
            assertThat(stored.accountId()).isEqualTo(scoped ? reserve : null);
            assertThat(stored.jwtId()).isNotEqualTo(key.jwtId());
            invalid(key.token(), reserve, destination, firstTxn, "TOKEN_SUPERSEDED");
            balances(primary, reserve, destination, "66.64", "10001.25", checkpoint);
            var replacement = ApiTokens.reissue(owner.getUsername(), owner, "business", key.keyId());
            assertThat(replacement.keyId()).isEqualTo(key.keyId());
            assertThat(replacement.firmId()).isEqualTo(firmId);
            assertThat(replacement.accountId()).isEqualTo(scoped ? reserve : null);
            assertThat(replacement.ownerUuid()).isEqualTo(owner.getUuid());
            assertThat(replacement.jwtId()).isNotEqualTo(stored.jwtId()).isNotEqualTo(key.jwtId());
            invalid(key.token(), reserve, destination, firstTxn, "TOKEN_SUPERSEDED");
            if (scoped) {
                var denied = transfer(replacement.token(), primary, destination, "7.89");
                assertThat(denied.status()).as("%s", denied.body()).isEqualTo(403);
                assertThat(RestApi.getTransactions(replacement.token(), primary, null, null).status()).isEqualTo(403);
            } else {
                assertThat(RestApi.getTransactions(replacement.token(), primary, null, null).status()).isEqualTo(200);
            }
            assertThat(RestApi.getTransactionFeed(replacement.token(), primary, 0L, 10).status()).isEqualTo(scoped ? 403 : 200);
            balances(primary, reserve, destination, "66.64", "10001.25", checkpoint);
            long secondTxn = pay(replacement.token(), reserve, destination, "7.89");
            feedPayment(replacement.token(), reserve, secondTxn, "-7.89", destination);
            assertThat(RestApi.getTransactions(replacement.token(), reserve, null, null).status()).isEqualTo(200);
            checkpoint = Ledger.transactionCheckpoint();
            var revoked = RestApi.call("POST", path + "/revoke", service, null);
            assertThat(revoked.status()).isEqualTo(204);
            invalid(replacement.token(), reserve, destination, firstTxn, "TOKEN_REVOKED");
            balances(primary, reserve, destination, "58.75", "10009.14", checkpoint);
            var refused = RestApi.call("POST", path + "/rotate", service, null);
            assertThat(refused.status()).isEqualTo(409);
            assertThat(refused.error()).isEqualTo("TOKEN_REVOKED");
            assertThat(ApiTokens.keyById(key.keyId()).revoked()).isTrue();
            assertThat(ApiTokens.keyById(key.keyId()).jwtId()).isEqualTo(replacement.jwtId());
            assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("9908.66");
            assertThat(BusinessLedger.firmDefaultAccountId(firmId)).hasValue(primary);
            assertThat(BusinessLedger.activeFirmAccountCount(firmId)).isEqualTo(2);
            balances(primary, reserve, destination, "58.75", "10009.14", checkpoint);
        }
    }
    private static void invalid(String token, int source, int destination, long cursor, String error) {
        for (var response : java.util.List.of(transfer(token, source, destination, "11.00"),
                RestApi.getTransactions(token, source, null, null),
                RestApi.getTransactionFeed(token, source, 0L, 10),
                RestApi.getTransactionFeed(token, source, cursor, 10))) {
            assertThat(response.status()).as("%s", response.body()).isEqualTo(401);
            assertThat(response.error()).isEqualTo(error);
        }
    }
    private static void balances(int primary, int reserve, int destination, String remaining, String received, long checkpoint) {
        assertThat(Ledger.accountBalanceById(primary).orElseThrow()).isEqualByComparingTo("23.45");
        assertThat(Ledger.accountBalanceById(reserve).orElseThrow()).isEqualByComparingTo(remaining);
        assertThat(Ledger.accountBalanceById(destination).orElseThrow()).isEqualByComparingTo(received);
        assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint);
    }
    private static long pay(String token, int source, int destination, String amount) {
        var response = transfer(token, source, destination, amount);
        assertThat(response.status()).as("%s", response.body()).isEqualTo(200);
        assertThat(response.json().path("fromAccountId").asInt()).isEqualTo(source);
        assertThat(response.json().path("toAccountId").asInt()).isEqualTo(destination);
        assertThat(response.json().path("amount").asText()).isEqualTo(amount);
        return response.json().path("txnId").asLong();
    }
    private static void feedPayment(String token, int account, long txn, String debit, int destination) {
        var response = Await.until("business feed includes settled debit", () -> {
            var r = RestApi.getTransactionFeed(token, account, txn - 1, 10);
            assertThat(r.status()).as(r.body()).isEqualTo(200);
            return r;
        }, r -> r.json().path("items").size() > 0, Duration.ofSeconds(20));
        var body = response.json();
        assertThat(body.path("accountId").asInt()).isEqualTo(account);
        assertThat(body.path("items").size()).isEqualTo(1);
        var item = body.path("items").get(0);
        assertThat(item.path("txnId").asLong()).isEqualTo(txn);
        assertThat(item.path("amount").asText()).isEqualTo(debit);
        assertThat(item.path("counterparty").path("accountId").asInt()).isEqualTo(destination);
        assertThat(body.path("nextCursor").asLong()).isEqualTo(txn);
        assertThat(body.path("hasMore").asBoolean()).isFalse();
    }
    private static RestApi.Response transfer(String token, int source, int destination, String amount) {
        return RestApi.postTransfer(token, "{\"fromAccountId\":" + source + ",\"toAccountId\":" + destination
                + ",\"amount\":\"" + amount + "\",\"memo\":\"BusinessKeyLifecycle\"}", null);
    }
}
