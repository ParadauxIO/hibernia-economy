package net.democracycraft.inttests.restapi;

import com.fasterxml.jackson.databind.JsonNode;
import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.ApiTokens;
import net.democracycraft.inttests.support.ApiTokens.IssuedKey;
import net.democracycraft.inttests.support.Await;
import net.democracycraft.inttests.support.Ledger;
import net.democracycraft.inttests.support.RestApi;
import net.democracycraft.inttests.support.RestApi.Response;
import net.democracycraft.inttests.support.Usernames;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E coverage for {@code GET /api/v1/accounts/{accountId}/transactions/feed}.
 * The feed only returns transactions settled at least
 * {@code FEED_SETTLEMENT_LAG_SECONDS} (default 3 s) ago, so reads poll until
 * the expected items have settled.
 */
class TransactionFeedTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final Duration SETTLE_TIMEOUT = Duration.ofSeconds(20);

    @Test
    void feedReturnsSettledTransfersOldestFirstWithCounterparties() {
        String sender = Usernames.fresh("Fs");
        String target = Usernames.fresh("Fst");
        try (MockPlayer s = MockPlayer.connectLocal(sender);
             MockPlayer t = MockPlayer.connectLocal(target)) {
            Ledger.waitForPersonalBalance(s.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(t.getUuid(), TIMEOUT);

            IssuedKey senderKey = ApiTokens.issuePersonal(sender, s);
            IssuedKey targetKey = ApiTokens.issuePersonal(target, t);
            int toAcct = Ledger.personalAccountId(t.getUuid()).orElseThrow();

            long first = transfer(senderKey.token(), toAcct, "10.00", "feed-a");
            long second = transfer(senderKey.token(), toAcct, "20.00", "feed-b");
            long since = first - 1;

            // Sender's side: debits, counterparty is the target's account.
            JsonNode body = waitForFeed(senderKey.token(), senderKey.accountId(), since, 2);
            JsonNode items = body.path("items");
            assertThat(txnIds(items)).containsExactly(first, second);
            assertThat(items.get(0).path("amount").asText()).isEqualTo("-10.00");
            assertThat(items.get(1).path("amount").asText()).isEqualTo("-20.00");
            assertThat(items.get(0).path("memo").asText()).isEqualTo("feed-a");
            for (JsonNode item : items) {
                JsonNode counterparty = item.path("counterparty");
                assertThat(counterparty.path("accountId").asInt()).isEqualTo(toAcct);
                assertThat(counterparty.path("accountType").asText()).isEqualTo("PERSONAL");
                assertThat(counterparty.path("ownerUuid").asText()).isEqualTo(t.getUuid().toString());
            }
            assertThat(body.path("accountId").asInt()).isEqualTo(senderKey.accountId());
            assertThat(body.path("cursor").asLong()).isEqualTo(since);
            assertThat(body.path("nextCursor").asLong()).isEqualTo(second);
            assertThat(body.path("hasMore").asBoolean()).isFalse();

            // Target's side: the same transactions as credits, counterparty is the sender.
            JsonNode credits = waitForFeed(targetKey.token(), targetKey.accountId(), since, 2).path("items");
            assertThat(txnIds(credits)).containsExactly(first, second);
            assertThat(credits.get(0).path("amount").asText()).isEqualTo("10.00");
            for (JsonNode item : credits) {
                JsonNode counterparty = item.path("counterparty");
                assertThat(counterparty.path("accountId").asInt()).isEqualTo(senderKey.accountId());
                assertThat(counterparty.path("accountType").asText()).isEqualTo("PERSONAL");
                assertThat(counterparty.path("ownerUuid").asText()).isEqualTo(s.getUuid().toString());
            }

            // Caught up: polling from nextCursor returns nothing and keeps the cursor.
            Response caughtUp = RestApi.getTransactionFeed(senderKey.token(), senderKey.accountId(), second, null);
            assertThat(caughtUp.status()).isEqualTo(200);
            assertThat(caughtUp.json().path("items").size()).isZero();
            assertThat(caughtUp.json().path("nextCursor").isNull()).isTrue();
            assertThat(caughtUp.json().path("hasMore").asBoolean()).isFalse();
        }
    }

    @Test
    void feedPagesByLimitAndCursor() {
        String sender = Usernames.fresh("Fp");
        String target = Usernames.fresh("Fpt");
        try (MockPlayer s = MockPlayer.connectLocal(sender);
             MockPlayer t = MockPlayer.connectLocal(target)) {
            Ledger.waitForPersonalBalance(s.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(t.getUuid(), TIMEOUT);

            IssuedKey key = ApiTokens.issuePersonal(sender, s);
            int toAcct = Ledger.personalAccountId(t.getUuid()).orElseThrow();

            List<Long> txns = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                txns.add(transfer(key.token(), toAcct, (1 + i) + ".00", "fp" + i));
            }
            long since = txns.get(0) - 1;
            waitForFeed(key.token(), key.accountId(), since, 3);

            Response p1 = RestApi.getTransactionFeed(key.token(), key.accountId(), since, 2);
            assertThat(p1.status()).isEqualTo(200);
            assertThat(txnIds(p1.json().path("items"))).containsExactly(txns.get(0), txns.get(1));
            assertThat(p1.json().path("hasMore").asBoolean()).isTrue();

            long next = p1.json().path("nextCursor").asLong();
            assertThat(next).isEqualTo(txns.get(1));
            // A new payment arrives after the client has saved page one's cursor.
            long fourth = transfer(key.token(), toAcct, "4.00", "fp-between-pages");
            waitForFeed(key.token(), key.accountId(), next, 2);
            Response p2 = RestApi.getTransactionFeed(key.token(), key.accountId(), next, 2);
            assertThat(p2.status()).isEqualTo(200);
            assertThat(txnIds(p2.json().path("items"))).containsExactly(txns.get(2), fourth);
            assertThat(p2.json().path("cursor").asLong()).isEqualTo(next);
            assertThat(p2.json().path("nextCursor").asLong()).isEqualTo(fourth);
            // Contract: a full page signals that more MAY exist, even at the tail.
            assertThat(p2.json().path("hasMore").asBoolean()).isTrue();
            assertThat(p2.json().path("items").get(0).path("amount").asText()).isEqualTo("-3.00");
            assertThat(p2.json().path("items").get(1).path("amount").asText()).isEqualTo("-4.00");
            assertThat(p2.json().path("items").get(1).path("memo").asText()).isEqualTo("fp-between-pages");
            for (JsonNode item : p2.json().path("items")) {
                assertThat(item.path("counterparty").path("accountId").asInt()).isEqualTo(toAcct);
            }
            Response empty = RestApi.getTransactionFeed(key.token(), key.accountId(), fourth, 2);
            assertThat(empty.status()).isEqualTo(200);
            assertThat(empty.json().path("cursor").asLong()).isEqualTo(fourth);
            assertThat(empty.json().path("items").size()).isZero();
            assertThat(empty.json().path("nextCursor").isNull()).isTrue();
            assertThat(empty.json().path("hasMore").asBoolean()).isFalse();

            // An empty response must not prevent resuming from the last non-null cursor.
            long fifth = transfer(key.token(), toAcct, "5.00", "fp-after-caught-up");
            waitForFeed(key.token(), key.accountId(), fourth, 1);
            long checkpoint = Ledger.transactionCheckpoint();
            Response resumed = RestApi.getTransactionFeed(key.token(), key.accountId(), fourth, 2);
            assertThat(resumed.status()).isEqualTo(200);
            assertThat(txnIds(resumed.json().path("items"))).containsExactly(fifth);
            assertThat(resumed.json().path("nextCursor").asLong()).isEqualTo(fifth);
            assertThat(resumed.json().path("hasMore").asBoolean()).isFalse();
            assertThat(resumed.json().path("items").get(0).path("amount").asText()).isEqualTo("-5.00");
            assertThat(resumed.json().path("items").get(0).path("memo").asText()).isEqualTo("fp-after-caught-up");
            Response replay = RestApi.getTransactionFeed(key.token(), key.accountId(), since, 10);
            assertThat(replay.status()).isEqualTo(200);
            assertThat(txnIds(replay.json().path("items"))).containsExactly(txns.get(0), txns.get(1), txns.get(2), fourth, fifth);
            assertThat(Ledger.personalBalance(s.getUuid()).orElseThrow()).isEqualByComparingTo("9985");
            assertThat(Ledger.personalBalance(t.getUuid()).orElseThrow()).isEqualByComparingTo("10015");
            assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint);
        }
    }

    @Test
    void feedRejectsForeignAccountWithoutMembership() {
        String sender = Usernames.fresh("Ff");
        String target = Usernames.fresh("Fft");
        try (MockPlayer s = MockPlayer.connectLocal(sender);
             MockPlayer t = MockPlayer.connectLocal(target)) {
            Ledger.waitForPersonalBalance(s.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(t.getUuid(), TIMEOUT);

            IssuedKey key = ApiTokens.issuePersonal(sender, s);
            int otherAcct = Ledger.personalAccountId(t.getUuid()).orElseThrow();

            Response r = RestApi.getTransactionFeed(key.token(), otherAcct, null, null);
            assertThat(r.status()).isEqualTo(403);
            assertThat(r.error()).isEqualTo("FORBIDDEN");
        }
    }

    @Test
    void feedRejectsUnknownAccount() {
        String sender = Usernames.fresh("Fn");
        try (MockPlayer s = MockPlayer.connectLocal(sender)) {
            Ledger.waitForPersonalBalance(s.getUuid(), TIMEOUT);
            IssuedKey key = ApiTokens.issuePersonal(sender, s);

            Response r = RestApi.getTransactionFeed(key.token(), 99_999_999, null, null);
            assertThat(r.status()).isEqualTo(404);
            assertThat(r.error()).isEqualTo("ACCOUNT_NOT_FOUND");
        }
    }

    @Test
    void feedRequiresAuth() {
        Response r = RestApi.getTransactionFeed("not-a-token", 1, null, null);
        assertThat(r.status()).isEqualTo(401);
    }

    @Test
    void feedRejectsInvalidSinceOrLimit() {
        String sender = Usernames.fresh("Fi");
        try (MockPlayer s = MockPlayer.connectLocal(sender)) {
            Ledger.waitForPersonalBalance(s.getUuid(), TIMEOUT);
            IssuedKey key = ApiTokens.issuePersonal(sender, s);

            Response negativeSince = RestApi.getTransactionFeed(key.token(), key.accountId(), -1L, null);
            assertThat(negativeSince.status()).isEqualTo(400);
            assertThat(negativeSince.error()).isEqualTo("INVALID_PARAM");

            Response hugeLimit = RestApi.getTransactionFeed(key.token(), key.accountId(), null, 1001);
            assertThat(hugeLimit.status()).isEqualTo(400);
            assertThat(hugeLimit.error()).isEqualTo("INVALID_PARAM");
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    /** POSTs a transfer and returns its txn_id. */
    private static long transfer(String token, int toAccountId, String amount, String memo) {
        Response r = RestApi.postTransfer(token,
                "{\"toAccountId\":" + toAccountId + ",\"amount\":\"" + amount + "\",\"memo\":\"" + memo + "\"}",
                null);
        assertThat(r.status()).as("transfer %s: %s", memo, r.body()).isBetween(200, 299);
        return r.json().path("txnId").asLong();
    }

    /** Polls the feed from {@code since} until at least {@code count} items have settled. */
    private static JsonNode waitForFeed(String token, int accountId, long since, int count) {
        Response last = Await.until("the feed for account " + accountId + " to hold " + count + " items",
                () -> {
                    Response r = RestApi.getTransactionFeed(token, accountId, since, null);
                    assertThat(r.status()).as("feed: %s", r.body()).isEqualTo(200);
                    return r;
                },
                r -> r.json().path("items").size() >= count, SETTLE_TIMEOUT);
        return last.json();
    }

    private static List<Long> txnIds(JsonNode items) {
        List<Long> ids = new ArrayList<>();
        items.forEach(item -> ids.add(item.path("txnId").asLong()));
        return ids;
    }
}
