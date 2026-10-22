package net.democracycraft.inttests.restapi;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.ApiTokens;
import net.democracycraft.inttests.support.ApiTokens.IssuedKey;
import net.democracycraft.inttests.support.Ledger;
import net.democracycraft.inttests.support.RestApi;
import net.democracycraft.inttests.support.RestApi.Response;
import net.democracycraft.inttests.support.Usernames;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E coverage for the {@code @RateLimit} interceptor. Exists so a
 * regression in the rate-limit wiring (interceptor not registered, bucket
 * not consumed, headers missing, 429 not returned) fails a test rather
 * than slipping into production.
 *
 * <p>Uses {@code POST /api/v1/auth/rotate} as the target. It has the
 * tightest configured limit (5/min for both PERSONAL and BUSINESS keys),
 * so we can prove the bucket exhausts within seconds without a 600-call
 * burst. The rotate semantics:
 * <ul>
 *   <li>Authenticated budgets are keyed by issuer (owner UUID), route and
 *       effective capacity. All keys for one owner share that route's budget;
 *       rotating a JTI does not replenish it.</li>
 *   <li>Each rotate supersedes the prior token, so we have to feed the
 *       new token into the next call.</li>
 * </ul>
 *
 * <p>Five rotates exhaust the bucket; the sixth fails with 429 +
 * {@code Retry-After} while the current bearer remains valid.
 */
class RateLimitTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void exceedingPerMinuteLimitReturns429WithHeaders() throws InterruptedException {
        String username = Usernames.fresh("Rl");
        try (MockPlayer p = MockPlayer.connectLocal(username)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            IssuedKey key = ApiTokens.issuePersonal(username, p);
            long checkpoint = Ledger.transactionCheckpoint();

            // Burn through the bucket. /auth/rotate is 5/min for both key
            // types; the 6th call must 429 even though the new token is
            // still cryptographically valid.
            String currentToken = key.token();
            for (int i = 1; i <= 5; i++) {
                Response r = RestApi.postRotate(currentToken);
                assertThat(r.status())
                        .as("rotate #%d should succeed (within 5/min limit), body=%s", i, r.body())
                        .isEqualTo(200);
                currentToken = r.json().path("token").asText();
                // Header sanity on every successful call.
                assertThat(r.header("X-RateLimit-Limit")).hasValue("5");
                assertThat(r.header("X-RateLimit-Remaining"))
                        .as("Remaining after rotate #%d", i)
                        .hasValue(Integer.toString(5 - i));
            }

            Response over = RestApi.postRotate(currentToken);
            assertThat(over.status())
                    .as("rotate #6 should hit the rate limit, body=%s", over.body())
                    .isEqualTo(429);
            assertThat(over.error()).isEqualTo("RATE_LIMIT_EXCEEDED");
            assertThat(over.header("Retry-After"))
                    .as("Retry-After header must be set on 429")
                    .isPresent();
            assertThat(over.header("X-RateLimit-Limit")).hasValue("5");
            assertThat(over.header("X-RateLimit-Remaining")).hasValue("0");
            String jti = ApiTokens.keyById(key.keyId()).jwtId();
            int account = Ledger.personalAccountId(p.getUuid()).orElseThrow();
            Response history = RestApi.getTransactions(currentToken, account, 1, 10);
            assertThat(history.status()).as(history.body()).isEqualTo(200);
            assertThat(history.header("X-RateLimit-Limit")).isPresent();
            assertThat(Integer.parseInt(history.header("X-RateLimit-Remaining").orElseThrow())).isPositive();
            assertThat(ApiTokens.keyById(key.keyId()).jwtId()).isEqualTo(jti);
            int retrySeconds = Integer.parseInt(over.header("Retry-After").orElseThrow());
            assertThat(retrySeconds).isBetween(1, 60);
            // Current providers use interval refill despite comments describing gradual refill.
            // Header uses whole seconds. Allow one additional second for truncation;
            // this verifies refill, not exact Retry-After rounding semantics.
            Thread.sleep((retrySeconds + 1L) * 1000);
            Response resumed = RestApi.postRotate(currentToken);
            assertThat(resumed.status()).as("rotation resumes after refill: %s", resumed.body()).isEqualTo(200);
            assertThat(resumed.header("X-RateLimit-Limit")).hasValue("5");
            assertThat(resumed.header("X-RateLimit-Remaining")).hasValue("4");
            assertThat(resumed.json().path("token").asText()).isNotBlank().isNotEqualTo(currentToken);
            assertThat(ApiTokens.keyById(key.keyId()).jwtId()).isNotEqualTo(jti);
            assertThat(Ledger.personalBalance(p.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
            assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint);
        }
    }
}
