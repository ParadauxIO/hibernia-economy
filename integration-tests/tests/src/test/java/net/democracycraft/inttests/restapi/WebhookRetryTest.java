package net.democracycraft.inttests.restapi;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.api.Test;
import java.sql.DriverManager;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;

class WebhookRetryTest {
    @Test
    void transientFailureRetriesSameDeliveryWithoutRepeatingMoneyMovement() throws Exception {
        var receiver = WebhookReceiver.get();
        String path = Usernames.fresh("Retry");
        try (var fault = receiver.failFirstAttempt(path, 503);
             var owner = MockPlayer.connectLocal(Usernames.fresh("RtOwn"));
             var peer = MockPlayer.connectLocal(Usernames.fresh("RtPe"))) {
            Ledger.waitForPersonalBalance(owner.getUuid(), Duration.ofSeconds(10));
            Ledger.waitForPersonalBalance(peer.getUuid(), Duration.ofSeconds(10));
            var key = ApiTokens.issuePersonal(owner.getUsername(), owner);
            int destination = Ledger.personalAccountId(peer.getUuid()).orElseThrow();
            var subscription = RestApi.postWebhook(key.token(), receiver.url(path));
            assertThat(subscription.status()).isEqualTo(201);
            long id = subscription.json().path("id").asLong();
            long before = Ledger.transactionCheckpoint();
            var paid = RestApi.postTransfer(key.token(), "{\"toAccountId\":" + destination
                    + ",\"amount\":\"23.45\",\"memo\":\"RetryMustNotPayTwice\"}", null);
            assertThat(paid.status()).as("%s", paid.body()).isEqualTo(200);
            long txn = paid.json().path("txnId").asLong();
            var first = receiver.await(id, e -> e.path("transaction").path("txnId").asLong() == txn, Duration.ofSeconds(30));
            assertThat(first.responseStatus()).isEqualTo(503);
            long delivery = first.json().path("deliveryId").asLong();
            Await.until("failed delivery recorded as pending", () -> row(delivery),
                    r -> r != null && r.attempts() == 1, Duration.ofSeconds(5));
            var pending = row(delivery);
            assertThat(pending.status()).isEqualTo("PENDING");
            assertThat(pending.httpStatus()).isEqualTo(503);
            assertThat(pending.error()).isEqualTo("HTTP 503");
            assertThat(pending.futureAttempt()).isTrue();
            Await.until("same delivery successfully retried", () -> row(delivery),
                    r -> r != null && r.status().equals("DELIVERED"), Duration.ofSeconds(50));
            var done = row(delivery);
            assertThat(done.attempts()).isEqualTo(2);
            assertThat(done.httpStatus()).isEqualTo(200);
            assertThat(done.error()).isNull();
            var attempts = receiver.forSubscription(id).stream()
                    .filter(d -> d.json().path("transaction").path("txnId").asLong() == txn).toList();
            assertThat(attempts).hasSize(2);
            assertThat(attempts.get(1).responseStatus()).isEqualTo(200);
            assertThat(attempts.get(1).rawBody()).isEqualTo(first.rawBody());
            assertThat(attempts.get(1).header("X-Treasury-Delivery")).isEqualTo(first.header("X-Treasury-Delivery"));
            assertThat(attempts.get(1).header("X-Treasury-Signature")).isEqualTo(first.header("X-Treasury-Signature"));
            assertThat(Ledger.accountBalanceById(key.accountId()).orElseThrow()).isEqualByComparingTo("9976.55");
            assertThat(Ledger.accountBalanceById(destination).orElseThrow()).isEqualByComparingTo("10023.45");
            assertThat(Ledger.initiatedTransactionCountAfter(owner.getUuid(), before)).isEqualTo(1);
            assertThat(Ledger.initiatedPostingsAfter(owner.getUuid(), before)).hasSize(2);
        }
    }
    @Test
    void pendingRetryUsesUpdatedTargetAndSecretWithoutChangingDeliveryIdentity() throws Exception {
        var receiver = WebhookReceiver.get();
        String oldPath = Usernames.fresh("RqOld"), newPath = Usernames.fresh("RqNew");
        try (var fault = receiver.failFirstAttempt(oldPath, 503);
             var owner = MockPlayer.connectLocal(Usernames.fresh("RqOwn"));
             var peer = MockPlayer.connectLocal(Usernames.fresh("RqPe"))) {
            Ledger.waitForPersonalBalance(owner.getUuid(), Duration.ofSeconds(10));
            Ledger.waitForPersonalBalance(peer.getUuid(), Duration.ofSeconds(10));
            var key = ApiTokens.issuePersonal(owner.getUsername(), owner);
            int destination = Ledger.personalAccountId(peer.getUuid()).orElseThrow();
            String service = ApiTokens.mintServiceKey(peer.getUuid()).token();
            var subscription = RestApi.postWebhook(key.token(), receiver.url(oldPath));
            assertThat(subscription.status()).isEqualTo(201);
            long id = subscription.json().path("id").asLong();
            long before = Ledger.transactionCheckpoint();
            var paid = RestApi.postTransfer(key.token(), "{\"toAccountId\":" + destination
                    + ",\"amount\":\"7.89\",\"memo\":\"QueuedConfiguration\"}", null);
            assertThat(paid.status()).as("%s", paid.body()).isEqualTo(200);
            long txn = paid.json().path("txnId").asLong();
            var first = receiver.await(id, e -> e.path("transaction").path("txnId").asLong() == txn, Duration.ofSeconds(30));
            assertThat(first.responseStatus()).isEqualTo(503);
            assertThat(first.path()).isEqualTo("/" + oldPath);
            long delivery = first.json().path("deliveryId").asLong();
            Await.until("retry is scheduled before changing configuration", () -> row(delivery),
                    r -> r != null && r.attempts() == 1 && r.status().equals("PENDING"), Duration.ofSeconds(5));
            assertThat(row(delivery).futureAttempt()).isTrue();
            String secret = "c".repeat(64);
            String base = "/admin/webhooks/" + id;
            var target = RestApi.call("PATCH", base + "/url", service,
                    "{\"targetUrl\":\"" + receiver.url(newPath) + "\"}");
            assertThat(target.status()).as("%s", target.body()).isEqualTo(200);
            assertThat(target.json().path("affected").asInt()).isEqualTo(1);
            var changed = RestApi.call("PATCH", base + "/secret", service, "{\"secret\":\"" + secret + "\"}");
            assertThat(changed.status()).as("%s", changed.body()).isEqualTo(200);
            assertThat(changed.json().path("affected").asInt()).isEqualTo(1);
            Await.until("queued delivery reaches updated endpoint", () -> row(delivery),
                    r -> r != null && r.status().equals("DELIVERED"), Duration.ofSeconds(50));
            assertThat(row(delivery).attempts()).isEqualTo(2);
            var attempts = receiver.forSubscription(id).stream()
                    .filter(d -> d.json().path("transaction").path("txnId").asLong() == txn).toList();
            assertThat(attempts).hasSize(2);
            var retried = attempts.get(1);
            assertThat(retried.path()).isEqualTo("/" + newPath);
            assertThat(retried.responseStatus()).isEqualTo(200);
            assertThat(retried.rawBody()).isEqualTo(first.rawBody());
            assertThat(retried.header("X-Treasury-Delivery")).isEqualTo(first.header("X-Treasury-Delivery"));
            var mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            assertThat(retried.header("X-Treasury-Signature"))
                    .isEqualToIgnoringCase("sha256=" + java.util.HexFormat.of().formatHex(mac.doFinal(retried.rawBody())))
                    .isNotEqualTo(first.header("X-Treasury-Signature"));
            assertThat(Ledger.accountBalanceById(key.accountId()).orElseThrow()).isEqualByComparingTo("9992.11");
            assertThat(Ledger.accountBalanceById(destination).orElseThrow()).isEqualByComparingTo("10007.89");
            assertThat(Ledger.initiatedTransactionCountAfter(owner.getUuid(), before)).isEqualTo(1);
            assertThat(Ledger.initiatedPostingsAfter(owner.getUuid(), before)).hasSize(2);
        }
    }
    @Test
    void pausedOverdueRetryRemainsPendingAndResumesWithSameIdentity() throws Exception {
        var receiver = WebhookReceiver.get();
        String path = Usernames.fresh("Pause");
        try (var fault = receiver.failFirstAttempt(path, 503);
             var owner = MockPlayer.connectLocal(Usernames.fresh("PsOwn"));
             var peer = MockPlayer.connectLocal(Usernames.fresh("PsPe"))) {
            Ledger.waitForPersonalBalance(owner.getUuid(), Duration.ofSeconds(10));
            Ledger.waitForPersonalBalance(peer.getUuid(), Duration.ofSeconds(10));
            var key = ApiTokens.issuePersonal(owner.getUsername(), owner);
            int destination = Ledger.personalAccountId(peer.getUuid()).orElseThrow();
            String service = ApiTokens.mintServiceKey(peer.getUuid()).token();
            var subscription = RestApi.postWebhook(key.token(), receiver.url(path));
            assertThat(subscription.status()).isEqualTo(201);
            long id = subscription.json().path("id").asLong();
            long before = Ledger.transactionCheckpoint();
            var paid = RestApi.postTransfer(key.token(), "{\"toAccountId\":" + destination
                    + ",\"amount\":\"7.00\",\"memo\":\"PausedRetry\"}", null);
            assertThat(paid.status()).as(paid.body()).isEqualTo(200);
            long txn = paid.json().path("txnId").asLong();
            var first = receiver.await(id, e -> e.path("transaction").path("txnId").asLong() == txn, Duration.ofSeconds(30));
            assertThat(first.responseStatus()).isEqualTo(503);
            long delivery = first.json().path("deliveryId").asLong();
            Await.until("retry scheduled before pause", () -> row(delivery),
                    r -> r != null && r.attempts() == 1 && r.status().equals("PENDING"), Duration.ofSeconds(5));
            assertThat(row(delivery).futureAttempt()).isTrue();
            setActive(service, id, false);
            Await.until("paused retry becomes due", () -> row(delivery),
                    r -> !r.futureAttempt(), Duration.ofSeconds(40));
            // A later control event proves dispatch continues after the paused retry is due.
            var control = RestApi.postWebhook(key.token(), receiver.url(Usernames.fresh("PsCtl")));
            assertThat(control.status()).isEqualTo(201);
            long controlId = control.json().path("id").asLong();
            var next = RestApi.postTransfer(key.token(), "{\"toAccountId\":" + destination
                    + ",\"amount\":\"1.00\",\"memo\":\"PauseControl\"}", null);
            assertThat(next.status()).isEqualTo(200);
            long controlTxn = next.json().path("txnId").asLong();
            receiver.await(controlId, e -> e.path("transaction").path("txnId").asLong() == controlTxn, Duration.ofSeconds(30));
            assertThat(row(delivery).status()).isEqualTo("PENDING");
            assertThat(row(delivery).attempts()).isEqualTo(1);
            assertThat(row(delivery).httpStatus()).isEqualTo(503);
            assertThat(receiver.forSubscription(id).stream().filter(d -> d.json().path("transaction").path("txnId").asLong() == txn).count()).isEqualTo(1);
            setActive(service, id, true);
            Await.until("resumed overdue retry delivered", () -> row(delivery),
                    r -> r.status().equals("DELIVERED"), Duration.ofSeconds(15));
            assertThat(row(delivery).attempts()).isEqualTo(2);
            assertThat(row(delivery).httpStatus()).isEqualTo(200);
            assertThat(row(delivery).error()).isNull();
            var attempts = receiver.forSubscription(id).stream()
                    .filter(d -> d.json().path("transaction").path("txnId").asLong() == txn).toList();
            assertThat(attempts).hasSize(2);
            assertThat(attempts.get(1).rawBody()).isEqualTo(first.rawBody());
            assertThat(attempts.get(1).header("X-Treasury-Delivery")).isEqualTo(first.header("X-Treasury-Delivery"));
            assertThat(attempts.get(1).header("X-Treasury-Signature")).isEqualTo(first.header("X-Treasury-Signature"));
            assertThat(Ledger.accountBalanceById(key.accountId()).orElseThrow()).isEqualByComparingTo("9992");
            assertThat(Ledger.accountBalanceById(destination).orElseThrow()).isEqualByComparingTo("10008");
            assertThat(Ledger.initiatedTransactionCountAfter(owner.getUuid(), before)).isEqualTo(2);
            assertThat(Ledger.initiatedPostingsAfter(owner.getUuid(), before)).hasSize(4);
        }
    }

