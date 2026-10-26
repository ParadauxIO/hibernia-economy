package net.democracycraft.inttests.restapi;

import com.fasterxml.jackson.databind.JsonNode;
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
 * E2E coverage for {@code GET /api/v1/accounts/{accountId}/transactions}.
 * Each test obtains a fresh JWT and asserts both the HTTP body shape and
 * that the items returned reflect ledger postings actually present in DB.
 */
class TransactionsTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void selfHistoryReturnsRecentTransactions() {
        String sender = Usernames.fresh("Th");
        String target = Usernames.fresh("Tht");
        try (MockPlayer s = MockPlayer.connectLocal(sender);
             MockPlayer t = MockPlayer.connectLocal(target)) {
            Ledger.waitForPersonalBalance(s.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(t.getUuid(), TIMEOUT);

            IssuedKey key = ApiTokens.issuePersonal(sender, s);
            int toAcct = Ledger.personalAccountId(t.getUuid()).orElseThrow();

            // Seed history: two transfers from sender → target.
            RestApi.postTransfer(key.token(),
                    "{\"toAccountId\":" + toAcct + ",\"amount\":\"10.00\",\"memo\":\"first\"}", null);
            RestApi.postTransfer(key.token(),
                    "{\"toAccountId\":" + toAcct + ",\"amount\":\"20.00\",\"memo\":\"second\"}", null);

            Response r = RestApi.getTransactions(key.token(), key.accountId(), null, null);
            assertThat(r.status()).isEqualTo(200);

            JsonNode body = r.json();
            assertThat(body.path("accountId").asInt()).isEqualTo(key.accountId());
            assertThat(body.path("page").asInt()).isEqualTo(1);
            assertThat(body.path("totalItems").asInt()).isGreaterThanOrEqualTo(2);
            assertThat(body.path("items").isArray()).isTrue();

            // Most recent posting should be the second transfer (-20.00).
            JsonNode first = body.path("items").get(0);
            assertThat(first.path("amount").asText()).isEqualTo("-20.00");
            assertThat(first.path("memo").asText()).isEqualTo("second");
            assertThat(first.path("pluginSystem").asText()).isEqualTo("rest-api");
            assertThat(first.path("initiatorUuid").asText()).isEqualTo(s.getUuid().toString());
            // The other leg is the target's personal account.
            JsonNode counterparty = first.path("counterparty");
            assertThat(counterparty.path("accountId").asInt()).isEqualTo(toAcct);
            assertThat(counterparty.path("accountType").asText()).isEqualTo("PERSONAL");
            assertThat(counterparty.path("ownerUuid").asText()).isEqualTo(t.getUuid().toString());
        }
    }

