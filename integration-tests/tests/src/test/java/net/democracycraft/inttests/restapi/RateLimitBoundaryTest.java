package net.democracycraft.inttests.restapi;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class RateLimitBoundaryTest {
    @ParameterizedTest
    @CsvSource({"0.005,0.01,1", "0.10,0.10,1", "0.30,0.30,2", "9999.994,9999.99,50000"})
    void storedRoundingAndEffectiveLimitAreObservedThroughRealRequests(String input, String stored, int limit) throws Exception {
        try (var owner = MockPlayer.connectLocal(Usernames.fresh("RbOwn"))) {
            Ledger.waitForPersonalBalance(owner.getUuid(), Duration.ofSeconds(10));
            var key = ApiTokens.issuePersonal(owner.getUsername(), owner);
            UUID admin = UUID.randomUUID();
            String service = ApiTokens.mintServiceKey(admin).token();
            String path = "/admin/rate-limit-overrides/" + owner.getUuid();
            long checkpoint = Ledger.transactionCheckpoint();
            var response = RestApi.call("PUT", path, service, "{\"multiplier\":" + input + ",\"note\":\"Boundary\"}");
            assertThat(response.status()).as("%s", response.body()).isEqualTo(204);
            state(owner.getUuid(), stored, "Boundary", admin);
            var rotated = RestApi.postRotate(key.token());
            assertThat(rotated.status()).as("%s", rotated.body()).isEqualTo(200);
            assertThat(rotated.header("X-RateLimit-Limit")).hasValue(Integer.toString(limit));
            assertThat(rotated.header("X-RateLimit-Remaining")).hasValue(Integer.toString(limit - 1));
            assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
            assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint);
        }
    }

    @Test
    void rejectedBoundsAndUnauthorizedChangesPreserveStoredOverrideAndLiveLimit() throws Exception {
        try (var owner = MockPlayer.connectLocal(Usernames.fresh("RbBad"))) {
            Ledger.waitForPersonalBalance(owner.getUuid(), Duration.ofSeconds(10));
            var key = ApiTokens.issuePersonal(owner.getUsername(), owner);
            UUID admin = UUID.randomUUID();
            String service = ApiTokens.mintServiceKey(admin).token();
            String path = "/admin/rate-limit-overrides/" + owner.getUuid();
            String note = "n".repeat(255);
            long checkpoint = Ledger.transactionCheckpoint();
            assertThat(RestApi.call("PUT", path, service, "{\"multiplier\":2,\"note\":\"" + note + "\"}").status()).isEqualTo(204);
            for (String body : new String[] {"{}", "{\"multiplier\":null}", "{\"multiplier\":-1}",
                    "{\"multiplier\":0.0049}", "{\"multiplier\":9999.995}", "{\"multiplier\":10000}",
                    "{\"multiplier\":3,\"note\":\"" + "x".repeat(256) + "\"}"}) {
                var rejected = RestApi.call("PUT", path, service, body);
                assertThat(rejected.status()).as("%s: %s", body, rejected.body()).isEqualTo(400);
                assertThat(rejected.error()).isEqualTo("INVALID_BODY");
                state(owner.getUuid(), "2.00", note, admin);
            }
            for (String method : new String[] {"PUT", "DELETE"}) {
                var denied = RestApi.call(method, path, key.token(), method.equals("PUT") ? "{\"multiplier\":3}" : null);
                assertThat(denied.status()).isEqualTo(403);
                state(owner.getUuid(), "2.00", note, admin);
            }
            var rotated = RestApi.postRotate(key.token());
            assertThat(rotated.status()).isEqualTo(200);
            assertThat(rotated.header("X-RateLimit-Limit")).hasValue("10");
            assertThat(rotated.header("X-RateLimit-Remaining")).hasValue("9");
            assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
            assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint);
        }
    }
    private static void state(UUID owner, String multiplier, String note, UUID admin) throws Exception {
        try (var c = DriverManager.getConnection(DevServer.DB_URL, DevServer.DB_USER, DevServer.DB_PASSWORD);
             var q = c.prepareStatement("SELECT multiplier, note, BIN_TO_UUID(updated_by_bin) FROM api_rate_limit_override WHERE owner_uuid_bin = UNHEX(REPLACE(?, '-', ''))")) {
            q.setString(1, owner.toString());
            try (var r = q.executeQuery()) {
                assertThat(r.next()).isTrue();
                assertThat(r.getBigDecimal(1)).isEqualByComparingTo(multiplier);
                assertThat(r.getString(2)).isEqualTo(note);
                assertThat(UUID.fromString(r.getString(3))).isEqualTo(admin);
                assertThat(r.next()).isFalse();
            }
        }
    }
}
