package io.paradaux.treasury.deployment;

import static org.assertj.core.api.Assertions.*;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TasklieDeploymentTest {
    private static final String VERSION = "2026.10.18.1-g41b9bbf9f528";

    @Test
    void sendsRunningCommitAndStableIdentityAcrossRetryWithTokenOnlyInHeader() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> bodies = new ArrayList<>();
        List<String> auth = new ArrayList<>();
        server.createContext("/deploy", exchange -> {
            auth.add(exchange.getRequestHeaders().getFirst("Authorization"));
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            byte[] response = (bodies.size() == 1 ? "unavailable" : "duplicate")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(bodies.size() == 1 ? 503 : 200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try (var client = HttpClient.newHttpClient()) {
            var deployment = new TasklieDeployment(
                    client,
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/deploy"),
                    "tdp_test-secret",
                    "democracycraft-plugins",
                    "plugins \"primary\"",
                    VERSION,
                    Instant.parse("2026-10-29T08:09:00Z"));
            assertThat(deployment.send()).isEqualTo(TasklieDeployment.Result.RETRY);
            assertThat(deployment.send()).isEqualTo(TasklieDeployment.Result.ACCEPTED);
            assertThat(auth).containsExactly("Bearer tdp_test-secret", "Bearer tdp_test-secret");
            assertThat(bodies).hasSize(2);
            assertThat(bodies.get(1)).isEqualTo(bodies.getFirst()).doesNotContain("tdp_test-secret");
            var body = JsonParser.parseString(bodies.getFirst()).getAsJsonObject();
            assertThat(body.get("revision").getAsString()).isEqualTo("41b9bbf9f528");
            assertThat(body.get("repo").getAsString()).isEqualTo("ParadauxIO/hibernia-economy");
            assertThat(body.get("environment").getAsString()).isEqualTo("democracycraft-plugins");
            assertThat(body.get("app").getAsString()).isEqualTo("plugins \"primary\"");
            assertThat(body.get("finishedAt").getAsString()).isEqualTo("2026-10-29T08:09:00Z");
            assertThat(body.get("status").getAsString()).isEqualTo("Succeeded");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void acceptsOnlyAcknowledgementsAndNeverFollowsRedirects() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int[] status = {200};
        String[] body = {"ok"};
        server.createContext("/", exchange -> {
            byte[] bytes = body[0].getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Location", "/must-not-follow");
            exchange.sendResponseHeaders(status[0], bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try (var client = HttpClient.newHttpClient()) {
            var deployment = new TasklieDeployment(
                    client,
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    "tdp_test",
                    "democracycraft-plugins",
                    "plugins",
                    VERSION,
                    Instant.EPOCH);
            assertThat(deployment.send()).isEqualTo(TasklieDeployment.Result.ACCEPTED);
            body[0] = "ignored";
            assertThat(deployment.send()).isEqualTo(TasklieDeployment.Result.REJECTED);
            for (int rejected : new int[] {400, 404, 302}) {
                status[0] = rejected;
                assertThat(deployment.send()).isEqualTo(TasklieDeployment.Result.REJECTED);
            }
            status[0] = 429;
            assertThat(deployment.send()).isEqualTo(TasklieDeployment.Result.RETRY);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void skipsSnapshotsDevelopmentBuildsAndInvalidConfiguration() {
        try (var client = HttpClient.newHttpClient()) {
            for (String version : List.of("2.3.0-SNAPSHOT", "2026.10.18.1-dev-g41b9bbf9f528", "unknown")) {
                assertThatIllegalArgumentException()
                        .isThrownBy(() -> new TasklieDeployment(
                                client,
                                TasklieDeployment.ENDPOINT,
                                "tdp_test",
                                "plugins",
                                "plugins",
                                version,
                                Instant.EPOCH));
            }
            for (String token : List.of("", "secret\nheader")) {
                assertThatIllegalArgumentException()
                        .isThrownBy(() -> new TasklieDeployment(
                                client,
                                TasklieDeployment.ENDPOINT,
                                token,
                                "plugins",
                                "plugins",
                                VERSION,
                                Instant.EPOCH));
            }
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new TasklieDeployment(
                            client, TasklieDeployment.ENDPOINT, "tdp_test", "", "plugins", VERSION, Instant.EPOCH));
        }
    }
}
