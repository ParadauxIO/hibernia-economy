package io.paradaux.treasuryrestapi.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.paradaux.treasuryrestapi.testsupport.EmbeddedDbIT;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * Pacing for hosts with an unreported per-channel limit (PAR-565). The local test
 * endpoint is configured as the only paced host, standing in for Discord.
 */
@TestPropertySource(
        properties = {
            "treasury.webhook.enabled=true",
            "treasury.webhook.settlement-lag-seconds=0",
            "treasury.webhook.retry-base-seconds=30",
            "treasury.webhook.retry-max-seconds=3600",
            "treasury.webhook.initial-delay-ms=600000",
            "treasury.webhook.poll-ms=600000",
            "treasury.webhook.paced-hosts=127.0.0.1",
            "treasury.webhook.paced-interval-seconds=3",
        })
class WebhookPacingIT extends EmbeddedDbIT {

    private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID INITIATOR = UUID.fromString("99999999-9999-9999-9999-999999999999");

    /** Path → response headers on a 204. */
    private static final Map<String, Map<String, String>> HEADERS = Map.of(
            "/feed", Map.of("X-RateLimit-Remaining", "4", "X-RateLimit-Reset-After", "1"),
            "/feed-other", Map.of("X-RateLimit-Remaining", "4", "X-RateLimit-Reset-After", "1"),
            "/drained-long", Map.of("X-RateLimit-Remaining", "0", "X-RateLimit-Reset-After", "20"));

    private static final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
    private static final HttpServer SERVER = start();

    private static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                exchange.getRequestBody().readAllBytes();
                hits.computeIfAbsent(path, k -> new AtomicInteger()).incrementAndGet();
                HEADERS.getOrDefault(path, Map.of())
                        .forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
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

    @BeforeEach
    void resetEndpoint() {
        hits.clear();
    }

    private long subscription(int accountId, String path) {
        insertAccount(accountId, "PERSONAL", null, "Account " + accountId);
        String url = "http://127.0.0.1:" + SERVER.getAddress().getPort() + path;
        return insertSubscription(OWNER, "PERSONAL", accountId, null, url, "s".repeat(64), true, 0);
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
    void aPacedWebhookPostsOncePerIntervalEvenWithBucketRemaining() {
        long sub = subscription(1, "/feed");
        long sent = due(sub, 1);
        long next = due(sub, 1);
        long later = due(sub, 1);

        dispatcher.tick();

        // The bucket still has room, but the paced host gets one post per interval.
        assertThat(hitsOn("/feed")).isEqualTo(1);
        assertThat(deliveryStatus(sent)).isEqualTo("DELIVERED");
        for (long waiting : new long[] {next, later}) {
            assertThat(deliveryStatus(waiting)).isEqualTo("PENDING");
            assertThat(deliveryAttempts(waiting)).isZero();
            assertThat(secondsUntilNextAttempt(waiting)).isBetween(2L, 3L);
        }

        // Inside the interval nothing more is sent.
        dispatcher.tick();
        assertThat(hitsOn("/feed")).isEqualTo(1);
    }

    @Test
    void pacingOneWebhookDoesNotHoldUpAnother() {
        long first = subscription(1, "/feed");
        due(first, 1);
        due(first, 1);
        long second = subscription(2, "/feed-other");
        long other = due(second, 2);

        dispatcher.tick();

        assertThat(hitsOn("/feed")).isEqualTo(1);
        assertThat(hitsOn("/feed-other")).isEqualTo(1);
        assertThat(deliveryStatus(other)).isEqualTo("DELIVERED");
    }

    @Test
    void aLongerReportedWaitWinsOverThePace() {
        long sub = subscription(1, "/drained-long");
        due(sub, 1);
        long waiting = due(sub, 1);

        dispatcher.tick();

        assertThat(secondsUntilNextAttempt(waiting)).isBetween(19L, 21L);
    }
}
