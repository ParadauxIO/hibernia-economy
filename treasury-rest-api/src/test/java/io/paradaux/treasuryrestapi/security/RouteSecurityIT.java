package io.paradaux.treasuryrestapi.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import io.paradaux.treasuryrestapi.testsupport.EmbeddedDbIT;
import io.paradaux.treasuryrestapi.testsupport.TestTokens;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;

/**
 * Route-table security test: every endpoint the running app exposes must be either on
 * the reviewed {@link Access#PUBLIC} list below or locked behind a valid API key.
 *
 * <p>Routes are enumerated from every {@link RequestMappingInfoHandlerMapping} in the
 * context (our controllers, springdoc, Boot's {@code /error}, actuator) — each pattern
 * × HTTP method, with path variables filled with type-appropriate values. Each one
 * must appear in {@link #ROUTES} and is then exercised over MockMvc:
 *
 * <ul>
 *   <li>{@code PUBLIC} — no token is not rejected (not 401/403).</li>
 *   <li>everything else — 401 with no token, a malformed token, an expired token and a
 *       wrongly-signed token; and with a valid key of the right type it gets past
 *       authentication (not 401).</li>
 *   <li>{@code SERVICE} / {@code BUSINESS} — additionally 403 {@code FORBIDDEN} for a
 *       valid key of every other type.</li>
 * </ul>
 *
 * <p>A new endpoint that isn't in {@link #ROUTES} fails {@link #everyRouteIsClassified()},
 * so opening anything to the public (or adding a privileged route) is always a
 * reviewed change to this file. Stale entries fail it too.
 */
class RouteSecurityIT extends EmbeddedDbIT {

    enum Access {
        /** Reachable without a token. Every entry carries the reason it's public. */
        PUBLIC,
        /** Any valid API key (PERSONAL / BUSINESS / GOVERNMENT / SERVICE). */
        AUTHENTICATED,
        /** BUSINESS keys only (firm self-service under /firms/me). */
        BUSINESS,
        /** SERVICE keys only (the hidden /admin surface, PAR-210/PAR-217). */
        SERVICE
    }

    /** {@code body} is a minimal valid JSON body so scope checks aren't pre-empted by a 400. */
    record Rule(Access access, String reason, String body) {}

    private static Rule pub(String reason) {
        return new Rule(Access.PUBLIC, reason, null);
    }

    private static Rule auth() {
        return new Rule(Access.AUTHENTICATED, null, null);
    }

    private static Rule business(String body) {
        return new Rule(Access.BUSINESS, null, body);
    }

    private static Rule service() {
        return new Rule(Access.SERVICE, null, null);
    }

