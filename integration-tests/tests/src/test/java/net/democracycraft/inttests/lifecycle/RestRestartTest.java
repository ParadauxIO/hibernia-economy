package net.democracycraft.inttests.lifecycle;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

/** Controls only the REST process explicitly owned by the disposable runner. */
@EnabledIfEnvironmentVariable(named = "IT_REST_RESTART_ENABLED", matches = "true")
class RestRestartTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    @Test
    void restartPreservesTokenRevocationAndPaymentIdempotency() throws Exception {
        try (var sender = MockPlayer.connectLocal(Usernames.fresh("RsS"));
             var recipient = MockPlayer.connectLocal(Usernames.fresh("RsR"))) {
            Ledger.waitForPersonalBalance(sender.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(recipient.getUuid(), TIMEOUT);
            var key = ApiTokens.issuePersonal(sender.getUsername(), sender);
            int target = Ledger.personalAccountId(recipient.getUuid()).orElseThrow();
            int senderPostings = Ledger.postingsCountForAccount(key.accountId());
            int recipientPostings = Ledger.postingsCountForAccount(target);
            long checkpoint = Ledger.transactionCheckpoint();
            String request = "{\"toAccountId\":" + target + ",\"amount\":\"23.45\",\"memo\":\"rest-restart\"}";
            var first = RestApi.postTransfer(key.token(), request, "before-restart");
            assertThat(first.status()).isBetween(200, 299);
            long transaction = first.json().path("txnId").asLong();
            assertThat(transaction).isPositive();
            var rotation = RestApi.postRotate(key.token());
            assertThat(rotation.status()).isEqualTo(200);
            String token = rotation.json().path("token").asText();
            assertThat(token).isNotBlank();
            assertThat(RestApi.getTransactions(key.token(), key.accountId(), 1, 10).status()).isEqualTo(401);

            long pid = Long.parseLong(System.getenv("IT_REST_PID"));
            var old = ProcessHandle.of(pid).orElseThrow();
            assertThat(old.isAlive()).isTrue();
            assertThat(old.destroy()).as("stop runner-owned REST process").isTrue();
            old.onExit().get(60, TimeUnit.SECONDS);
            var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-jar", System.getenv("IT_REST_JAR"),
                    "--server.port=" + URI.create(RestApi.BASE_URL).getPort(),
                    "--api.jwt-secret=" + System.getenv("RESTAPI_JWT_SECRET"));
            builder.environment().put("DB_URL", DevServer.DB_URL);
            builder.environment().put("DB_USERNAME", DevServer.DB_USER);
            builder.environment().put("DB_PASSWORD", DevServer.DB_PASSWORD);
            Process restarted = builder.redirectErrorStream(true).redirectOutput(
                    Path.of(System.getenv("IT_SERVER_DIR")).getParent().resolve("rest-api-restart.log").toFile()).start();
            try {
                Await.until("restarted REST accepts the current token", () -> {
                    if (!restarted.isAlive()) throw new AssertionError("Restarted REST exited: " + restarted.exitValue());
                    try { return RestApi.getTransactions(token, key.accountId(), 1, 10).status(); }
                    catch (RuntimeException unavailable) { return 0; }
                }, status -> status == 200, Duration.ofSeconds(120));
                assertThat(RestApi.getTransactions(key.token(), key.accountId(), 1, 10).status()).isEqualTo(401);
                var replay = RestApi.postTransfer(token, request, "before-restart");
                assertThat(replay.status()).isBetween(200, 299);
                assertThat(replay.json().path("txnId").asLong()).isEqualTo(transaction);
                assertThat(Ledger.personalBalance(sender.getUuid()).orElseThrow()).isEqualByComparingTo("9976.55");
                assertThat(Ledger.personalBalance(recipient.getUuid()).orElseThrow()).isEqualByComparingTo("10023.45");
                assertThat(Ledger.postingsCountForAccount(key.accountId())).isEqualTo(senderPostings + 1);
                assertThat(Ledger.postingsCountForAccount(target)).isEqualTo(recipientPostings + 1);
                var next = RestApi.postTransfer(token, request, "after-restart");
                assertThat(next.status()).isBetween(200, 299);
                assertThat(next.json().path("txnId").asLong()).isGreaterThan(transaction);
                assertThat(Ledger.personalBalance(sender.getUuid()).orElseThrow()).isEqualByComparingTo("9953.10");
                assertThat(Ledger.personalBalance(recipient.getUuid()).orElseThrow()).isEqualByComparingTo("10046.90");
                assertThat(Ledger.postingsCountForAccount(key.accountId())).isEqualTo(senderPostings + 2);
                assertThat(Ledger.postingsCountForAccount(target)).isEqualTo(recipientPostings + 2);
                var postings = Ledger.initiatedPostingsAfter(sender.getUuid(), checkpoint);
                assertThat(postings).hasSize(4);
                assertThat(postings.stream().map(Ledger.Posting::transactionId).distinct().toList())
                        .containsExactlyInAnyOrder(transaction, next.json().path("txnId").asLong());
                assertThat(Ledger.initiatedTransactionCountAfter(sender.getUuid(), checkpoint)).isEqualTo(2);
            } finally {
                restarted.destroy();
                if (!restarted.waitFor(60, TimeUnit.SECONDS)) {
                    restarted.destroyForcibly();
                    assertThat(restarted.waitFor(10, TimeUnit.SECONDS)).isTrue();
                    throw new AssertionError("Restarted REST required forced shutdown");
                }
            }
        }
    }
}
