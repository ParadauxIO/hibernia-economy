package io.paradaux.treasuryrestapi.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.paradaux.treasuryrestapi.testsupport.EmbeddedDbIT;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * Rate-limit handling in {@link WebhookDispatcherService} (PAR-563), against a local
 * HTTP endpoint that answers like Discord: 429 with {@code Retry-After}, or 2xx with
 * {@code X-RateLimit-Remaining}/{@code X-RateLimit-Reset-After}. The endpoint is
 * allowed through the SSRF check as an integration test target.
 *
 * <p>max-attempts=1 and failure-threshold=1 make any attempt spent on a 429 visible
 * at once: the delivery would turn FAILED and the subscription would auto-disable.
 */
@TestPropertySource(
        properties = {
            "treasury.webhook.enabled=true",
            "treasury.webhook.settlement-lag-seconds=0",
            "treasury.webhook.max-attempts=1",
            "treasury.webhook.failure-threshold=1",
            "treasury.webhook.retry-base-seconds=30",
            "treasury.webhook.retry-max-seconds=3600",
            "treasury.webhook.initial-delay-ms=600000",
            "treasury.webhook.poll-ms=600000",
        })
class WebhookRateLimitIT extends EmbeddedDbIT {

    private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID INITIATOR = UUID.fromString("99999999-9999-9999-9999-999999999999");

    /** Path → response headers and status. Each request is recorded in {@link #hits}. */
    private static final Map<String, Response> RESPONSES = Map.of(
            "/limited", new Response(429, Map.of("Retry-After", "30")),
            "/limited-discord", new Response(429, Map.of("Retry-After", "1", "X-RateLimit-Reset-After", "42.5")),
            "/limited-bare", new Response(429, Map.of()),
            "/limited-huge", new Response(429, Map.of("Retry-After", "999999")),
            "/drained", new Response(204, Map.of("X-RateLimit-Remaining", "0", "X-RateLimit-Reset-After", "2.5")),
            "/ok", new Response(204, Map.of("X-RateLimit-Remaining", "4", "X-RateLimit-Reset-After", "2.5")));

    private static final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
    /** Concurrent requests in flight per path, and the most seen at once. */
    private static final Map<String, AtomicInteger> inFlight = new ConcurrentHashMap<>();

    private static final List<String> overlapped = new CopyOnWriteArrayList<>();
    private static final HttpServer SERVER = start();

    record Response(int status, Map<String, String> headers) {}

