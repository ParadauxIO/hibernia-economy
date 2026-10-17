package io.paradaux.treasuryrestapi.ratelimit;

import io.github.bucket4j.Bucket;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.paradaux.treasuryrestapi.mapper.ApiRateLimitOverrideMapper;
import io.paradaux.treasuryrestapi.model.RateLimitOverride;
import io.paradaux.treasuryrestapi.security.VerifiedToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the backend-error behaviour (ADT-16) and client-IP keying
 * (ADT-15) of {@link RateLimitInterceptor}, exercised without a Spring context.
 */
class RateLimitInterceptorTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final RateLimitOverrideService overrides = new RateLimitOverrideService(NOOP_OVERRIDE_MAPPER);

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    /** ADT-16: a money-mutating (failClosed) endpoint rejects with 503 when the bucket backend errors. */
    @Test
    void failClosedEndpointRejectsWith503OnBackendError() throws Exception {
        authenticate();
        RateLimitInterceptor interceptor = new RateLimitInterceptor(THROWING_BACKEND, objectMapper, overrides, metrics);
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(new MockHttpServletRequest(), response, handler("failClosed"));

        assertFalse(allowed, "fail-closed endpoint must reject when the limiter can't be checked");
        assertEquals(503, response.getStatus());
        assertTrue(response.getContentAsString().contains("RATE_LIMIT_BACKEND_UNAVAILABLE"));
    }

    /** ADT-16: a public-read (default) endpoint stays available (fails open) on the same backend error. */
    @Test
    void failOpenEndpointAllowsOnBackendError() throws Exception {
        authenticate();
        RateLimitInterceptor interceptor = new RateLimitInterceptor(THROWING_BACKEND, objectMapper, overrides, metrics);
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(new MockHttpServletRequest(), response, handler("failOpen"));

        assertTrue(allowed, "fail-open endpoint must stay available when the limiter backend errors");
        assertEquals(200, response.getStatus());
    }

    /** ADT-15: the anonymous bucket is keyed off getRemoteAddr(), never a spoofable X-Forwarded-For. */
    @Test
    void anonymousBucketKeyedByRemoteAddrNotForwardedForHeader() throws Exception {
        SecurityContextHolder.clearContext(); // anonymous
        String[] captured = new String[1];
        CaffeineBucketProvider real = new CaffeineBucketProvider();
        BucketProvider capturing = (key, cap) -> {
            captured[0] = key;
            return real.bucketFor(key, cap);
        };
        RateLimitInterceptor interceptor = new RateLimitInterceptor(capturing, objectMapper, overrides, metrics);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.9");
        request.addHeader("X-Forwarded-For", "1.2.3.4");   // attacker-supplied spoof
        boolean allowed = interceptor.preHandle(request, new MockHttpServletResponse(), handler("anon"));

        assertTrue(allowed);
        assertTrue(captured[0].contains("10.0.0.9"), "bucket key must use the socket peer address");
        assertFalse(captured[0].contains("1.2.3.4"), "bucket key must NOT use the spoofable XFF header");
    }

    /** ADT-15: the unforgeable gateway-set X-Envoy-External-Address wins over a spoofed XFF. */
    @Test
    void anonymousBucketKeyedByEnvoyExternalAddress_overSpoofedForwardedFor() throws Exception {
        SecurityContextHolder.clearContext();
        String[] captured = new String[1];
        CaffeineBucketProvider real = new CaffeineBucketProvider();
        BucketProvider capturing = (key, cap) -> {
            captured[0] = key;
            return real.bucketFor(key, cap);
        };
        RateLimitInterceptor interceptor = new RateLimitInterceptor(capturing, objectMapper, overrides, metrics);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.9");
        request.addHeader("X-Forwarded-For", "1.2.3.4");          // attacker spoof
        request.addHeader("X-Envoy-External-Address", "203.0.113.7"); // gateway-resolved, unforgeable
        boolean allowed = interceptor.preHandle(request, new MockHttpServletResponse(), handler("anon"));

        assertTrue(allowed);
        assertTrue(captured[0].contains("203.0.113.7"), "must key on the Envoy-resolved client address");
        assertFalse(captured[0].contains("1.2.3.4"), "must ignore the spoofable XFF header");
    }

    // --- helpers ------------------------------------------------------------

    private static void authenticate() {
        VerifiedToken token = new VerifiedToken(1L, UUID.randomUUID(), "PERSONAL", 10L, null);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(token, null, List.of()));
    }

    private static HandlerMethod handler(String methodName) throws NoSuchMethodException {
        Method m = Endpoints.class.getMethod(methodName);
        return new HandlerMethod(new Endpoints(), m);
    }

    /** Backend that always errors, simulating Redis down / timeout. */
    private static final BucketProvider THROWING_BACKEND = (key, cap) -> {
        throw new RuntimeException("rate-limit backend unavailable (test)");
    };

    /** Controller stand-in carrying the three relevant @RateLimit shapes. */
    @SuppressWarnings("unused")
    static class Endpoints {
        @RateLimit(personalPerMinute = 5, businessPerMinute = 5, failClosed = true)
        public void failClosed() { }

        @RateLimit(personalPerMinute = 5, businessPerMinute = 5)
        public void failOpen() { }

        @RateLimit(personalPerMinute = 5, businessPerMinute = 5, anonymousPerMinute = 10)
        public void anon() { }
    }

    /** Override mapper that reports no overrides (multiplier defaults to 1.00). */
    private static final ApiRateLimitOverrideMapper NOOP_OVERRIDE_MAPPER = new ApiRateLimitOverrideMapper() {
        @Override public BigDecimal findMultiplier(UUID owner) { return null; }
        @Override public List<RateLimitOverride> findAll() { return List.of(); }
        @Override public int upsert(UUID owner, BigDecimal multiplier, String note, UUID updatedBy) { return 0; }
        @Override public int delete(UUID owner) { return 0; }
    };
}
