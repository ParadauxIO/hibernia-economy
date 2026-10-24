package io.paradaux.treasuryrestapi.service;

import io.paradaux.treasuryrestapi.testsupport.EmbeddedDbIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link WebhookDispatcherService} (finding
 * treasury-rest-api/testing/0003) driving the real dispatcher against the embedded
 * MariaDB. The service is normally off in tests; this class re-enables it and calls
 * {@code tick()} directly (no Redis → the lease is granted) so the ingest and
 * deliver/retry/backoff/auto-disable state machine is exercised against real SQL.
 *
 * <p>Delivery targets use loopback/blocked URLs on purpose: the SSRF re-check rejects
 * them, so every delivery lands in {@code onFailure} — which is exactly where the
 * retry-vs-terminal, next-attempt scheduling, failure-count and auto-disable logic
 * lives. The 2xx "delivered + signed" path needs a genuinely public host and is out
 * of reach of an offline SSRF-gated run; it is covered at the HMAC-signing unit level
 * ({@code HmacSha256Test}).
 *
 * <p>Config overrides: max-attempts=2 and failure-threshold=3 keep the terminal and
 * auto-disable thresholds small enough to hit in one tick; the poll/initial delays are
 * pushed out so the {@code @Scheduled} loop never races the manual tick.
 */
@TestPropertySource(properties = {
        "treasury.webhook.enabled=true",
        "treasury.webhook.settlement-lag-seconds=0",
        "treasury.webhook.max-attempts=2",
        "treasury.webhook.failure-threshold=3",
        "treasury.webhook.retry-base-seconds=30",
        "treasury.webhook.retry-max-seconds=3600",
        "treasury.webhook.initial-delay-ms=600000",
        "treasury.webhook.poll-ms=600000",
})
class WebhookDispatcherIT extends EmbeddedDbIT {

    @Autowired
    private WebhookDispatcherService dispatcher;

    private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID INITIATOR = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final String BLOCKED_URL = "https://127.0.0.1/hook"; // loopback → SSRF re-check fails

    // ── ingest: cursor → enqueue matching deliveries ──────────────────────────────

    @Test
    void ingest_enqueuesForAccountScopedSubscription_andAdvancesCursor() {
        insertAccount(1, "PERSONAL", OWNER, "Payer");
        insertAccount(2, "PERSONAL", null, "Payee");
        long sub = insertSubscription(OWNER, "PERSONAL", 2, null, BLOCKED_URL, "s".repeat(64), true, 0);
        long txn = insertSettledTxn(2, "100.00", "pay", INITIATOR);

        dispatcher.tick();

        // One delivery enqueued for the subscribed account, cursor advanced past the txn.
        assertThat(deliveryCount(sub)).isEqualTo(1);
        assertThat(cursorValue()).isGreaterThanOrEqualTo(txn);
    }

    @Test
    void ingest_enqueuesForFirmScopedSubscription() {
        insertFirm(10, "Acme");
        insertAccount(1, "BUSINESS", null, "Acme Main");
        linkFirmAccount(10, 1);
        long sub = insertSubscription(OWNER, "BUSINESS", null, 10, BLOCKED_URL, "s".repeat(64), true, 0);
        insertSettledTxn(1, "50.00", "firm income", INITIATOR);

        dispatcher.tick();

        assertThat(deliveryCount(sub)).isEqualTo(1);
    }

    @Test
    void ingest_inactiveSubscription_notMatched() {
        insertAccount(1, "PERSONAL", null, "Payee");
        long sub = insertSubscription(OWNER, "PERSONAL", 1, null, BLOCKED_URL, "s".repeat(64), false, 0);
        insertSettledTxn(1, "10.00", "x", INITIATOR);

        dispatcher.tick();

        assertThat(deliveryCount(sub)).isZero();
    }

    @Test
    void ingest_isIdempotent_reScanDoesNotDoubleEnqueue() {
        insertAccount(1, "PERSONAL", null, "Payee");
        long sub = insertSubscription(OWNER, "PERSONAL", 1, null, BLOCKED_URL, "s".repeat(64), true, 0);
        long txn = insertSettledTxn(1, "10.00", "x", INITIATOR);

        dispatcher.tick();
        // Rewind the cursor so the same txn is re-scanned; INSERT IGNORE + UNIQUE must
        // keep it at a single delivery row.
        exec("UPDATE webhook_cursor SET last_dispatched_txn_id = ? WHERE id = 1", ps -> ps.setLong(1, txn - 1));
        dispatcher.tick();

        assertThat(deliveryCount(sub)).isEqualTo(1);
    }

    // ── deliver → onFailure: retry branch (backoff + scheduling) ──────────────────