    @Test
    void receiverHistoryShowsSenderAsCounterparty() {
        String sender = Usernames.fresh("Tr");
        String target = Usernames.fresh("Trt");
        try (MockPlayer s = MockPlayer.connectLocal(sender);
             MockPlayer t = MockPlayer.connectLocal(target)) {
            Ledger.waitForPersonalBalance(s.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(t.getUuid(), TIMEOUT);

            IssuedKey senderKey = ApiTokens.issuePersonal(sender, s);
            IssuedKey targetKey = ApiTokens.issuePersonal(target, t);

            Response sent = RestApi.postTransfer(senderKey.token(),
                    "{\"toAccountId\":" + targetKey.accountId() + ",\"amount\":\"7.50\",\"memo\":\"incoming\"}", null);
            assertThat(sent.status()).as(sent.body()).isBetween(200, 299);
            long txnId = sent.json().path("txnId").asLong();

            Response r = RestApi.getTransactions(targetKey.token(), targetKey.accountId(), null, null);
            assertThat(r.status()).isEqualTo(200);

            JsonNode first = r.json().path("items").get(0);
            assertThat(first.path("txnId").asLong()).isEqualTo(txnId);
            assertThat(first.path("amount").asText()).isEqualTo("7.50");
            JsonNode counterparty = first.path("counterparty");
            assertThat(counterparty.path("accountId").asInt()).isEqualTo(senderKey.accountId());
            assertThat(counterparty.path("accountType").asText()).isEqualTo("PERSONAL");
            assertThat(counterparty.path("ownerUuid").asText()).isEqualTo(s.getUuid().toString());
            assertThat(counterparty.has("displayName")).isTrue();
        }
    }

    @Test
    void historyHonoursPaginationLimit() {
        String sender = Usernames.fresh("Tp");
        String target = Usernames.fresh("Tpt");
        try (MockPlayer s = MockPlayer.connectLocal(sender);
             MockPlayer t = MockPlayer.connectLocal(target)) {
            Ledger.waitForPersonalBalance(s.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(t.getUuid(), TIMEOUT);

            IssuedKey key = ApiTokens.issuePersonal(sender, s);
            int toAcct = Ledger.personalAccountId(t.getUuid()).orElseThrow();

            // 3 transfers — request limit=2, expect items[]==2 across two pages.
            for (int i = 0; i < 3; i++) {
                RestApi.postTransfer(key.token(),
                        "{\"toAccountId\":" + toAcct + ",\"amount\":\"" + (1 + i) + ".00\",\"memo\":\"p" + i + "\"}", null);
            }

            Response p1 = RestApi.getTransactions(key.token(), key.accountId(), 1, 2);
            assertThat(p1.status()).isEqualTo(200);
            assertThat(p1.json().path("items").size()).isEqualTo(2);
            assertThat(p1.json().path("page").asInt()).isEqualTo(1);

            Response p2 = RestApi.getTransactions(key.token(), key.accountId(), 2, 2);
            assertThat(p2.status()).isEqualTo(200);
            assertThat(p2.json().path("items").size()).isGreaterThanOrEqualTo(1);
            assertThat(p2.json().path("page").asInt()).isEqualTo(2);
        }
    }

    @Test
    void historyRejectsForeignAccountWithoutMembership() {
        String sender = Usernames.fresh("Tf");
        String target = Usernames.fresh("Tft");
        try (MockPlayer s = MockPlayer.connectLocal(sender);
             MockPlayer t = MockPlayer.connectLocal(target)) {
            Ledger.waitForPersonalBalance(s.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(t.getUuid(), TIMEOUT);

            IssuedKey key = ApiTokens.issuePersonal(sender, s);
            int otherAcct = Ledger.personalAccountId(t.getUuid()).orElseThrow();

            Response r = RestApi.getTransactions(key.token(), otherAcct, null, null);

            assertThat(r.status()).isEqualTo(403);
            assertThat(r.error()).isEqualTo("FORBIDDEN");
        }
    }

    @Test
    void historyRejectsUnknownAccount() {
        String sender = Usernames.fresh("Tn");
        try (MockPlayer s = MockPlayer.connectLocal(sender)) {
            Ledger.waitForPersonalBalance(s.getUuid(), TIMEOUT);
            IssuedKey key = ApiTokens.issuePersonal(sender, s);

            Response r = RestApi.getTransactions(key.token(), 99_999_999, null, null);
            assertThat(r.status()).isEqualTo(404);
            assertThat(r.error()).isEqualTo("ACCOUNT_NOT_FOUND");
        }
    }

    @Test
    void historyRequiresAuth() {
        Response r = RestApi.getTransactions("not-a-token", 1, null, null);
        assertThat(r.status()).isEqualTo(401);
    }

    @Test
    void historyRejectsInvalidPageOrLimit() {
        String sender = Usernames.fresh("Ti");
        try (MockPlayer s = MockPlayer.connectLocal(sender)) {
            Ledger.waitForPersonalBalance(s.getUuid(), TIMEOUT);
            IssuedKey key = ApiTokens.issuePersonal(sender, s);

            Response zeroPage = RestApi.getTransactions(key.token(), key.accountId(), 0, null);
            assertThat(zeroPage.status()).isEqualTo(400);
            assertThat(zeroPage.error()).isEqualTo("INVALID_PARAM");

            Response hugeLimit = RestApi.getTransactions(key.token(), key.accountId(), null, 1000);
            assertThat(hugeLimit.status()).isEqualTo(400);
            assertThat(hugeLimit.error()).isEqualTo("INVALID_PARAM");
        }
    }
}
