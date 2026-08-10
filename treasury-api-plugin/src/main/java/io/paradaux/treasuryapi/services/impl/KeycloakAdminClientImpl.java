package io.paradaux.treasuryapi.services.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.treasuryapi.model.config.KeycloakConfiguration;
import io.paradaux.treasuryapi.services.KeycloakAdminClient;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thin Keycloak Admin REST client for the in-game linking flow: authenticates
 * with the plugin's service-account client and sets a user's
 * {@code minecraft_uuid} (+ {@code minecraft_name}) attribute. Uses the JDK
 * HTTP client and Paper-provided Gson — no extra dependencies.
 */
@Singleton
public class KeycloakAdminClientImpl implements KeycloakAdminClient {

    private final KeycloakConfiguration cfg;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    // setMinecraftAttributes is a GET-merge-PUT of the whole Keycloak user
    // representation, so two concurrent links for the SAME user can interleave and
    // the later PUT clobbers the earlier one (or an unrelated attribute changed in
    // between). Serialise per-user with a small fixed lock stripe keyed by the
    // subject id — bounded memory, and only same-sub operations ever contend (ADT-113).
    private static final int LOCK_STRIPES = 64;
    private final ReentrantLock[] subjectLocks;

    @Inject
    public KeycloakAdminClientImpl(KeycloakConfiguration cfg) {
        this.cfg = cfg;
        this.subjectLocks = new ReentrantLock[LOCK_STRIPES];
        for (int i = 0; i < LOCK_STRIPES; i++) {
            this.subjectLocks[i] = new ReentrantLock();
        }
    }

    private ReentrantLock lockFor(String sub) {
        return subjectLocks[Math.floorMod(sub.hashCode(), LOCK_STRIPES)];
    }

    @Override
    public boolean isEnabled() {
        return cfg.isEnabled();
    }

    /** Merges the minecraft attributes into the Keycloak user identified by {@code sub}. */
    @Override
    public void setMinecraftAttributes(String sub, UUID uuid, String name) throws Exception {
        // Serialise the read-modify-write per subject so concurrent links for the
        // same user can't clobber one another (ADT-113).
        ReentrantLock lock = lockFor(sub);
        lock.lock();
        try {
            doSetMinecraftAttributes(sub, uuid, name);
        } finally {
            lock.unlock();
        }
    }

    private void doSetMinecraftAttributes(String sub, UUID uuid, String name) throws Exception {
        String token = serviceAccountToken();
        String userUrl = cfg.getBaseUrl() + "/admin/realms/" + cfg.getRealm() + "/users/" + sub;

        HttpResponse<String> get = http.send(
                HttpRequest.newBuilder(URI.create(userUrl))
                        .timeout(Duration.ofSeconds(15))
                        .header("Authorization", "Bearer " + token)
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (get.statusCode() / 100 != 2) {
            throw new IllegalStateException("Keycloak GET user " + sub + " failed: HTTP " + get.statusCode());
        }

        JsonObject user = JsonParser.parseString(get.body()).getAsJsonObject();
        JsonElement attrsEl = user.get("attributes");
        JsonObject attrs = (attrsEl != null && attrsEl.isJsonObject()) ? attrsEl.getAsJsonObject() : new JsonObject();
        attrs.add("minecraft_uuid", single(uuid.toString()));
        if (name != null) attrs.add("minecraft_name", single(name));
        user.add("attributes", attrs);

        HttpResponse<String> put = http.send(
                HttpRequest.newBuilder(URI.create(userUrl))
                        .timeout(Duration.ofSeconds(15))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(user.toString()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (put.statusCode() / 100 != 2) {
            throw new IllegalStateException("Keycloak PUT user " + sub + " failed: HTTP " + put.statusCode());
        }
    }

    private String serviceAccountToken() throws Exception {
        String tokenUrl = cfg.getBaseUrl() + "/realms/" + cfg.getRealm() + "/protocol/openid-connect/token";
        // Env var takes precedence so the service-account secret can be injected
        // rather than committed in plaintext config.yml (ADT-38).
        String clientSecret = System.getenv("TREASURYAPI_KEYCLOAK_CLIENT_SECRET");
        if (clientSecret == null || clientSecret.isBlank()) clientSecret = cfg.getClientSecret();
        String form = "grant_type=client_credentials"
                + "&client_id=" + enc(cfg.getClientId())
                + "&client_secret=" + enc(clientSecret);
        HttpResponse<String> res = http.send(
                HttpRequest.newBuilder(URI.create(tokenUrl))
                        .timeout(Duration.ofSeconds(15))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            throw new IllegalStateException("Keycloak token request failed: HTTP " + res.statusCode());
        }
        return JsonParser.parseString(res.body()).getAsJsonObject().get("access_token").getAsString();
    }

    private static JsonArray single(String value) {
        JsonArray a = new JsonArray();
        a.add(value);
        return a;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