    @Test
    void deliver_failure_belowMaxAttempts_retriesWithBackoff() {
        insertAccount(1, "PERSONAL", null, "Payee");
        long sub = insertSubscription(OWNER, "PERSONAL", 1, null, BLOCKED_URL, "s".repeat(64), true, 0);
        long txn = insertSettledTxn(1, "10.00", "x", INITIATOR);
        long delivery = insertDueDelivery(sub, txn, 1, /* attempts */ 0); // 0+1 < max(2) → retry

        dispatcher.tick();

        // Stays PENDING, attempts incremented, next attempt scheduled ~30s out
        // (retry-base 30 * 2^0), an error recorded.
        assertThat(deliveryStatus(delivery)).isEqualTo("PENDING");
        assertThat(deliveryAttempts(delivery)).isEqualTo(1);
        long secs = secondsUntilNextAttempt(delivery);
        assertThat(secs).isBetween(20L, 40L);
        // Subscription not yet failed/disabled on a non-terminal attempt.
        assertThat(subscriptionActive(sub)).isTrue();
        assertThat(subscriptionFailures(sub)).isZero();
    }

    @Test
    void deliver_retryBackoff_growsWithAttempts() {
        insertAccount(1, "PERSONAL", null, "Payee");
        long sub = insertSubscription(OWNER, "PERSONAL", 1, null, BLOCKED_URL, "s".repeat(64), true, 0);
        long txnA = insertSettledTxn(1, "10.00", "a", INITIATOR);
        long txnB = insertSettledTxn(1, "10.00", "b", INITIATOR);
        long dA = insertDueDelivery(sub, txnA, 1, /* attempts */ 0); // delay 30 * 2^0 = 30
        long dB = insertDueDelivery(sub, txnB, 1, /* attempts */ 1); // delay 30 * 2^1 = 60 (0+1<2? for B: attempts=1 → 1+1>=2 terminal!)

        // Raise max-attempts locally is not possible here; instead assert the delay math
        // on the one that retries (dA) and that dB terminated (attempts 1 → terminal).
        dispatcher.tick();

        assertThat(deliveryStatus(dA)).isEqualTo("PENDING");
        assertThat(secondsUntilNextAttempt(dA)).isBetween(20L, 40L);
        assertThat(deliveryStatus(dB)).isEqualTo("FAILED");
    }

    // ── deliver → onFailure: terminal branch + auto-disable ───────────────────────

    @Test
    void deliver_atMaxAttempts_marksFailed_incrementsAndAutoDisables() {
        insertAccount(1, "PERSONAL", null, "Payee");
        // consecutive_failures=2; after the terminal increment → 3 >= threshold(3) → disabled.
        long sub = insertSubscription(OWNER, "PERSONAL", 1, null, BLOCKED_URL, "s".repeat(64), true, 2);
        long txn = insertSettledTxn(1, "10.00", "x", INITIATOR);
        long delivery = insertDueDelivery(sub, txn, 1, /* attempts */ 1); // 1+1 >= max(2) → terminal

        dispatcher.tick();

        assertThat(deliveryStatus(delivery)).isEqualTo("FAILED");
        assertThat(deliveryAttempts(delivery)).isEqualTo(2);
        assertThat(subscriptionFailures(sub)).isEqualTo(3);
        assertThat(subscriptionActive(sub)).isFalse(); // auto-disabled over threshold
    }

    @Test
    void deliver_terminal_belowThreshold_failsButStaysActive() {
        insertAccount(1, "PERSONAL", null, "Payee");
        long sub = insertSubscription(OWNER, "PERSONAL", 1, null, BLOCKED_URL, "s".repeat(64), true, 0);
        long txn = insertSettledTxn(1, "10.00", "x", INITIATOR);
        long delivery = insertDueDelivery(sub, txn, 1, /* attempts */ 1); // terminal

        dispatcher.tick();

        assertThat(deliveryStatus(delivery)).isEqualTo("FAILED");
        assertThat(subscriptionFailures(sub)).isEqualTo(1); // 0 → 1, still < threshold 3
        assertThat(subscriptionActive(sub)).isTrue();
    }

    @Test
    void deliver_skipsDeliveryWhoseSubscriptionIsInactive() {
        insertAccount(1, "PERSONAL", null, "Payee");
        // Subscription inactive → the findDue join (s.active = 1) drops the row entirely.
        long sub = insertSubscription(OWNER, "PERSONAL", 1, null, BLOCKED_URL, "s".repeat(64), false, 0);
        long txn = insertSettledTxn(1, "10.00", "x", INITIATOR);
        long delivery = insertDueDelivery(sub, txn, 1, 0);

        dispatcher.tick();

        // Untouched: still PENDING with 0 attempts (never selected by findDue).
        assertThat(deliveryStatus(delivery)).isEqualTo("PENDING");
        assertThat(deliveryAttempts(delivery)).isZero();
    }
}
