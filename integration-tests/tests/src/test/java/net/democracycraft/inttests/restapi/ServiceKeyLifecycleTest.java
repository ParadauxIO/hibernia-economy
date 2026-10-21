package net.democracycraft.inttests.restapi;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.sql.DriverManager;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;

class ServiceKeyLifecycleTest {
    @ParameterizedTest
    @ValueSource(strings = {"rotate", "revoke"})
    void rotatedServiceTokenRetainsAdminScopeThenSelfInvalidationBlocksWrites(String operation) throws Exception {
        try (var owner = MockPlayer.connectLocal(Usernames.fresh("SkOwn"));
             var peer = MockPlayer.connectLocal(Usernames.fresh("SkPe"))) {
            Ledger.waitForPersonalBalance(owner.getUuid(), Duration.ofSeconds(10));
            Ledger.waitForPersonalBalance(peer.getUuid(), Duration.ofSeconds(10));
            int account = Ledger.personalAccountId(owner.getUuid()).orElseThrow();
            int other = Ledger.personalAccountId(peer.getUuid()).orElseThrow();
            var first = ApiTokens.mintServiceKey(owner.getUuid());
            var independent = ApiTokens.mintServiceKey(peer.getUuid());
            rename(first.token(), account, "Before Rotation");
            var rotation = RestApi.postRotate(first.token());
            assertThat(rotation.status()).as("%s", rotation.body()).isEqualTo(200);
            assertThat(rotation.json().path("keyId").asInt()).isEqualTo(first.keyId());
            String current = rotation.json().path("token").asText();
            assertThat(current).isNotBlank();
            var stored = ApiTokens.keyById(first.keyId());
            assertThat(stored.ownerUuid()).isEqualTo(owner.getUuid());
            assertThat(stored.accountId()).isNull();
            assertThat(stored.firmId()).isNull();
            assertThat(stored.jwtId()).isNotEqualTo(first.jwtId());
            long checkpoint = Ledger.transactionCheckpoint();
            invalid(first.token(), account, other, "TOKEN_SUPERSEDED");
            state(account, other, "Before Rotation", "10000", "10000", checkpoint);
            // Only a correctly retained SERVICE claim can perform these admin operations.
            rename(current, account, "After Rotation");
            paid(current, account, other, "23.45");
            checkpoint = Ledger.transactionCheckpoint();
            var invalidated = RestApi.call("POST", "/admin/api-keys/" + first.keyId() + "/" + operation, current, null);
            assertThat(invalidated.status()).as("%s", invalidated.body()).isEqualTo(operation.equals("rotate") ? 200 : 204);
            String error = operation.equals("rotate") ? "TOKEN_SUPERSEDED" : "TOKEN_REVOKED";
            invalid(current, account, other, error);
            state(account, other, "After Rotation", "9976.55", "10023.45", checkpoint);
            var after = ApiTokens.keyById(first.keyId());
            assertThat(after.revoked()).isEqualTo(operation.equals("revoke"));
            if (operation.equals("rotate")) assertThat(after.jwtId()).isNotEqualTo(stored.jwtId());
            else assertThat(after.jwtId()).isEqualTo(stored.jwtId());
            // Another SERVICE credential retains control after the first invalidates itself.
            rename(independent.token(), account, "Independent Admin");
            paid(independent.token(), other, account, "11.00");
            assertThat(Ledger.initiatedTransactionCountAfter(peer.getUuid(), checkpoint)).isEqualTo(1);
            assertThat(Ledger.initiatedPostingsAfter(peer.getUuid(), checkpoint)).hasSize(2);
            state(account, other, "Independent Admin", "9987.55", "10012.45", null);
            assertThat(ApiTokens.keyById(independent.keyId()).jwtId()).isEqualTo(independent.jwtId());
            assertThat(ApiTokens.keyById(independent.keyId()).revoked()).isFalse();
        }
    }
    private static void invalid(String token, int account, int other, String error) {
        for (var response : java.util.List.of(
                RestApi.call("PATCH", "/admin/accounts/" + account + "/display-name", token, "{\"displayName\":\"Forbidden Change\"}"),
                transfer(token, account, other, "7.89"), RestApi.postRotate(token))) {
            assertThat(response.status()).as("%s", response.body()).isEqualTo(401);
            assertThat(response.error()).isEqualTo(error);
        }
    }
    private static void rename(String token, int account, String name) {
        var response = RestApi.call("PATCH", "/admin/accounts/" + account + "/display-name", token,
                "{\"displayName\":\"" + name + "\"}");
        assertThat(response.status()).as("%s", response.body()).isEqualTo(200);
        assertThat(response.json().path("displayName").asText()).isEqualTo(name);
    }
    private static void paid(String token, int source, int destination, String amount) {
        var response = transfer(token, source, destination, amount);
        assertThat(response.status()).as("%s", response.body()).isEqualTo(200);
        assertThat(response.json().path("fromAccountId").asInt()).isEqualTo(source);
        assertThat(response.json().path("toAccountId").asInt()).isEqualTo(destination);
        assertThat(response.json().path("amount").asText()).isEqualTo(amount);
    }
    private static RestApi.Response transfer(String token, int source, int destination, String amount) {
        return RestApi.call("POST", "/admin/transfers", token, "{\"fromAccountId\":" + source
                + ",\"toAccountId\":" + destination + ",\"amount\":\"" + amount + "\",\"memo\":\"ServiceLifecycle\"}");
    }
    private static void state(int account, int other, String name, String balance, String peerBalance, Long checkpoint) throws Exception {
        try (var c = DriverManager.getConnection(DevServer.DB_URL, DevServer.DB_USER, DevServer.DB_PASSWORD);
             var q = c.prepareStatement("SELECT display_name FROM accounts WHERE account_id = ?")) {
            q.setInt(1, account);
            try (var r = q.executeQuery()) {
                assertThat(r.next()).isTrue();
                assertThat(r.getString(1)).isEqualTo(name);
            }
        }
        assertThat(Ledger.accountBalanceById(account).orElseThrow()).isEqualByComparingTo(balance);
        assertThat(Ledger.accountBalanceById(other).orElseThrow()).isEqualByComparingTo(peerBalance);
        if (checkpoint != null) assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint);
    }
}