    @Test
    void deletingSubscriptionRemovesPendingRetryBeforeItBecomesDue() throws Exception {
        var receiver = WebhookReceiver.get();
        String path = Usernames.fresh("DelQ");
        try (var fault = receiver.failFirstAttempt(path, 503);
             var owner = MockPlayer.connectLocal(Usernames.fresh("DqOwn"));
             var peer = MockPlayer.connectLocal(Usernames.fresh("DqPe"))) {
            Ledger.waitForPersonalBalance(owner.getUuid(), Duration.ofSeconds(10));
            Ledger.waitForPersonalBalance(peer.getUuid(), Duration.ofSeconds(10));
            var key = ApiTokens.issuePersonal(owner.getUsername(), owner);
            int destination = Ledger.personalAccountId(peer.getUuid()).orElseThrow();
            String service = ApiTokens.mintServiceKey(peer.getUuid()).token();
            var subscription = RestApi.postWebhook(key.token(), receiver.url(path));
            assertThat(subscription.status()).isEqualTo(201);
            long id = subscription.json().path("id").asLong();
            long before = Ledger.transactionCheckpoint();
            var paid = RestApi.postTransfer(key.token(), "{\"toAccountId\":" + destination
                    + ",\"amount\":\"7.00\",\"memo\":\"DeletedRetry\"}", null);
            assertThat(paid.status()).as(paid.body()).isEqualTo(200);
            long txn = paid.json().path("txnId").asLong();
            var first = receiver.await(id, e -> e.path("transaction").path("txnId").asLong() == txn, Duration.ofSeconds(30));
            assertThat(first.responseStatus()).isEqualTo(503);
            long delivery = first.json().path("deliveryId").asLong();
            Await.until("retry scheduled before deletion", () -> row(delivery),
                    r -> r != null && r.attempts() == 1 && r.status().equals("PENDING"), Duration.ofSeconds(5));
            assertThat(row(delivery).futureAttempt()).isTrue();
            long remainingMillis = remainingRetryMillis(delivery);
            assertThat(remainingMillis).isBetween(1L, 30000L);
            var deleted = RestApi.call("DELETE", "/admin/webhooks/" + id, service, null);
            assertThat(deleted.status()).as(deleted.body()).isEqualTo(200);
            assertThat(deleted.json().path("affected").asInt()).isEqualTo(1);
            assertThat(row(delivery)).as("subscription deletion cascades to queued delivery").isNull();
            var repeated = RestApi.call("DELETE", "/admin/webhooks/" + id, service, null);
            assertThat(repeated.status()).isEqualTo(200);
            assertThat(repeated.json().path("affected").asInt()).isZero();
            // Preserve the original due-time boundary without altering queue timestamps.
            Thread.sleep(remainingMillis + 1000);
            // A later control event proves dispatch still works beyond the deleted retry's due time.
            var control = RestApi.postWebhook(key.token(), receiver.url(Usernames.fresh("DqCtl")));
            assertThat(control.status()).isEqualTo(201);
            long controlId = control.json().path("id").asLong();
            var next = RestApi.postTransfer(key.token(), "{\"toAccountId\":" + destination
                    + ",\"amount\":\"1.00\",\"memo\":\"DeleteControl\"}", null);
            assertThat(next.status()).isEqualTo(200);
            long controlTxn = next.json().path("txnId").asLong();
            receiver.await(controlId, e -> e.path("transaction").path("txnId").asLong() == controlTxn, Duration.ofSeconds(30));
            assertThat(row(delivery)).isNull();
            assertThat(receiver.forSubscription(id).stream()
                    .filter(d -> d.json().path("transaction").path("txnId").asLong() == txn).count()).isEqualTo(1);
            assertThat(receiver.forSubscription(id).stream()
                    .filter(d -> d.json().path("transaction").path("txnId").asLong() == controlTxn).count()).isZero();
            assertThat(Ledger.accountBalanceById(key.accountId()).orElseThrow()).isEqualByComparingTo("9992");
            assertThat(Ledger.accountBalanceById(destination).orElseThrow()).isEqualByComparingTo("10008");
            assertThat(Ledger.initiatedTransactionCountAfter(owner.getUuid(), before)).isEqualTo(2);
            assertThat(Ledger.initiatedPostingsAfter(owner.getUuid(), before)).hasSize(4);
        }
    }

