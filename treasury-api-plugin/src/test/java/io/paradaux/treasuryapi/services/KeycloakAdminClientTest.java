package io.paradaux.treasuryapi.services;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import io.paradaux.treasuryapi.model.config.KeycloakConfiguration;
import io.paradaux.treasuryapi.services.impl.KeycloakAdminClientImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage for {@link KeycloakAdminClientImpl} that does NOT need a live Keycloak:
 *
 * <ul>
 *   <li>the ADT-113 per-subject lock striping (same sub → same lock; a bounded,
 *       fixed number of stripes) via reflection;</li>
 *   <li>the real token-request form + admin URL construction + attribute-merge
 *       GET→PUT flow, driven against a captured in-process HTTP server so the
 *       exact URLs and request bodies the client builds are asserted;</li>
 *   <li>{@code isEnabled} delegating to config.</li>
 * </ul>
 */
class KeycloakAdminClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    private static KeycloakConfiguration config(String baseUrl, boolean enabled) {
        return new KeycloakConfiguration() {
            @Override public boolean isEnabled() { return enabled; }
            @Override public String getBaseUrl() { return baseUrl; }
            @Override public String getRealm() { return "minecraft"; }
            @Override public String getClientId() { return "treasury-api-plugin"; }
            @Override public String getClientSecret() { return "s3cr3t value/with+chars"; }
        };
    }

    @Test
    void isEnabled_reflectsConfig() {
        assertTrue(new KeycloakAdminClientImpl(config("http://x", true)).isEnabled());
        assertFalse(new KeycloakAdminClientImpl(config("http://x", false)).isEnabled());
    }

    @Test
    void lockFor_sameSubject_returnsSameLock_andStripesAreBounded() throws Exception {
        KeycloakAdminClientImpl client = new KeycloakAdminClientImpl(config("http://x", true));

        ReentrantLock l1 = lockFor(client, "sub-abc");
        ReentrantLock l2 = lockFor(client, "sub-abc");
        assertSame(l1, l2, "the same subject id must always map to the same lock (serialises same-sub writes)");

        // The stripe array is the bounded fixed pool (LOCK_STRIPES = 64): distinct
        // subjects draw from it, so memory can't grow with subject count.
        Field f = KeycloakAdminClientImpl.class.getDeclaredField("subjectLocks");
        f.setAccessible(true);
        ReentrantLock[] stripes = (ReentrantLock[]) f.get(client);
        assertEquals(64, stripes.length);
    }

    private static ReentrantLock lockFor(KeycloakAdminClientImpl client, String sub) throws Exception {
        var m = KeycloakAdminClientImpl.class.getDeclaredMethod("lockFor", String.class);
        m.setAccessible(true);
        return (ReentrantLock) m.invoke(client, sub);
    }

    @Test
    void setMinecraftAttributes_authenticates_thenMergesAttributesIntoUserViaPut() throws Exception {
        List<String> requestUris = new CopyOnWriteArrayList<>();
        String[] tokenForm = new String[1];
        String[] putBody = new String[1];

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String realm = "minecraft";

        server.createContext("/realms/" + realm + "/protocol/openid-connect/token", ex -> {
            requestUris.add(ex.getRequestURI().toString());
            tokenForm[0] = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            respond(ex, 200, "{\"access_token\":\"tok-123\"}");
        });
        // The admin user endpoint: GET returns an existing user with a pre-existing
        // attribute; PUT captures the merged representation the client sends back.
        server.createContext("/admin/realms/" + realm + "/users/", ex -> {
            requestUris.add(ex.getRequestMethod() + " " + ex.getRequestURI());
            if ("GET".equals(ex.getRequestMethod())) {
                String auth = ex.getRequestHeaders().getFirst("Authorization");
                assertEquals("Bearer tok-123", auth, "GET must present the fetched service-account token");
                respond(ex, 200, "{\"id\":\"the-sub\",\"attributes\":{\"existing\":[\"keep\"]}}");
            } else { // PUT
                putBody[0] = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                respond(ex, 204, "");
            }
        });
        server.start();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        KeycloakAdminClientImpl client = new KeycloakAdminClientImpl(config(baseUrl, true));

        UUID uuid = UUID.randomUUID();
        client.setMinecraftAttributes("the-sub", uuid, "Alice");

        // Token request: client-credentials grant with URL-encoded client id/secret.
        assertTrue(tokenForm[0].contains("grant_type=client_credentials"), tokenForm[0]);
        assertTrue(tokenForm[0].contains("client_id=treasury-api-plugin"), tokenForm[0]);
        // secret has a space, slash and plus — must be percent-encoded in the form body.
        assertTrue(tokenForm[0].contains("client_secret=s3cr3t+value%2Fwith%2Bchars"), tokenForm[0]);

        // Admin URL targets the exact subject.
        assertTrue(requestUris.contains("GET /admin/realms/" + realm + "/users/the-sub"), requestUris.toString());
        assertTrue(requestUris.contains("PUT /admin/realms/" + realm + "/users/the-sub"), requestUris.toString());

        // The PUT body is a MERGE: the pre-existing attribute is preserved and the
        // minecraft attributes are added as single-element arrays (Keycloak's shape).
        JsonObject sent = JsonParser.parseString(putBody[0]).getAsJsonObject();
        JsonObject attrs = sent.getAsJsonObject("attributes");
        assertEquals("keep", attrs.getAsJsonArray("existing").get(0).getAsString());
        assertEquals(uuid.toString(), attrs.getAsJsonArray("minecraft_uuid").get(0).getAsString());
        assertEquals("Alice", attrs.getAsJsonArray("minecraft_name").get(0).getAsString());
    }

    @Test
    void setMinecraftAttributes_nonTwoHundredOnUserGet_throws() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/realms/minecraft/protocol/openid-connect/token",
                ex -> respond(ex, 200, "{\"access_token\":\"tok\"}"));
        server.createContext("/admin/realms/minecraft/users/",
                ex -> respond(ex, 404, "{}")); // user not found
        server.start();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        KeycloakAdminClientImpl client = new KeycloakAdminClientImpl(config(baseUrl, true));

        // A failed GET must surface as an exception (the caller then reports a partial
        // link), not silently proceed to a PUT that would 404 too.
        assertThrows(Exception.class,
                () -> client.setMinecraftAttributes("missing-sub", UUID.randomUUID(), "Bob"));
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0) {
            ex.sendResponseHeaders(status, -1);
        } else {
            ex.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }
        ex.close();
    }
}
