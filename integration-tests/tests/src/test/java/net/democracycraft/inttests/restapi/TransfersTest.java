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

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E coverage for {@code POST /api/v1/transfers}. Each test obtains a real
 * JWT via the in-game issue command, drives the REST endpoint, and asserts
 * both the HTTP response and the resulting DB state via {@link Ledger}.
 */
class TransfersTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static String body(int toAccountId, String amount, String memo) {
        StringBuilder sb = new StringBuilder("{")
                .append("\"toAccountId\":").append(toAccountId)
                .append(",\"amount\":\"").append(amount).append('"');
        if (memo != null) sb.append(",\"memo\":\"").append(memo).append('"');
        return sb.append('}').toString();
    }

    @Test
    void transferDebitsSenderAndCreditsRecipient() {
        String sender = Usernames.fresh("Tx");
        String target = Usernames.fresh("Tt");
        try (MockPlayer s = MockPlayer.connectLocal(sender);
             MockPlayer t = MockPlayer.connectLocal(target)) {
            BigDecimal sStart = Ledger.waitForPersonalBalance(s.getUuid(), TIMEOUT);
            BigDecimal tStart = Ledger.waitForPersonalBalance(t.getUuid(), TIMEOUT);
            IssuedKey key = ApiTokens.issuePersonal(sender, s);
            int toAcct = Ledger.personalAccountId(t.getUuid()).orElseThrow();

            Response r = RestApi.postTransfer(key.token(), body(toAcct, "150.00", "rent"), null);

            assertThat(r.status()).isEqualTo(200);
            JsonNode b = r.json();
            assertThat(b.path("fromAccountId").asInt()).isEqualTo(key.accountId());
            assertThat(b.path("toAccountId").asInt()).isEqualTo(toAcct);
            assertThat(b.path("amount").asText()).isEqualTo("150.00");
            assertThat(b.path("memo").asText()).isEqualTo("rent");
            assertThat(b.path("txnId").asLong()).isPositive();

            Ledger.waitForPersonalBalance(s.getUuid(), sStart.subtract(new BigDecimal("150.00")), TIMEOUT);
            Ledger.waitForPersonalBalance(t.getUuid(), tStart.add(new BigDecimal("150.00")), TIMEOUT);
        }
    }

    @Test
    void transferRejectsSelfTarget() {
        String sender = Usernames.fresh("Ts");
        try (MockPlayer s = MockPlayer.connectLocal(sender)) {
            Ledger.waitForPersonalBalance(s.getUuid(), TIMEOUT);
            IssuedKey key = ApiTokens.issuePersonal(sender, s);

            Response r = RestApi.postTransfer(key.token(),
                    body(key.accountId(), "10.00", "self"), null);

            assertThat(r.status()).isEqualTo(400);
            assertThat(r.error()).isEqualTo("SELF_TRANSFER");
        }
    }

    @Test
    void transferRejectsNonPositiveAmount() {
        String sender = Usernames.fresh("Tn");
        String target = Usernames.fresh("Tnt");
        try (MockPlayer s = MockPlayer.connectLocal(sender);
             MockPlayer t = MockPlayer.connectLocal(target)) {
            Ledger.waitForPersonalBalance(s.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(t.getUuid(), TIMEOUT);
            IssuedKey key = ApiTokens.issuePersonal(sender, s);
            int toAcct = Ledger.personalAccountId(t.getUuid()).orElseThrow();

            Response zero = RestApi.postTransfer(key.token(), body(toAcct, "0.00", null), null);
            assertThat(zero.status()).isEqualTo(400);
            assertThat(zero.error()).isEqualTo("INVALID_AMOUNT");

            Response neg = RestApi.postTransfer(key.token(), body(toAcct, "-5.00", null), null);
            assertThat(neg.status()).isEqualTo(400);
            assertThat(neg.error()).isEqualTo("INVALID_AMOUNT");
        }
    }

    @Test
    void transferRejectsUnknownDestinationAccount() {
        String sender = Usernames.fresh("Tu");
        try (MockPlayer s = MockPlayer.connectLocal(sender)) {
            Ledger.waitForPersonalBalance(s.getUuid(), TIMEOUT);
            IssuedKey key = ApiTokens.issuePersonal(sender, s);

            Response r = RestApi.postTransfer(key.token(),
                    body(99_999_999, "10.00", null), null);

            assertThat(r.status()).isEqualTo(404);
            assertThat(r.error()).isEqualTo("ACCOUNT_NOT_FOUND");
        }
    }

    @Test
    void transferRejectsInsufficientFunds() {
        String sender = Usernames.fresh("Ti");
        String target = Usernames.fresh("Tit");
        try (MockPlayer s = MockPlayer.connectLocal(sender);
             MockPlayer t = MockPlayer.connectLocal(target)) {
            BigDecimal start = Ledger.waitForPersonalBalance(s.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(t.getUuid(), TIMEOUT);
            IssuedKey key = ApiTokens.issuePersonal(sender, s);
            int toAcct = Ledger.personalAccountId(t.getUuid()).orElseThrow();

            Response r = RestApi.postTransfer(key.token(),
                    body(toAcct, start.add(new BigDecimal("100000")).toPlainString(), null), null);

            assertThat(r.status()).isEqualTo(422);
            assertThat(r.error()).isEqualTo("INSUFFICIENT_FUNDS");
            // Sender balance unchanged.
            assertThat(Ledger.personalBalance(s.getUuid()).orElseThrow())
                    .isEqualByComparingTo(start);
        }
    }

    @Test
    void idempotencyReplayReturnsSameTxn() {
        String sender = Usernames.fresh("Td");
        String target = Usernames.fresh("Tdt");
        try (MockPlayer s = MockPlayer.connectLocal(sender);
             MockPlayer t = MockPlayer.connectLocal(target)) {
            BigDecimal sStart = Ledger.waitForPersonalBalance(s.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(t.getUuid(), TIMEOUT);
            IssuedKey key = ApiTokens.issuePersonal(sender, s);
            int toAcct = Ledger.personalAccountId(t.getUuid()).orElseThrow();
            String idem = "test-idem-" + System.nanoTime();
            String reqBody = body(toAcct, "75.00", "idem-test");

            Response first = RestApi.postTransfer(key.token(), reqBody, idem);
            assertThat(first.status()).isEqualTo(200);
            long firstTxnId = first.json().path("txnId").asLong();

            Response second = RestApi.postTransfer(key.token(), reqBody, idem);
            assertThat(second.status()).isEqualTo(200);
            assertThat(second.json().path("txnId").asLong())
                    .as("idempotent replay should return same txnId")
                    .isEqualTo(firstTxnId);

            // Sender debited once, not twice.
            Ledger.waitForPersonalBalance(s.getUuid(), sStart.subtract(new BigDecimal("75.00")), TIMEOUT);
        }
    }

    @Test
    void idempotencyMismatchedBodyReturnsConflict() {
        String sender = Usernames.fresh("Tdc");
        String target = Usernames.fresh("Tdct");
        try (MockPlayer s = MockPlayer.connectLocal(sender);
             MockPlayer t = MockPlayer.connectLocal(target)) {
            Ledger.waitForPersonalBalance(s.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(t.getUuid(), TIMEOUT);
            IssuedKey key = ApiTokens.issuePersonal(sender, s);
            int toAcct = Ledger.personalAccountId(t.getUuid()).orElseThrow();
            String idem = "test-idem-conflict-" + System.nanoTime();

            Response first = RestApi.postTransfer(key.token(),
                    body(toAcct, "10.00", null), idem);
            assertThat(first.status()).isEqualTo(200);

            // Same idem-key, different amount.
            Response second = RestApi.postTransfer(key.token(),
                    body(toAcct, "20.00", null), idem);
            assertThat(second.status()).isEqualTo(409);
            assertThat(second.error()).isEqualTo("IDEMPOTENCY_CONFLICT");
        }
    }

    @Test
    void transferRejectsMissingAuthHeader() {
        String sender = Usernames.fresh("Tm");
        String target = Usernames.fresh("Tmt");
        try (MockPlayer s = MockPlayer.connectLocal(sender);
             MockPlayer t = MockPlayer.connectLocal(target)) {
            Ledger.waitForPersonalBalance(s.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(t.getUuid(), TIMEOUT);
            int toAcct = Ledger.personalAccountId(t.getUuid()).orElseThrow();

            Response r = RestApi.postTransfer("",
                    body(toAcct, "10.00", null), null);

            assertThat(r.status()).isEqualTo(401);
            // INVALID_TOKEN here because the empty string still has the
            // "Bearer " prefix in the wrapper but no JWT to parse.
            assertThat(r.error()).isIn("MISSING_TOKEN", "INVALID_TOKEN");
        }
    }
}
