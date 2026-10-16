package io.paradaux.treasuryrestapi.controller;

import io.paradaux.treasuryrestapi.testsupport.EmbeddedDbIT;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contract snapshot of the published OpenAPI document. springdoc builds the spec at
 * runtime from the controllers, so a renamed DTO field, a dropped endpoint or a new
 * required parameter silently changes what API consumers (the explorer, players'
 * scripts) code against. The normalised spec is committed at
 * {@code treasury-rest-api/openapi/openapi.json}; any drift fails here, and CI diffs
 * the committed file between base and PR for breaking changes.
 *
 * <p><b>Updating after an intended API change:</b>
 * <pre>
 *   ./gradlew :treasury-rest-api:test --tests '*OpenApiSnapshotIT' -Dopenapi.update=true
 * </pre>
 * (or {@code OPENAPI_UPDATE=true} in the environment) rewrites the committed file; review
 * and commit the diff. Without it, a mismatch fails and leaves the freshly generated spec
 * at {@code build/openapi/openapi.json} to inspect or copy.
 *
 * <p>Normalisation: object keys sorted recursively (springdoc's map ordering isn't a
 * contract), {@code servers} dropped (derived from the request host / X-Forwarded-*),
 * LF line endings, trailing newline. Array order is kept — it is meaningful (parameter
 * order, enum order) and deterministic.
 */
class OpenApiSnapshotIT extends EmbeddedDbIT {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    @Test
    void publishedSpecMatchesCommittedSnapshot() throws Exception {
        String actual = normalise(fetchSpec());

        Path snapshot = Path.of(System.getProperty("openapi.snapshot", "openapi/openapi.json"));
        Path generated = Path.of(System.getProperty("openapi.actual", "build/openapi/openapi.json"));
        Files.createDirectories(generated.toAbsolutePath().getParent());
        Files.writeString(generated, actual, StandardCharsets.UTF_8);

        if (updateRequested()) {
            Files.createDirectories(snapshot.toAbsolutePath().getParent());
            Files.writeString(snapshot, actual, StandardCharsets.UTF_8);
            return;
        }

        if (!Files.exists(snapshot)) {
            fail("No committed OpenAPI snapshot at " + snapshot.toAbsolutePath() + ". Generate it with "
                    + "-Dopenapi.update=true (see OpenApiSnapshotIT) and commit it.");
        }
        String expected = Files.readString(snapshot, StandardCharsets.UTF_8).replace("\r\n", "\n");
        if (!expected.equals(actual)) {
            fail("""
                    The published OpenAPI spec no longer matches the committed snapshot %s.
                    If the API change is intended, regenerate and commit it:
                      ./gradlew :treasury-rest-api:test --tests '*OpenApiSnapshotIT' -Dopenapi.update=true
                    (or copy %s over it). Removed/renamed fields, endpoints or enum values break API
                    consumers — CI's oasdiff check will flag them.
                    First differences (- committed, + generated):
                    %s""".formatted(snapshot, generated.toAbsolutePath(), firstDifferences(expected, actual, 20)));
        }
    }

    /**
     * Invariant the snapshot alone can't guard (a snapshot can be regenerated): the
     * SERVICE-only admin surface is {@code @Hidden} and must never be published, while the
     * public surface still is. Supersedes AdminFirmDocsHiddenIT, which checked only the
     * firm/account/transfer admin paths.
     */
    @Test
    void adminSurfaceIsNotDocumented() throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> paths = (Map<String, Object>) JSON.readValue(fetchSpec(), Map.class).get("paths");

        assertThat(paths.keySet()).noneMatch(p -> p.startsWith("/api/v1/admin"));
        assertThat(paths).containsKeys("/api/v1/transfers", "/api/v1/auth/me", "/api/v1/chestshop/shops");
    }

    private String fetchSpec() throws Exception {
        return mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static boolean updateRequested() {
        return Boolean.parseBoolean(System.getProperty("openapi.update"))
                || Boolean.parseBoolean(System.getenv("OPENAPI_UPDATE"));
    }

    static String normalise(String json) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> spec = JSON.readValue(json, Map.class);
        spec.remove("servers");
        return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(sortKeys(spec))
                .replace("\r\n", "\n") + "\n";
    }

    private static Object sortKeys(Object node) {
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((k, v) -> sorted.put(String.valueOf(k), sortKeys(v)));
            return sorted;
        }
        if (node instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            list.forEach(v -> out.add(sortKeys(v)));
            return out;
        }
        return node;
    }

    private static String firstDifferences(String expected, String actual, int max) {
        String[] e = expected.split("\n", -1);
        String[] a = actual.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (int i = 0; i < Math.max(e.length, a.length) && shown < max; i++) {
            String el = i < e.length ? e[i] : null;
            String al = i < a.length ? a[i] : null;
            if (el != null && el.equals(al)) continue;
            sb.append("  line ").append(i + 1).append(":\n");
            if (el != null) sb.append("  - ").append(el).append('\n');
            if (al != null) sb.append("  + ").append(al).append('\n');
            shown++;
        }
        return sb.toString();
    }
}
