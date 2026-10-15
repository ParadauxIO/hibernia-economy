package net.democracycraft.inttests.restapi;

import com.fasterxml.jackson.databind.JsonNode;
import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.ApiTokens;
import net.democracycraft.inttests.support.ApiTokens.IssuedKey;
import net.democracycraft.inttests.support.Ledger;
import net.democracycraft.inttests.support.RestApi;
import net.democracycraft.inttests.support.RestApi.Response;
import net.democracycraft.inttests.support.Usernames;
import net.democracycraft.inttests.support.WebhookReceiver;
import net.democracycraft.inttests.support.WebhookReceiver.Delivery;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E coverage for webhook delivery: subscriptions are registered through
 * {@code POST /api/v1/webhooks}, transfers go through {@code POST /transfers},
 * and the running REST API's dispatcher POSTs signed events to the local
 * {@link WebhookReceiver}. Delivery waits cover the dispatcher's settlement
 * lag (3 s) and poll interval (2 s).
 */
class WebhookDeliveryTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(30);

    private final WebhookReceiver receiver = WebhookReceiver.get();

    @Test
    void deliversSignedTransactionEventsWithCounterpartyToBothSides() {
        String sender = Usernames.fresh("Ws");
        String target = Usernames.fresh("Wst");
        try (MockPlayer s = MockPlayer.connectLocal(sender);
             MockPlayer t = MockPlayer.connectLocal(target)) {
            Ledger.waitForPersonalBalance(s.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(t.getUuid(), TIMEOUT);

            IssuedKey senderKey = ApiTokens.issuePersonal(sender, s);
            IssuedKey targetKey = ApiTokens.issuePersonal(target, t);
            JsonNode senderHook = subscribe(senderKey, "sender");
            JsonNode targetHook = subscribe(targetKey, "target");

            long txnId = transfer(senderKey.token(), targetKey.accountId(), "12.34", "webhook-e2e");

            // Sender: a debit whose counterparty is the target's account.
            Delivery debit = receiver.await(senderHook.path("id").asLong(),
                    e -> e.path("transaction").path("txnId").asLong() == txnId, DELIVERY_TIMEOUT);
            assertSigned(debit, senderHook.path("secret").asText());
            JsonNode event = debit.json();
            assertThat(event.path("event").asText()).isEqualTo("transaction");
            assertThat(event.path("accountId").asInt()).isEqualTo(senderKey.accountId());
            assertThat(debit.header("X-Treasury-Delivery")).isEqualTo(event.path("deliveryId").asText());
            JsonNode txn = event.path("transaction");
            assertThat(txn.path("amount").asText()).isEqualTo("-12.34");
            assertThat(txn.path("memo").asText()).isEqualTo("webhook-e2e");
            assertThat(txn.path("pluginSystem").asText()).isEqualTo("rest-api");
            assertThat(txn.path("initiatorUuid").asText()).isEqualTo(s.getUuid().toString());
            assertThat(txn.path("counterparty").path("accountId").asInt()).isEqualTo(targetKey.accountId());
            assertThat(txn.path("counterparty").path("accountType").asText()).isEqualTo("PERSONAL");
            assertThat(txn.path("counterparty").path("ownerUuid").asText()).isEqualTo(t.getUuid().toString());

            // Target: the same transaction as a credit whose counterparty is the sender.
            Delivery credit = receiver.await(targetHook.path("id").asLong(),
                    e -> e.path("transaction").path("txnId").asLong() == txnId, DELIVERY_TIMEOUT);
            assertSigned(credit, targetHook.path("secret").asText());
            JsonNode creditTxn = credit.json().path("transaction");
            assertThat(credit.json().path("accountId").asInt()).isEqualTo(targetKey.accountId());
            assertThat(creditTxn.path("amount").asText()).isEqualTo("12.34");
            assertThat(creditTxn.path("counterparty").path("accountId").asInt()).isEqualTo(senderKey.accountId());
            assertThat(creditTxn.path("counterparty").path("ownerUuid").asText()).isEqualTo(s.getUuid().toString());

            // Exactly one event per subscription for the transaction (no duplicate
            // deliveries). Other events can arrive too: a subscription also gets
            // transactions the dispatcher hadn't ingested yet when it was created,
            // such as the player's starting balance on a fresh stack.
            assertThat(eventsFor(senderHook, txnId)).hasSize(1);
            assertThat(eventsFor(targetHook, txnId)).hasSize(1);
        }
    }

    @Test
    void pausedWebhookReceivesNothing() {
        String sender = Usernames.fresh("Wp");
        String target = Usernames.fresh("Wpt");
        try (MockPlayer s = MockPlayer.connectLocal(sender);
             MockPlayer t = MockPlayer.connectLocal(target)) {
            Ledger.waitForPersonalBalance(s.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(t.getUuid(), TIMEOUT);

            IssuedKey senderKey = ApiTokens.issuePersonal(sender, s);
            IssuedKey targetKey = ApiTokens.issuePersonal(target, t);
            long paused = subscribe(senderKey, "paused").path("id").asLong();
            long control = subscribe(targetKey, "control").path("id").asLong();

            Response patch = RestApi.patchWebhook(senderKey.token(), paused, "{\"active\":false}");
            assertThat(patch.status()).as(patch.body()).isEqualTo(200);
            assertThat(patch.json().path("active").asBoolean()).isFalse();

            long txnId = transfer(senderKey.token(), targetKey.accountId(), "3.00", "paused");

            // The control delivery proves the dispatcher has handled this transaction.
            receiver.await(control, e -> e.path("transaction").path("txnId").asLong() == txnId, DELIVERY_TIMEOUT);
            assertThat(receiver.forSubscription(paused)).isEmpty();
        }
    }

    @Test
    void onlyTheConfiguredTestTargetBypassesTheSsrfGuard() {
        String player = Usernames.fresh("Wr");
        try (MockPlayer p = MockPlayer.connectLocal(player)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            IssuedKey key = ApiTokens.issuePersonal(player, p);

            String allowed = receiver.url("ok");
            String otherPort = allowed.replaceFirst(":(\\d+)/", ":1/");
            for (String url : new String[] {otherPort, "http://localhost:8099/hook", "https://127.0.0.1/hook"}) {
                Response r = RestApi.postWebhook(key.token(), url);
                assertThat(r.status()).as(url + ": " + r.body()).isEqualTo(400);
                assertThat(r.error()).as(url).isEqualTo("INVALID_WEBHOOK_URL");
            }
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    /** Registers the receiver for the key's account; returns the create response (id + secret). */
    private JsonNode subscribe(IssuedKey key, String label) {
        Response r = RestApi.postWebhook(key.token(), receiver.url(label));
        assertThat(r.status()).as("create webhook: %s", r.body()).isEqualTo(201);
        JsonNode body = r.json();
        assertThat(body.path("scope").asText()).isEqualTo("account");
        assertThat(body.path("accountId").asInt()).isEqualTo(key.accountId());
        assertThat(body.path("secret").asText()).isNotBlank();
        return body;
    }

    private List<Delivery> eventsFor(JsonNode hook, long txnId) {
        return receiver.forSubscription(hook.path("id").asLong()).stream()
                .filter(d -> d.json().path("transaction").path("txnId").asLong() == txnId)
                .toList();
    }

    private static long transfer(String token, int toAccountId, String amount, String memo) {
        Response r = RestApi.postTransfer(token,
                "{\"toAccountId\":" + toAccountId + ",\"amount\":\"" + amount + "\",\"memo\":\"" + memo + "\"}",
                null);
        assertThat(r.status()).as("transfer %s: %s", memo, r.body()).isBetween(200, 299);
        return r.json().path("txnId").asLong();
    }

    /** Checks the delivery headers and recomputes the HMAC over the raw body. */
    private static void assertSigned(Delivery d, String secret) {
        assertThat(d.header("Content-Type")).isEqualTo("application/json");
        assertThat(d.header("User-Agent")).isEqualTo("Treasury-Webhook/1.0");
        assertThat(d.header("X-Treasury-Event")).isEqualTo("transaction");
        assertThat(d.header("X-Treasury-Signature")).isEqualToIgnoringCase("sha256=" + hmacHex(secret, d.rawBody()));
    }

    private static String hmacHex(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