    private static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                AtomicInteger busy = inFlight.computeIfAbsent(path, k -> new AtomicInteger());
                if (busy.incrementAndGet() > 1) overlapped.add(path);
                try {
                    exchange.getRequestBody().readAllBytes();
                    hits.computeIfAbsent(path, k -> new AtomicInteger()).incrementAndGet();
                    Thread.sleep(50); // long enough for a parallel send to overlap
                    Response r = RESPONSES.getOrDefault(path, new Response(404, Map.of()));
                    r.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
                    exchange.sendResponseHeaders(r.status(), -1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    busy.decrementAndGet();
                    exchange.close();
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void testTarget(DynamicPropertyRegistry registry) {
        registry.add(
                "treasury.webhook.insecure-test-targets",
                () -> "127.0.0.1:" + SERVER.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        SERVER.stop(0);
    }

    @Autowired
    private WebhookDispatcherService dispatcher;

    /** The real lease (always granted without Redis), spied so a test can take it away mid-batch. */
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private WebhookRedisLock lock;

    @BeforeEach
    void resetEndpoint() {
        hits.clear();
        inFlight.clear();
        overlapped.clear();
    }

    private static String url(String path) {
        return "http://127.0.0.1:" + SERVER.getAddress().getPort() + path;
    }

    private long subscription(int accountId, String path) {
        insertAccount(accountId, "PERSONAL", null, "Account " + accountId);
        return insertSubscription(OWNER, "PERSONAL", accountId, null, url(path), "s".repeat(64), true, 0);
    }

    private long due(long subscription, int accountId) {
        long txn = insertSettledTxn(accountId, "10.00", "pay", INITIATOR);
        return insertDueDelivery(subscription, txn, accountId, 0);
    }

    private static int hitsOn(String path) {
        AtomicInteger n = hits.get(path);
        return n == null ? 0 : n.get();
    }

    @Test
    void rateLimitedDeliveryWaitsRetryAfterWithoutUsingAnAttemptOrCountingAFailure() {
        long sub = subscription(1, "/limited");
        long first = due(sub, 1);
        long second = due(sub, 1);

        dispatcher.tick();

        // One request, then the subscription's whole queue waits out the window.
        assertThat(hitsOn("/limited")).isEqualTo(1);
        for (long delivery : List.of(first, second)) {
            assertThat(deliveryStatus(delivery)).isEqualTo("PENDING");
            assertThat(deliveryAttempts(delivery)).isZero();
            assertThat(secondsUntilNextAttempt(delivery)).isBetween(28L, 31L);
        }
        assertThat(subscriptionFailures(sub)).isZero();
        assertThat(subscriptionActive(sub)).isTrue();

        // Still inside the window: nothing is due, nothing is sent.
        dispatcher.tick();
        assertThat(hitsOn("/limited")).isEqualTo(1);
    }

    @Test
    void waitsForTheLongerOfRetryAfterAndDiscordsResetAfter() {
        long sub = subscription(1, "/limited-discord");
        long delivery = due(sub, 1);

        dispatcher.tick();

        assertThat(secondsUntilNextAttempt(delivery)).isBetween(41L, 44L);
    }

    @Test
    void rateLimitWithoutAWaitHeaderFallsBackToTheRetryBase() {
        long sub = subscription(1, "/limited-bare");
        long delivery = due(sub, 1);

        dispatcher.tick();

        assertThat(secondsUntilNextAttempt(delivery)).isBetween(28L, 31L);
        assertThat(deliveryAttempts(delivery)).isZero();
    }

    @Test
    void anAbsurdRetryAfterIsCappedAtTheRetryMaximum() {
        long sub = subscription(1, "/limited-huge");
        long delivery = due(sub, 1);

        dispatcher.tick();

        assertThat(secondsUntilNextAttempt(delivery)).isBetween(3590L, 3601L);
    }

    @Test
    void anEmptyBucketOnSuccessPausesTheRestOfTheQueueBeforeA429() {
        long sub = subscription(1, "/drained");
        long sent = due(sub, 1);
        long waiting = due(sub, 1);

        dispatcher.tick();

        assertThat(hitsOn("/drained")).isEqualTo(1);
        assertThat(deliveryStatus(sent)).isEqualTo("DELIVERED");
        assertThat(deliveryStatus(waiting)).isEqualTo("PENDING");
        assertThat(deliveryAttempts(waiting)).isZero();
        assertThat(secondsUntilNextAttempt(waiting)).isBetween(2L, 4L);
    }

    @Test
    void oneWebhooksDeliveriesAreSentOneAtATimeWhileOthersProceed() {
        long busy = subscription(1, "/ok");
        for (int i = 0; i < 4; i++) due(busy, 1);
        long limited = subscription(2, "/limited");
        due(limited, 2);
        long other = subscription(3, "/ok-other");
        long otherDelivery = due(other, 3);

        dispatcher.tick();

        // All four reached the busy endpoint, never two at once.
        assertThat(hitsOn("/ok")).isEqualTo(4);
        assertThat(overlapped).doesNotContain("/ok");
        // The rate-limited webhook didn't hold up the rest of the batch.
        assertThat(hitsOn("/limited")).isEqualTo(1);
        assertThat(hitsOn("/ok-other")).isEqualTo(1);
        assertThat(deliveryAttempts(otherDelivery)).isEqualTo(1);
    }

    @Test
    void stopsSendingAsSoonAsTheLeaseIsLostMidBatch() {
        long sub = subscription(1, "/ok");
        long sent = due(sub, 1);
        long left = due(sub, 1);
        // Granted for the tick and the first send, then another replica holds it.
        org.mockito.Mockito.doReturn(true, true, false).when(lock).tryAcquireOrRenew();

        dispatcher.tick();

        assertThat(hitsOn("/ok")).isEqualTo(1);
        assertThat(deliveryStatus(sent)).isEqualTo("DELIVERED");
        assertThat(deliveryStatus(left)).isEqualTo("PENDING");
        assertThat(deliveryAttempts(left)).isZero();
    }
}