    /**
     * The reviewed route table, keyed {@code "METHOD /pattern"} ({@code ANY} = mapping
     * declares no method). Adding a PUBLIC entry is a security decision: give the reason,
     * and keep SecurityConfig's permitAll list and JwtAuthFilter.shouldNotFilter in step.
     */
    private static final Map<String, Rule> ROUTES = Map.ofEntries(
            // ── public ──────────────────────────────────────────────────────────────
            Map.entry(
                    "GET /api/v1/config/tax", pub("Published tax rates every player can already see in-game (PAR-80)")),
            Map.entry("GET /v3/api-docs", pub("The public OpenAPI contract (admin controllers are @Hidden)")),
            Map.entry("GET /v3/api-docs/swagger-config", pub("Swagger UI bootstrap config for the public docs")),
            Map.entry("GET /swagger-ui.html", pub("Redirect into the public Swagger UI")),
            Map.entry("GET /actuator", pub("Actuator discovery page (lists only the exposed health/info)")),
            Map.entry(
                    "GET /actuator/health", pub("Kubernetes liveness/readiness probes; details only when_authorized")),
            Map.entry("GET /actuator/health/**", pub("Kubernetes liveness/readiness probe groups")),

            // ── any valid key ───────────────────────────────────────────────────────
            Map.entry("GET /api/v1/chestshop/shops", auth()),
            Map.entry("GET /api/v1/chestshop/items", auth()),
            Map.entry("GET /api/v1/chestshop/items/{itemKey}", auth()),
            Map.entry("GET /api/v1/chestshop/stats", auth()),
            // Not in JwtAuthFilter's skip list, so it has always needed a token (unlike
            // the JSON form). Pinned here; making it public would be a reviewed change.
            Map.entry("GET /v3/api-docs.yaml", auth()),
            // Direct requests to Boot's error controller; container ERROR dispatches are
            // permitted separately (see SecurityConfig and ErrorDispatchSecurityIT).
            Map.entry("ANY /error", auth()),
            Map.entry("GET /api/v1/auth/me", auth()),
            Map.entry("POST /api/v1/auth/rotate", auth()),
            Map.entry("GET /api/v1/accounts/{accountId}/balance", auth()),
            Map.entry("GET /api/v1/accounts/by-player", auth()),
            Map.entry("GET /api/v1/accounts/baltop", auth()),
            Map.entry("GET /api/v1/accounts/{accountId}/transactions", auth()),
            Map.entry("GET /api/v1/accounts/{accountId}/transactions/feed", auth()),
            Map.entry("GET /api/v1/firms/{firmName}", auth()),
            Map.entry("GET /api/v1/firms/{firmName}/accounts", auth()),
            Map.entry("GET /api/v1/firms/by-id/{firmId}/accounts", auth()),
            Map.entry("GET /api/v1/accounts/{accountId}/firm", auth()),
            Map.entry("GET /api/v1/firms/{firmName}/balance", auth()),
            Map.entry("POST /api/v1/transfers", auth()),
            Map.entry("POST /api/v1/transfers/to-firm", auth()),
            Map.entry("POST /api/v1/transfers/to-player", auth()),
            Map.entry("POST /api/v1/webhooks", auth()),
            Map.entry("GET /api/v1/webhooks", auth()),
            Map.entry("GET /api/v1/webhooks/{id}", auth()),
            Map.entry("PATCH /api/v1/webhooks/{id}", auth()),
            Map.entry("DELETE /api/v1/webhooks/{id}", auth()),

            // ── BUSINESS keys only ──────────────────────────────────────────────────
            Map.entry("GET /api/v1/firms/me", business(null)),
            Map.entry("PATCH /api/v1/firms/me", business("{}")),
            Map.entry("GET /api/v1/firms/me/accounts", business(null)),
            Map.entry("GET /api/v1/firms/me/employees", business(null)),
            Map.entry("GET /api/v1/firms/me/roles", business(null)),
            Map.entry(
                    "PATCH /api/v1/firms/me/accounts/{accountId}/display-name", business("{\"displayName\":\"Till\"}")),

            // ── SERVICE keys only ───────────────────────────────────────────────────
            Map.entry("PATCH /api/v1/admin/accounts/{id}/display-name", service()),
            Map.entry("PATCH /api/v1/admin/accounts/{id}/owner", service()),
            Map.entry("POST /api/v1/admin/accounts/{id}/archive", service()),
            Map.entry("POST /api/v1/admin/accounts/{id}/unarchive", service()),
            Map.entry("POST /api/v1/admin/api-keys/{keyId}/revoke", service()),
            Map.entry("POST /api/v1/admin/api-keys/{keyId}/rotate", service()),
            Map.entry("POST /api/v1/admin/firms/{firmId}/disband", service()),
            Map.entry("POST /api/v1/admin/firms/{firmId}/rename", service()),
            Map.entry("PATCH /api/v1/admin/firms/{firmId}", service()),
            Map.entry("PUT /api/v1/admin/rate-limit-overrides/{ownerUuid}", service()),
            Map.entry("DELETE /api/v1/admin/rate-limit-overrides/{ownerUuid}", service()),
            Map.entry("POST /api/v1/admin/transfers", service()),
            Map.entry("POST /api/v1/admin/webhooks", service()),
            Map.entry("PATCH /api/v1/admin/webhooks/{id}/active", service()),
            Map.entry("PATCH /api/v1/admin/webhooks/{id}/url", service()),
            Map.entry("PATCH /api/v1/admin/webhooks/{id}/secret", service()),
            Map.entry("DELETE /api/v1/admin/webhooks/{id}", service()));

    private static final Set<String> KEY_TYPES = Set.of("PERSONAL", "BUSINESS", "GOVERNMENT", "SERVICE");

    @Autowired
    private ApplicationContext context;

    @Autowired
    private SecretKey signingKey;

    private TestTokens tokens;

    /** One concrete, requestable instance of a mapped route. */
    record Route(String key, HttpMethod method, String path) {}

    @BeforeEach
    void seedKeys() {
        tokens = new TestTokens(signingKey, this::insertApiKey);
        tokens.seedAll();
    }

