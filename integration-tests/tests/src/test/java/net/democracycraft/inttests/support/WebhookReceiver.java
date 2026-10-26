package net.democracycraft.inttests.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToIntFunction;
import java.util.function.Predicate;

/**
 * Local HTTP endpoint the REST API delivers webhooks to during the live suite.
 * It listens on {@code 127.0.0.1:}{@value #DEFAULT_PORT} (or
 * {@code WEBHOOK_RECEIVER_PORT}); {@code ops/integration/run.sh} starts the API
 * with that exact {@code host:port} in {@code treasury.webhook.insecure-test-targets}
 * so the SSRF guard lets deliveries through.
 *
 * <p>One receiver is shared by the whole test JVM and records every POST it
 * gets, answering 200 unless a test installs a path-scoped response rule.
 * Tests pick out their own deliveries by subscription id.
 */
public final class WebhookReceiver {

    private static final int DEFAULT_PORT = 8099;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static WebhookReceiver instance;

    private final Map<String, ToIntFunction<JsonNode>> responses = new ConcurrentHashMap<>();
    private final int port;
    private final List<Delivery> received = new CopyOnWriteArrayList<>();

    /** One recorded POST: its headers and the raw body (what the signature covers). */
    public record Delivery(Map<String, List<String>> headers, byte[] rawBody, String path, int responseStatus) {
        public String header(String name) {
            return headers.entrySet().stream()
                    .filter(e -> e.getKey().equalsIgnoreCase(name))
                    .flatMap(e -> e.getValue().stream())
                    .findFirst().orElse(null);
        }

        @Override
        public String toString() {
            return new String(rawBody, StandardCharsets.UTF_8);
        }

        public JsonNode json() {
            try {
                return JSON.readTree(rawBody);
            } catch (IOException e) {
                throw new UncheckedIOException("Delivery body is not JSON: "
                        + new String(rawBody, StandardCharsets.UTF_8), e);
            }
        }
    }

    private WebhookReceiver(int port) {
        this.port = port;
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            server.createContext("/", this::handle);
            server.start();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not start the webhook receiver on 127.0.0.1:" + port, e);
        }
    }

    /** The shared receiver, started on first use. */
    public static synchronized WebhookReceiver get() {
        if (instance == null) {
            String env = System.getenv("WEBHOOK_RECEIVER_PORT");
            instance = new WebhookReceiver(env == null || env.isBlank() ? DEFAULT_PORT : Integer.parseInt(env));
        }
        return instance;
    }

    /** URL to register as a webhook target; {@code path} only labels the request. */
    public String url(String path) {
        return "http://127.0.0.1:" + port + "/" + path;
    }

    /** The first delivery for {@code subscriptionId} matching {@code filter}, waiting up to {@code timeout}. */
    public Delivery await(long subscriptionId, Predicate<JsonNode> filter, Duration timeout) {
        try {
            return Await.until("a matching webhook delivery for subscription " + subscriptionId,
                    () -> find(subscriptionId, filter).orElse(null), d -> d != null, timeout);
        } catch (AssertionError e) {
            throw new AssertionError(e.getMessage() + " (deliveries received for it: "
                    + forSubscription(subscriptionId).stream().map(d -> d.json().toString()).toList() + ")", e);
        }
    }

    /** Every delivery recorded so far for {@code subscriptionId}. */
    public List<Delivery> forSubscription(long subscriptionId) {
        List<Delivery> out = new ArrayList<>();
        for (Delivery d : received) {
            if (d.json().path("subscriptionId").asLong() == subscriptionId) out.add(d);
        }
        return out;
    }

    private Optional<Delivery> find(long subscriptionId, Predicate<JsonNode> filter) {
        return forSubscription(subscriptionId).stream().filter(d -> filter.test(d.json())).findFirst();
    }

    /** Return a fixed failure on this test path until the scope closes. */
    public AutoCloseable respondWith(String path, int status) {
        String requestPath = "/" + path;
        ToIntFunction<JsonNode> response = event -> status;
        if (responses.putIfAbsent(requestPath, response) != null) {
            throw new IllegalStateException("Response already configured for " + requestPath);
        }
        return () -> responses.remove(requestPath, response);
    }

    /** Fail once per delivery ID on this unique path, then acknowledge retries. */
    public AutoCloseable failFirstAttempt(String path, int status) {
        var seen = ConcurrentHashMap.<Long>newKeySet();
        String requestPath = "/" + path;
        ToIntFunction<JsonNode> response = event -> seen.add(event.path("deliveryId").asLong()) ? status : 200;
        if (responses.putIfAbsent(requestPath, response) != null) {
            throw new IllegalStateException("Response already configured for " + requestPath);
        }
        return () -> responses.remove(requestPath, response);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            byte[] body = exchange.getRequestBody().readAllBytes();
            String path = exchange.getRequestURI().getPath();
            int status = 200;
            if ("POST".equals(exchange.getRequestMethod())) {
                var response = responses.get(path);
                if (response != null) status = response.applyAsInt(JSON.readTree(body));
                received.add(new Delivery(Map.copyOf(exchange.getRequestHeaders()), body, path, status));
            }
            exchange.sendResponseHeaders(status, -1);
        }
    }
}