    private static long remainingRetryMillis(long delivery) throws Exception {
        try (var c = DriverManager.getConnection(DevServer.DB_URL, DevServer.DB_USER, DevServer.DB_PASSWORD);
             var query = c.prepareStatement("SELECT TIMESTAMPDIFF(MICROSECOND, NOW(6), next_attempt_at) DIV 1000 FROM webhook_delivery WHERE delivery_id = ?")) {
            query.setLong(1, delivery);
            try (var rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getLong(1);
            }
        }
    }

    private static void setActive(String service, long id, boolean active) {
        var response = RestApi.call("PATCH", "/admin/webhooks/" + id + "/active", service,
                "{\"active\":" + active + "}");
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        assertThat(response.json().path("affected").asInt()).isEqualTo(1);
    }
    private record State(String status, int attempts, int httpStatus, String error, boolean futureAttempt) {}
    private static State row(long delivery) {
        try (var c = DriverManager.getConnection(DevServer.DB_URL, DevServer.DB_USER, DevServer.DB_PASSWORD);
             var q = c.prepareStatement("SELECT status, attempts, http_status, last_error, next_attempt_at > NOW() FROM webhook_delivery WHERE delivery_id = ?")) {
            q.setLong(1, delivery);
            try (var r = q.executeQuery()) {
                return r.next() ? new State(r.getString(1), r.getInt(2), r.getInt(3), r.getString(4), r.getBoolean(5)) : null;
            }
        } catch (java.sql.SQLException e) { throw new RuntimeException(e); }
    }
}
