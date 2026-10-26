package net.democracycraft.inttests.restapi;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;

class RateLimitOverrideLifecycleTest {
    @Test
    void adminOverrideChangesLiveLimitsAndAllOwnerKeysShareTheBudget() {
        try (var owner = MockPlayer.connectLocal(Usernames.fresh("RoOwn"));
             var peer = MockPlayer.connectLocal(Usernames.fresh("RoPe"))) {
            Ledger.waitForPersonalBalance(owner.getUuid(), Duration.ofSeconds(10));
            Ledger.waitForPersonalBalance(peer.getUuid(), Duration.ofSeconds(10));
            var first = ApiTokens.issuePersonal(owner.getUsername(), owner);
            var second = ApiTokens.issuePersonal(owner.getUsername(), owner);
            var foreign = ApiTokens.issuePersonal(peer.getUsername(), peer);
            String service = ApiTokens.mintServiceKey(peer.getUuid()).token();
            String path = "/admin/rate-limit-overrides/" + owner.getUuid();
            long checkpoint = Ledger.transactionCheckpoint();
            // Populate the no-override cache before the admin update.
            String token = rotate(first.token(), 5, 4);
            set(service, path, "0.20");
            token = rotate(token, 1, 0);
            denied(token, first.keyId(), 1);
            // A second key must not bypass the exhausted issuer budget.
            String otherToken = second.token();
            denied(otherToken, second.keyId(), 1);
            String foreignToken = rotate(foreign.token(), 5, 4);
            set(service, path, "0.40");
            token = rotate(token, 2, 1);
            otherToken = rotate(otherToken, 2, 0);
            denied(token, first.keyId(), 2);
            denied(otherToken, second.keyId(), 2);
            // Rejecting an invalid update must preserve the active override/bucket.
            var invalid = RestApi.call("PUT", path, service, "{\"multiplier\":0}");
            assertThat(invalid.status()).isEqualTo(400);
            assertThat(invalid.error()).isEqualTo("INVALID_BODY");
            denied(otherToken, second.keyId(), 2);
            var cleared = RestApi.call("DELETE", path, service, null);
            assertThat(cleared.status()).isEqualTo(204);
            // The owner's previous base-limit bucket retains its consumed request.
            token = rotate(token, 5, 3);
            otherToken = rotate(otherToken, 5, 2);
            foreignToken = rotate(foreignToken, 5, 3);
            assertThat(RestApi.call("DELETE", path, service, null).status()).isEqualTo(204);
            assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
            assertThat(Ledger.personalBalance(peer.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
            assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint);
        }
    }
    private static void set(String token, String path, String multiplier) {
        var r = RestApi.call("PUT", path, token, "{\"multiplier\":" + multiplier + ",\"note\":\"Live limit lifecycle\"}");
        assertThat(r.status()).as("%s", r.body()).isEqualTo(204);
    }
    private static String rotate(String token, int limit, int remaining) {
        var r = RestApi.postRotate(token);
        assertThat(r.status()).as("%s", r.body()).isEqualTo(200);
        assertThat(r.header("X-RateLimit-Limit")).hasValue(Integer.toString(limit));
        assertThat(r.header("X-RateLimit-Remaining")).hasValue(Integer.toString(remaining));
        return r.json().path("token").asText();
    }
    private static void denied(String token, int key, int limit) {
        String jti = ApiTokens.keyById(key).jwtId();
        var r = RestApi.postRotate(token);
        assertThat(r.status()).as("%s", r.body()).isEqualTo(429);
        assertThat(r.error()).isEqualTo("RATE_LIMIT_EXCEEDED");
        assertThat(r.header("X-RateLimit-Limit")).hasValue(Integer.toString(limit));
        assertThat(r.header("X-RateLimit-Remaining")).hasValue("0");
        assertThat(Integer.parseInt(r.header("Retry-After").orElseThrow())).isBetween(1, 60);
        assertThat(ApiTokens.keyById(key).jwtId()).isEqualTo(jti);
    }
}
