package net.democracycraft.inttests.restapi;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.sql.DriverManager;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;

/** Dedicated profile: three attempts, two exhausted deliveries, 1/2-second backoff. */
@EnabledIfEnvironmentVariable(named = "IT_WEBHOOK_FAILURE_PROFILE", matches = "true")
class WebhookExhaustionTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    @Test
    void repeatedExhaustionDisablesSubscriptionAndSuccessfulRecoveryResetsHealth() throws Exception {
        var receiver = WebhookReceiver.get();
        String path = Usernames.fresh("ExHk");
        try (var owner = MockPlayer.connectLocal(Usernames.fresh("ExOwn"));
             var peer = MockPlayer.connectLocal(Usernames.fresh("ExPe"))) {
            Ledger.waitForPersonalBalance(owner.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(peer.getUuid(), TIMEOUT);
            var key = ApiTokens.issuePersonal(owner.getUsername(), owner);
            int destination = Ledger.personalAccountId(peer.getUuid()).orElseThrow();
            String service = ApiTokens.mintServiceKey(peer.getUuid()).token();
            long before = Ledger.transactionCheckpoint();
            // Drain earlier starting grants before subscribing: only our payments affect health.
            Await.until("webhook cursor has ingested starting grants", () -> number("SELECT last_dispatched_txn_id FROM webhook_cursor WHERE id = 1"), n -> n >= before, TIMEOUT);
            var created = RestApi.postWebhook(key.token(), receiver.url(path));
            assertThat(created.status()).isEqualTo(201);
            long id = created.json().path("id").asLong();
            try (var fault = receiver.respondWith(path, 503)) {
                for (int failures = 1; failures <= 2; failures++) {
                    long txn = pay(key.token(), destination);
                    String where = " WHERE subscription_id = " + id + " AND txn_id = " + txn;
                    Await.until("delivery exhausts retries", () -> text("SELECT status FROM webhook_delivery" + where), "FAILED"::equals, TIMEOUT);
                    assertThat(number("SELECT attempts FROM webhook_delivery" + where)).isEqualTo(3);
                    assertThat(number("SELECT http_status FROM webhook_delivery" + where)).isEqualTo(503);
                    assertThat(text("SELECT last_error FROM webhook_delivery" + where)).isEqualTo("HTTP 503");
                    final int expected = failures;
                    Await.until("subscription health records exhausted delivery", () -> number("SELECT consecutive_failures FROM webhook_subscription WHERE subscription_id = " + id), n -> n == expected, TIMEOUT);
                    if (failures == 1) {
                        assertThat(number("SELECT active FROM webhook_subscription WHERE subscription_id = " + id)).isEqualTo(1);
                    } else {
                        Await.until("subscription automatically disabled", () -> number("SELECT active FROM webhook_subscription WHERE subscription_id = " + id), n -> n == 0, TIMEOUT);
                        assertThat(number("SELECT disabled_at IS NOT NULL FROM webhook_subscription WHERE subscription_id = " + id)).isEqualTo(1);
                    }
                    var attempts = receiver.forSubscription(id).stream().filter(d -> d.json().path("transaction").path("txnId").asLong() == txn).toList();
                    assertThat(attempts).hasSize(3);
                    assertThat(attempts).allSatisfy(d -> {
                        assertThat(d.responseStatus()).isEqualTo(503);
                        assertThat(d.rawBody()).isEqualTo(attempts.get(0).rawBody());
                    });
                }
            }
            var enabled = RestApi.call("PATCH", "/admin/webhooks/" + id + "/active", service, "{\"active\":true}");
            assertThat(enabled.status()).isEqualTo(200);
            assertThat(enabled.json().path("affected").asInt()).isEqualTo(1);
            long recovered = pay(key.token(), destination);
            var received = receiver.await(id, e -> e.path("transaction").path("txnId").asLong() == recovered, TIMEOUT);
            assertThat(received.responseStatus()).isEqualTo(200);
            Await.until("success resets failure health", () -> number("SELECT consecutive_failures FROM webhook_subscription WHERE subscription_id = " + id), n -> n == 0, TIMEOUT);
            assertThat(number("SELECT active FROM webhook_subscription WHERE subscription_id = " + id)).isEqualTo(1);
            assertThat(number("SELECT COUNT(*) FROM webhook_delivery WHERE subscription_id = " + id + " AND status = 'FAILED'")).isEqualTo(2);
            assertThat(Ledger.accountBalanceById(key.accountId()).orElseThrow()).isEqualByComparingTo("9997.00");
            assertThat(Ledger.accountBalanceById(destination).orElseThrow()).isEqualByComparingTo("10003.00");
            assertThat(Ledger.initiatedTransactionCountAfter(owner.getUuid(), before)).isEqualTo(3);
            assertThat(Ledger.initiatedPostingsAfter(owner.getUuid(), before)).hasSize(6);
        }
    }
    @Test
    void successfulDeliverySeparatesFailuresSoHistoricalFailuresDoNotDisable() throws Exception {
        var receiver = WebhookReceiver.get();
        String path = Usernames.fresh("HsHk");
        try (var owner = MockPlayer.connectLocal(Usernames.fresh("HsOwn"));
             var peer = MockPlayer.connectLocal(Usernames.fresh("HsPe"))) {
            Ledger.waitForPersonalBalance(owner.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(peer.getUuid(), TIMEOUT);
            var key = ApiTokens.issuePersonal(owner.getUsername(), owner);
            int destination = Ledger.personalAccountId(peer.getUuid()).orElseThrow();
            long before = Ledger.transactionCheckpoint();
            Await.until("starting grants ingested", () -> number("SELECT last_dispatched_txn_id FROM webhook_cursor WHERE id = 1"), n -> n >= before, TIMEOUT);
            var created = RestApi.postWebhook(key.token(), receiver.url(path));
            assertThat(created.status()).isEqualTo(201);
            long id = created.json().path("id").asLong();
            for (int cycle = 0; cycle < 2; cycle++) {
                try (var fault = receiver.respondWith(path, 503)) {
                    long txn = pay(key.token(), destination);
                    String where = " WHERE subscription_id = " + id + " AND txn_id = " + txn;
                    Await.until("isolated failure exhausts attempts", () -> text("SELECT status FROM webhook_delivery" + where), "FAILED"::equals, TIMEOUT);
                    assertThat(number("SELECT attempts FROM webhook_delivery" + where)).isEqualTo(3);
                    var attempts = receiver.forSubscription(id).stream().filter(d -> d.json().path("transaction").path("txnId").asLong() == txn).toList();
                    assertThat(attempts).hasSize(3);
                    assertThat(attempts).allSatisfy(d -> assertThat(d.responseStatus()).isEqualTo(503));
                    Await.until("failure count is one after isolated failure", () -> number("SELECT consecutive_failures FROM webhook_subscription WHERE subscription_id = " + id), n -> n == 1, TIMEOUT);
                    assertThat(number("SELECT active FROM webhook_subscription WHERE subscription_id = " + id)).isEqualTo(1);
                    assertThat(number("SELECT disabled_at IS NULL FROM webhook_subscription WHERE subscription_id = " + id)).isEqualTo(1);
                }
                // No admin activation or health write: only a delivered event resets the counter.
                long success = pay(key.token(), destination);
                String where = " WHERE subscription_id = " + id + " AND txn_id = " + success;
                Await.until("healthy delivery succeeds", () -> text("SELECT status FROM webhook_delivery" + where), "DELIVERED"::equals, TIMEOUT);
                assertThat(number("SELECT attempts FROM webhook_delivery" + where)).isEqualTo(1);
                assertThat(number("SELECT http_status FROM webhook_delivery" + where)).isEqualTo(200);
                var deliveries = receiver.forSubscription(id).stream().filter(d -> d.json().path("transaction").path("txnId").asLong() == success).toList();
                assertThat(deliveries).hasSize(1);
                assertThat(deliveries.get(0).responseStatus()).isEqualTo(200);
                Await.until("success clears consecutive failures", () -> number("SELECT consecutive_failures FROM webhook_subscription WHERE subscription_id = " + id), n -> n == 0, TIMEOUT);
            }
            assertThat(number("SELECT active FROM webhook_subscription WHERE subscription_id = " + id)).isEqualTo(1);
            assertThat(number("SELECT disabled_at IS NULL FROM webhook_subscription WHERE subscription_id = " + id)).isEqualTo(1);
            assertThat(number("SELECT COUNT(*) FROM webhook_delivery WHERE subscription_id = " + id + " AND status = 'FAILED'")).isEqualTo(2);
            assertThat(number("SELECT COUNT(*) FROM webhook_delivery WHERE subscription_id = " + id + " AND status = 'DELIVERED'")).isEqualTo(2);
            assertThat(Ledger.accountBalanceById(key.accountId()).orElseThrow()).isEqualByComparingTo("9996.00");
            assertThat(Ledger.accountBalanceById(destination).orElseThrow()).isEqualByComparingTo("10004.00");
            assertThat(Ledger.initiatedTransactionCountAfter(owner.getUuid(), before)).isEqualTo(4);
            assertThat(Ledger.initiatedPostingsAfter(owner.getUuid(), before)).hasSize(8);
        }
    }
    private static long pay(String token, int destination) {
        var r = RestApi.postTransfer(token, "{\"toAccountId\":" + destination + ",\"amount\":\"1.00\",\"memo\":\"ExhaustionPayment\"}", null);
        assertThat(r.status()).as("%s", r.body()).isEqualTo(200);
        return r.json().path("txnId").asLong();
    }
    private static long number(String sql) { return Long.parseLong(text(sql)); }
    private static String text(String sql) {
        try (var c = DriverManager.getConnection(DevServer.DB_URL, DevServer.DB_USER, DevServer.DB_PASSWORD);
             var q = c.createStatement(); var r = q.executeQuery(sql)) {
            return r.next() ? r.getString(1) : null;
        } catch (java.sql.SQLException e) { throw new RuntimeException(e); }
    }
}