    @Test
    void everyRouteIsClassified() {
        Set<String> live = new TreeSet<>();
        discoverRoutes().forEach(r -> live.add(r.key()));

        Set<String> unclassified = new TreeSet<>(live);
        unclassified.removeAll(ROUTES.keySet());
        Set<String> stale = new TreeSet<>(ROUTES.keySet());
        stale.removeAll(live);

        assertThat(unclassified)
                .as("Endpoints missing from RouteSecurityIT.ROUTES — classify each as PUBLIC (with a "
                        + "reason, and allowlist it in SecurityConfig + JwtAuthFilter), AUTHENTICATED, "
                        + "BUSINESS or SERVICE")
                .isEmpty();
        assertThat(stale)
                .as("RouteSecurityIT.ROUTES entries that no longer match any endpoint")
                .isEmpty();
        // Sanity: discovery actually found the API (guards against an empty enumeration).
        assertThat(live).contains("POST /api/v1/transfers", "POST /api/v1/admin/transfers");
    }

    @Test
    void publicRoutesNeedNoToken() throws Exception {
        List<String> failures = new ArrayList<>();
        for (Route r : routes(Access.PUBLIC)) {
            int status = send(r, null, null).getStatus();
            if (status == 401 || status == 403) failures.add(r + " -> " + status + " without a token");
        }
        assertThat(failures).isEmpty();
    }

    @Test
    void protectedRoutesRejectMissingAndBadTokens() throws Exception {
        Map<String, String> badTokens = new TreeMap<>(Map.of(
                "no token", "",
                "malformed token", "Bearer not.a.jwt",
                "expired token", "Bearer " + tokens.expiredPersonal(),
                "wrong-key signature", "Bearer " + tokens.personalSignedWithWrongKey(),
                "alg:none token", "Bearer " + tokens.personalUnsigned()));
        List<String> failures = new ArrayList<>();
        for (Route r : protectedRoutes()) {
            for (var bad : badTokens.entrySet()) {
                String header = bad.getValue().isEmpty() ? null : bad.getValue();
                MockHttpServletResponse res =
                        send(r, header, ROUTES.get(r.key()).body());
                if (res.getStatus() != 401) {
                    failures.add(
                            r + " with " + bad.getKey() + " -> " + res.getStatus() + " " + res.getContentAsString());
                }
            }
        }
        assertThat(failures).isEmpty();
    }

    @Test
    void scopedRoutesForbidOtherKeyTypes() throws Exception {
        List<String> failures = new ArrayList<>();
        for (Route r : protectedRoutes()) {
            Rule rule = ROUTES.get(r.key());
            if (rule.access() == Access.AUTHENTICATED) continue;
            String allowed = rule.access().name();
            for (String keyType : KEY_TYPES) {
                if (keyType.equals(allowed)) continue;
                MockHttpServletResponse res = send(r, bearer(keyType), rule.body());
                if (res.getStatus() != 403 || !res.getContentAsString().contains("\"FORBIDDEN\"")) {
                    failures.add(
                            r + " with a " + keyType + " key -> " + res.getStatus() + " " + res.getContentAsString());
                }
            }
        }
        assertThat(failures).isEmpty();
    }

    /**
     * Positive control: the right key type gets past the security layer. (The handler may
     * still answer 400/404 for the placeholder ids/bodies — only auth outcomes matter.)
     */
    @Test
    void protectedRoutesAdmitTheRightKeyType() throws Exception {
        List<String> failures = new ArrayList<>();
        for (Route r : protectedRoutes()) {
            Rule rule = ROUTES.get(r.key());
            String keyType = rule.access() == Access.AUTHENTICATED
                    ? "PERSONAL"
                    : rule.access().name();
            // Some of these succeed and mutate keys (POST /auth/rotate supersedes the jti,
            // admin revoke/rotate), so start each request from freshly seeded keys.
            exec("DELETE FROM api_keys", ps -> {});
            tokens.seedAll();
            MockHttpServletResponse res = send(r, bearer(keyType), rule.body());
            int status = res.getStatus();
            // Key-type refusals all read "This endpoint requires a <TYPE> API key."; other
            // 403s here are ownership (ReBAC) checks on the placeholder ids, which is fine.
            boolean scopeDenied = status == 403 && res.getContentAsString().contains("This endpoint requires");
            if (status == 401 || scopeDenied) {
                failures.add(r + " with a " + keyType + " key -> " + status + " " + res.getContentAsString());
            }
        }
        assertThat(failures).isEmpty();
    }

    @Test
    void chestShopAcceptsEveryKeyTypeAndUsesItsRateBudget() throws Exception {
        for (Route route : discoverRoutes()) {
            if (!route.path().startsWith("/api/v1/chestshop/")) continue;
            for (String keyType : KEY_TYPES) {
                MockHttpServletResponse response = send(route, bearer(keyType), null);
                // The unseeded item detail is a genuine 404; directories and totals are 200.
                int expected = route.key().contains("{itemKey}") ? 404 : 200;
                assertThat(response.getStatus())
                        .as("%s with %s", route, keyType)
                        .isEqualTo(expected);
                assertThat(response.getHeader("X-RateLimit-Limit"))
                        .as("%s with %s rate budget", route, keyType)
                        .isEqualTo(keyType.equals("BUSINESS") ? "600" : "120");
            }
        }
    }

