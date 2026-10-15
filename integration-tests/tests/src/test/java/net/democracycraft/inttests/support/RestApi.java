package net.democracycraft.inttests.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Thin HTTP client for the Treasury REST API. Reads the base URL from system
 * property {@code restapi.base} or env var {@code RESTAPI_BASE}; defaults to
 * {@code http://localhost:8080/api/v1}.
 *
 * <p>Returns a small {@link Response} record so tests can assert on status,
 * raw body, and parsed JSON without having to import an HTTP library.
 *
 * <p>Idempotency-key support is by intent: callers explicitly opt in by passing
 * a non-null {@code idemKey} to {@link #postTransfer}.
 */
public final class RestApi {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public static final String BASE_URL = resolve("restapi.base", "RESTAPI_BASE",
            "http://localhost:8080/api/v1");

    public static Response postRotate(String token) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE_URL + "/auth/rotate"))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return send(req);
    }

    public static Response postRotateNoAuth() {
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE_URL + "/auth/rotate"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return send(req);
    }

    public static Response postTransfer(String token, String body, String idemKey) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(BASE_URL + "/transfers"))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (idemKey != null) b.header("Idempotency-Key", idemKey);
        return send(b.build());
    }

    public static Response getTransactions(String token, int accountId, Integer page, Integer limit) {
        StringBuilder url = new StringBuilder(BASE_URL + "/accounts/" + accountId + "/transactions");
        char sep = '?';
        if (page != null) {
            url.append(sep).append("page=").append(page);
            sep = '&';
        }
        if (limit != null) {
            url.append(sep).append("limit=").append(limit);
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(url.toString()))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        return send(req);
    }

    /** GET /accounts/{accountId}/transactions/feed — the forward cursor "pull" feed. */
    public static Response getTransactionFeed(String token, int accountId, Long since, Integer limit) {
        StringBuilder url = new StringBuilder(BASE_URL + "/accounts/" + accountId + "/transactions/feed");
        char sep = '?';
        if (since != null) {
            url.append(sep).append("since=").append(since);
            sep = '&';
        }
        if (limit != null) {
            url.append(sep).append("limit=").append(limit);
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(url.toString()))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        return send(req);
    }

    /** GET /firms/me — returns the firm associated with a BUSINESS token. */
    public static Response getMyFirm(String token) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE_URL + "/firms/me"))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        return send(req);
    }

    /** GET /firms/me/balance — sum across all accounts owned by a BUSINESS-token's firm. */
    public static Response getMyFirmBalance(String token) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE_URL + "/firms/me/balance"))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        return send(req);
    }

    /** GET /firms/{firmName}/balance — public summed balance for a named firm. */
    public static Response getFirmBalanceByName(String token, String firmName) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE_URL + "/firms/" + firmName + "/balance"))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        return send(req);
    }

    private static Response send(HttpRequest req) {
        try {
            HttpResponse<String> r = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            return new Response(r.statusCode(), r.body(), r.headers().map());
        } catch (Exception e) {
            throw new RuntimeException("HTTP call failed: " + req.method() + " " + req.uri(), e);
        }
    }

    public record Response(int status, String body, Map<String, List<String>> headers) {
        public JsonNode json() {
            try { return JSON.readTree(body); }
            catch (Exception e) { throw new RuntimeException("Body is not JSON: " + body, e); }
        }
        public String error() { return json().path("error").asText(); }

        /** First value of a header, case-insensitive. */
        public Optional<String> header(String name) {
            return headers.entrySet().stream()
                    .filter(e -> e.getKey().equalsIgnoreCase(name))
                    .findFirst()
                    .flatMap(e -> e.getValue().stream().findFirst());
        }
    }

    private static String resolve(String prop, String env, String fallback) {
        String v = System.getProperty(prop);
        if (v != null && !v.isBlank()) return v;
        v = System.getenv(env);
        if (v != null && !v.isBlank()) return v;
        return fallback;
    }

    private RestApi() {}
}
