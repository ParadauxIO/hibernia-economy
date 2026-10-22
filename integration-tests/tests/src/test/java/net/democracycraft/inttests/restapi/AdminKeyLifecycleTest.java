package net.democracycraft.inttests.restapi;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;

class AdminKeyLifecycleTest {
    @Test
    void forceRotationAndRevocationBlockMoneyAndHistoryWhileReissueRestoresOnlyRotatedKey() {
        try (var owner = MockPlayer.connectLocal(Usernames.fresh("KlOwn"));
             var peer = MockPlayer.connectLocal(Usernames.fresh("KlPe"))) {
            Ledger.waitForPersonalBalance(owner.getUuid(), Duration.ofSeconds(10));
            Ledger.waitForPersonalBalance(peer.getUuid(), Duration.ofSeconds(10));
            int account = Ledger.personalAccountId(owner.getUuid()).orElseThrow();
            int other = Ledger.personalAccountId(peer.getUuid()).orElseThrow();
            var first = ApiTokens.issuePersonal(owner.getUsername(), owner);
            var independent = ApiTokens.issuePersonal(peer.getUsername(), peer);
            String service = ApiTokens.mintServiceKey(peer.getUuid()).token();
            String path = "/admin/api-keys/" + first.keyId();
            long savedCursor = pay(first.token(), account, other, "1.25");
            settledFeed(first.token(), account, savedCursor, "-1.25");
            assertThat(RestApi.getTransactions(first.token(), account, null, null).status()).isEqualTo(200);
            var rotated = RestApi.call("POST", path + "/rotate", service, null);
            assertThat(rotated.status()).as("%s", rotated.body()).isEqualTo(200);
            var stored = ApiTokens.keyById(first.keyId());
            assertThat(stored.jwtId()).isNotEqualTo(first.jwtId());
            assertThat(stored.ownerUuid()).isEqualTo(owner.getUuid());
            assertThat(stored.accountId()).isEqualTo(account);
            assertThat(stored.revoked()).isFalse();
            denied(first.token(), account, other, savedCursor, "TOKEN_SUPERSEDED", "9998.75", "10001.25");
            // A separate key still works; invalidation is scoped to the rotated key.
            pay(independent.token(), other, account, "0.25");
            assertThat(ApiTokens.keyById(independent.keyId()).jwtId()).isEqualTo(independent.jwtId());
            var replacement = ApiTokens.reissuePersonal(owner.getUsername(), owner, first.keyId());
            assertThat(replacement.keyId()).isEqualTo(first.keyId());
            assertThat(replacement.accountId()).isEqualTo(account);
            assertThat(replacement.jwtId()).isNotEqualTo(stored.jwtId()).isNotEqualTo(first.jwtId());
            denied(first.token(), account, other, savedCursor, "TOKEN_SUPERSEDED", "9999.00", "10001.00");
            long replacementTxn = pay(replacement.token(), account, other, "7.89");
            settledFeed(replacement.token(), account, replacementTxn, "-7.89");
            assertThat(RestApi.getTransactions(replacement.token(), account, null, null).status()).isEqualTo(200);
            for (int repeat = 0; repeat < 2; repeat++) {
                var revoked = RestApi.call("POST", path + "/revoke", service, null);
                assertThat(revoked.status()).as("%s", revoked.body()).isEqualTo(204);
                assertThat(ApiTokens.keyById(first.keyId()).revoked()).isTrue();
                denied(replacement.token(), account, other, savedCursor, "TOKEN_REVOKED", "9991.11", "10008.89");
            }
            var refused = RestApi.call("POST", path + "/rotate", service, null);
            assertThat(refused.status()).isEqualTo(409);
            assertThat(refused.error()).isEqualTo("TOKEN_REVOKED");
            assertThat(ApiTokens.keyById(first.keyId()).jwtId()).isEqualTo(replacement.jwtId());
            denied(replacement.token(), account, other, savedCursor, "TOKEN_REVOKED", "9991.11", "10008.89");
            pay(independent.token(), other, account, "0.89");
            assertThat(Ledger.accountBalanceById(account).orElseThrow()).isEqualByComparingTo("9992.00");
            assertThat(Ledger.accountBalanceById(other).orElseThrow()).isEqualByComparingTo("10008.00");
            assertThat(ApiTokens.keyById(independent.keyId()).revoked()).isFalse();
        }
    }
    private static void denied(String token, int source, int destination, long savedCursor, String error, String sourceBalance, String destBalance) {
        long checkpoint = Ledger.transactionCheckpoint();
        for (var response : java.util.List.of(transfer(token, destination, "23.45"),
                RestApi.getTransactions(token, source, null, null),
                RestApi.getTransactionFeed(token, source, 0L, 10),
                RestApi.getTransactionFeed(token, source, savedCursor, 10))) {
            assertThat(response.status()).as("%s", response.body()).isEqualTo(401);
            assertThat(response.error()).isEqualTo(error);
        }
        assertThat(Ledger.accountBalanceById(source).orElseThrow()).isEqualByComparingTo(sourceBalance);
        assertThat(Ledger.accountBalanceById(destination).orElseThrow()).isEqualByComparingTo(destBalance);
        assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint);
    }
    private static long pay(String token, int source, int destination, String amount) {
        var response = transfer(token, destination, amount);
        assertThat(response.status()).as("%s", response.body()).isEqualTo(200);
        assertThat(response.json().path("fromAccountId").asInt()).isEqualTo(source);
        assertThat(response.json().path("toAccountId").asInt()).isEqualTo(destination);
        assertThat(response.json().path("amount").asText()).isEqualTo(amount);
        return response.json().path("txnId").asLong();
    }
    private static void settledFeed(String token, int account, long txn, String amount) {
        var response = Await.until("valid key sees settled payment in feed", () -> {
            var r = RestApi.getTransactionFeed(token, account, txn - 1, 10);
            assertThat(r.status()).as(r.body()).isEqualTo(200);
            return r;
        }, r -> r.json().path("items").size() > 0, Duration.ofSeconds(20));
        var body = response.json();
        assertThat(body.path("items").size()).isEqualTo(1);
        assertThat(body.path("items").get(0).path("txnId").asLong()).isEqualTo(txn);
        assertThat(body.path("items").get(0).path("amount").asText()).isEqualTo(amount);
        assertThat(body.path("nextCursor").asLong()).isEqualTo(txn);
        assertThat(body.path("hasMore").asBoolean()).isFalse();
    }
    private static RestApi.Response transfer(String token, int destination, String amount) {
        return RestApi.postTransfer(token, "{\"toAccountId\":" + destination + ",\"amount\":\"" + amount + "\",\"memo\":\"KeyLifecycle\"}", null);
    }
}
