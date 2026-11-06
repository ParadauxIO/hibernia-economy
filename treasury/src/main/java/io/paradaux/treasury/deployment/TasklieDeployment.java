package io.paradaux.treasury.deployment;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;

/** Reports activation through Tasklie's workspace-key custom pipeline API. */
final class TasklieDeployment {
    static final URI ENDPOINT = URI.create("https://app.taskl.ie/api/public/w/paradaux/deployments/completions");
    private static final Pattern RELEASE = Pattern.compile("\\d{4}\\.\\d{1,2}\\.\\d+\\.\\d+-g([0-9a-f]{12})");

    enum Result {
        ACCEPTED,
        RETRY,
        REJECTED
    }

    private final HttpClient client;
    private final HttpRequest request;

    TasklieDeployment(
            HttpClient client,
            URI endpoint,
            String token,
            String environment,
            String app,
            String version,
            Instant startedAt) {
        var release = RELEASE.matcher(version);
        if (!release.matches()) {
            throw new IllegalArgumentException("Tasklie reporting requires a production release version");
        }
        if (token.isBlank() || token.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("Tasklie deployment token is missing or invalid");
        }
        if (!environment.matches("[a-z0-9][a-z0-9-]{0,39}") || app.isBlank() || app.length() > 100) {
            throw new IllegalArgumentException("Tasklie environment or app is invalid");
        }
        JsonObject body = new JsonObject();
        body.addProperty("repo", "ParadauxIO/hibernia-economy");
        body.addProperty("sha", release.group(1));
        body.addProperty("environment", environment);
        body.addProperty("eventId", app + ":" + startedAt);
        body.addProperty("completedAt", startedAt.toString());
        body.addProperty("source", "custom");
        this.client = client;
        this.request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
    }

    // Reuse the exact request (including completedAt) so an ambiguous response can safely be retried.
    Result send() {
        try {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200 && acknowledged(response.body())) {
                return Result.ACCEPTED;
            }
            return response.statusCode() == 429 || response.statusCode() >= 500 ? Result.RETRY : Result.REJECTED;
        } catch (IOException failure) {
            return Result.RETRY;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Result.REJECTED;
        }
    }

    private static boolean acknowledged(String body) {
        try {
            var json = JsonParser.parseString(body);
            if (!json.isJsonObject()) return false;
            var status = json.getAsJsonObject().get("status");
            if (status == null
                    || !status.isJsonPrimitive()
                    || !status.getAsJsonPrimitive().isString()) return false;
            return status.getAsString().equals("ok") || status.getAsString().equals("duplicate");
        } catch (JsonParseException malformed) {
            return false;
        }
    }
}