    @Test
    void chestShopRejectsRevokedAndSupersededKeys() throws Exception {
        String token = bearer("PERSONAL");
        exec("UPDATE api_keys SET revoked = 1 WHERE key_id = ?", ps -> ps.setInt(1, TestTokens.PERSONAL_KEY));
        assertChestShopTokenRejected(token, "TOKEN_REVOKED");
        exec(
                "UPDATE api_keys SET revoked = 0, jwt_id = 'superseded' WHERE key_id = ?",
                ps -> ps.setInt(1, TestTokens.PERSONAL_KEY));
        assertChestShopTokenRejected(token, "TOKEN_SUPERSEDED");
    }

    private void assertChestShopTokenRejected(String token, String error) throws Exception {
        for (Route route : discoverRoutes()) {
            if (!route.path().startsWith("/api/v1/chestshop/")) continue;
            MockHttpServletResponse response = send(route, token, null);
            assertThat(response.getStatus()).as("%s with %s", route, error).isEqualTo(401);
            assertThat(response.getContentAsString()).contains("\"error\":\"" + error + "\"");
        }
    }

    // ── route discovery ─────────────────────────────────────────────────────────

    private List<Route> routes(Access access) {
        return discoverRoutes().stream()
                .filter(r -> ROUTES.containsKey(r.key()) && ROUTES.get(r.key()).access() == access)
                .toList();
    }

    private List<Route> protectedRoutes() {
        return discoverRoutes().stream()
                .filter(r -> ROUTES.containsKey(r.key()) && ROUTES.get(r.key()).access() != Access.PUBLIC)
                .toList();
    }

    private List<Route> discoverRoutes() {
        List<Route> routes = new ArrayList<>();
        for (RequestMappingInfoHandlerMapping mapping :
                context.getBeansOfType(RequestMappingInfoHandlerMapping.class).values()) {
            for (Map.Entry<RequestMappingInfo, HandlerMethod> e :
                    mapping.getHandlerMethods().entrySet()) {
                RequestMappingInfo info = e.getKey();
                Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
                for (String pattern : info.getPatternValues()) {
                    String path = concretePath(pattern, e.getValue());
                    if (methods.isEmpty()) {
                        routes.add(new Route("ANY " + pattern, HttpMethod.GET, path));
                    } else {
                        for (RequestMethod m : methods) {
                            routes.add(new Route(m.name() + " " + pattern, HttpMethod.valueOf(m.name()), path));
                        }
                    }
                }
            }
        }
        return routes.stream().distinct().toList();
    }

    private static final Pattern PATH_VAR = Pattern.compile("\\{\\*?([^}:]+)(?::[^}]*)?}");

    /** Fill {@code {var}} from the handler's {@code @PathVariable} type; {@code **} with one segment. */
    private static String concretePath(String pattern, HandlerMethod handler) {
        Matcher m = PATH_VAR.matcher(pattern);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(sampleValue(m.group(1), handler)));
        }
        m.appendTail(sb);
        return sb.toString().replace("**", "x");
    }

    private static String sampleValue(String varName, HandlerMethod handler) {
        for (MethodParameter p : handler.getMethodParameters()) {
            PathVariable pv = p.getParameterAnnotation(PathVariable.class);
            if (pv == null) continue;
            String name = !pv.name().isEmpty() ? pv.name() : !pv.value().isEmpty() ? pv.value() : p.getParameterName();
            if (!varName.equals(name)) continue;
            Class<?> type = p.getParameterType();
            if (type == UUID.class) return "7e570000-0000-0000-0000-0000000000ff";
            if (Number.class.isAssignableFrom(type) || (type.isPrimitive() && type != boolean.class)) return "1";
            return "x";
        }
        return "1";
    }

    // ── requests ────────────────────────────────────────────────────────────────

    private String bearer(String keyType) {
        return "Bearer "
                + switch (keyType) {
                    case "PERSONAL" -> tokens.personal();
                    case "BUSINESS" -> tokens.business();
                    case "GOVERNMENT" -> tokens.government();
                    case "SERVICE" -> tokens.service();
                    default -> throw new IllegalArgumentException(keyType);
                };
    }

    private MockHttpServletResponse send(Route r, String authorization, String body) throws Exception {
        MockHttpServletRequestBuilder req = request(r.method(), r.path());
        if (authorization != null) req.header("Authorization", authorization);
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(req).andReturn().getResponse();
    }
}
