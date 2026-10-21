package net.democracycraft.inttests.restapi;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.api.Test;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import static org.assertj.core.api.Assertions.assertThat;

class AdminWebhookDeliveryTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private final WebhookReceiver receiver = WebhookReceiver.get();

    @Test
    void adminChangesControlDeliveryDestinationSignaturePauseResumeAndDeletion() throws Exception {
        try (var owner = MockPlayer.connectLocal(Usernames.fresh("HwOwn"));
             var peer = MockPlayer.connectLocal(Usernames.fresh("HwPe"))) {
            Ledger.waitForPersonalBalance(owner.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(peer.getUuid(), TIMEOUT);
            var key = ApiTokens.issuePersonal(owner.getUsername(), owner);
            var peerKey = ApiTokens.issuePersonal(peer.getUsername(), peer);
            String service = ApiTokens.mintServiceKey(peer.getUuid()).token();
            String firstSecret = "a".repeat(64), nextSecret = "b".repeat(64);
            String firstPath = "admin-first-" + key.keyId(), nextPath = "admin-next-" + key.keyId();
            var created = RestApi.call("POST", "/admin/webhooks", service,
                    "{\"ownerUuid\":\"" + owner.getUuid() + "\",\"keyType\":\"PERSONAL\",\"accountId\":" + key.accountId()
                    + ",\"targetUrl\":\"" + receiver.url(firstPath) + "\",\"secret\":\"" + firstSecret + "\"}");
            assertThat(created.status()).as("%s", created.body()).isEqualTo(200);
            long subscription = created.json().path("subscriptionId").asLong();
            assertThat(subscription).isPositive();
            var controlResponse = RestApi.postWebhook(peerKey.token(), receiver.url("admin-control-" + key.keyId()));
            assertThat(controlResponse.status()).isEqualTo(201);
            long control = controlResponse.json().path("id").asLong();
            String base = "/admin/webhooks/" + subscription;
            String wrongOwner = "?ownerUuid=" + peer.getUuid();
            affected(RestApi.call("PATCH", base + "/url" + wrongOwner, service,
                    "{\"targetUrl\":\"" + receiver.url(nextPath) + "\"}"), 0);
            affected(RestApi.call("PATCH", base + "/secret" + wrongOwner, service,
                    "{\"secret\":\"" + nextSecret + "\"}"), 0);
            affected(RestApi.call("PATCH", base + "/active" + wrongOwner, service, "{\"active\":false}"), 0);
            affected(RestApi.call("DELETE", base + wrongOwner, service, null), 0);
            long first = pay(key.token(), peerKey.accountId());
            delivery(subscription, first, key.accountId(), peerKey.accountId(), firstPath, firstSecret);
            affected(RestApi.call("PATCH", base + "/url", service,
                    "{\"targetUrl\":\"" + receiver.url(nextPath) + "\"}"), 1);
            affected(RestApi.call("PATCH", base + "/secret", service,
                    "{\"secret\":\"" + nextSecret + "\"}"), 1);
            long second = pay(key.token(), peerKey.accountId());
            delivery(subscription, second, key.accountId(), peerKey.accountId(), nextPath, nextSecret);
            affected(RestApi.call("PATCH", base + "/active", service, "{\"active\":false}"), 1);
            long paused = pay(key.token(), peerKey.accountId());
            await(control, paused);
            assertThat(receiver.forSubscription(subscription).stream().filter(d -> txn(d) == paused).toList()).isEmpty();
            affected(RestApi.call("PATCH", base + "/active", service, "{\"active\":true}"), 1);
            long resumed = pay(key.token(), peerKey.accountId());
            delivery(subscription, resumed, key.accountId(), peerKey.accountId(), nextPath, nextSecret);
            affected(RestApi.call("DELETE", base, service, null), 1);
            affected(RestApi.call("DELETE", base, service, null), 0);
            long deleted = pay(key.token(), peerKey.accountId());
            await(control, deleted);
            assertThat(receiver.forSubscription(subscription).stream().filter(d -> txn(d) == deleted).toList()).isEmpty();
            assertThat(Ledger.accountBalanceById(key.accountId()).orElseThrow()).isEqualByComparingTo("9995.00");
            assertThat(Ledger.accountBalanceById(peerKey.accountId()).orElseThrow()).isEqualByComparingTo("10005.00");
        }
    }
    private void delivery(long subscription, long txn, int account, int other, String path, String secret) throws Exception {
        var d = await(subscription, txn);
        assertThat(d.path()).isEqualTo("/" + path);
        assertThat(d.json().path("accountId").asInt()).isEqualTo(account);
        var transaction = d.json().path("transaction");
        assertThat(transaction.path("amount").asText()).isEqualTo("-1.00");
        assertThat(transaction.path("memo").asText()).isEqualTo("AdminHookLifecycle");
        assertThat(transaction.path("counterparty").path("accountId").asInt()).isEqualTo(other);
        assertThat(d.header("X-Treasury-Delivery")).isEqualTo(d.json().path("deliveryId").asText());
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        assertThat(d.header("X-Treasury-Signature")).isEqualToIgnoringCase("sha256=" + HexFormat.of().formatHex(mac.doFinal(d.rawBody())));
    }
    private WebhookReceiver.Delivery await(long subscription, long txn) {
        return receiver.await(subscription, e -> e.path("transaction").path("txnId").asLong() == txn, TIMEOUT);
    }
    private static long txn(WebhookReceiver.Delivery d) { return d.json().path("transaction").path("txnId").asLong(); }
    private static void affected(RestApi.Response response, int count) {
        assertThat(response.status()).as("%s", response.body()).isEqualTo(200);
        assertThat(response.json().path("affected").asInt()).isEqualTo(count);
    }
    private static long pay(String token, int destination) {
        var r = RestApi.postTransfer(token, "{\"toAccountId\":" + destination + ",\"amount\":\"1.00\",\"memo\":\"AdminHookLifecycle\"}", null);
        assertThat(r.status()).as("%s", r.body()).isEqualTo(200);
        return r.json().path("txnId").asLong();
    }
}
