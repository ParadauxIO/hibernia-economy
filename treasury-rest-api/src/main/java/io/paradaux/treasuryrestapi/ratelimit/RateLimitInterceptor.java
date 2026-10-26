package io.paradaux.treasuryrestapi.ratelimit;

import tools.jackson.databind.ObjectMapper;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.micrometer.core.instrument.MeterRegistry;
import io.paradaux.treasuryrestapi.dto.ErrorResponse;
import io.paradaux.treasuryrestapi.security.VerifiedToken;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * Per-ISSUER rate limiting. Reads {@link RateLimit} off the matched controller
 * method, picks the base limit for the principal's key type, scales it by the
 * issuer's override multiplier, and consumes one token from a Bucket4j bucket
 * keyed by the issuer (the human who created the key — {@code ownerUuid}), not
 * the individual key. All of a person's keys therefore share one budget per
 * endpoint.
 *
 * <p>The effective limit is part of the bucket key, so an admin raising an
 * issuer's multiplier takes effect immediately (a fresh bucket at the new
 * capacity) rather than waiting for the old bucket's TTL.
 *
 * <p>If {@link RateLimit#anonymousPerMinute()} is {@code > 0} on the endpoint
 * and the request arrives without a verified principal, the bucket is keyed by
 * the client IP instead. This caps scraping on intentionally-public endpoints
 * without breaking legitimate anonymous use.
 *
 * <p>Every throttled and allowed request is counted into Micrometer
 * ({@code treasury_api_key_requests_total}) for the admin dashboard / Grafana.
 *
 * <p>Backend-error behaviour is per-endpoint (see {@link RateLimit#failClosed()}):
 * public reads <em>fail open</em> (a Redis blip can't take the API down), while
 * money-mutating endpoints <em>fail closed</em> with {@code 503} so stressing the
 * limiter backend can't strip the throttle off the transfer path.
 *
 * <p>Endpoints without {@code @RateLimit}, or anonymous traffic on an endpoint
 * whose {@code anonymousPerMinute} is left at the default {@code 0}, are not
 * throttled.
 */
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);
    private static final String METRIC = "treasury_api_key_requests_total";

    private final BucketProvider bucketProvider;
    private final ObjectMapper objectMapper;
    private final RateLimitOverrideService overrides;
    private final MeterRegistry metrics;

    public RateLimitInterceptor(BucketProvider bucketProvider, ObjectMapper objectMapper,
                                RateLimitOverrideService overrides, MeterRegistry metrics) {
        this.bucketProvider = bucketProvider;
        this.objectMapper = objectMapper;
        this.overrides = overrides;
        this.metrics = metrics;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!(handler instanceof HandlerMethod hm)) return true;
        RateLimit ann = hm.getMethodAnnotation(RateLimit.class);
        if (ann == null) return true;

        String routeKey = hm.getBeanType().getSimpleName() + "#" + hm.getMethod().getName();

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        VerifiedToken token = (auth != null && auth.getPrincipal() instanceof VerifiedToken vt) ? vt : null;

        if (token == null) {
            if (ann.anonymousPerMinute() <= 0) return true;
            return throttleAnonymous(request, response, ann, routeKey);
        }
        return throttleAuthenticated(response, ann, routeKey, token);
    }

    private boolean throttleAuthenticated(HttpServletResponse response, RateLimit ann,
                                          String routeKey, VerifiedToken token) throws Exception {
        boolean failClosed = ann.failClosed();
        UUID issuer = token.ownerUuid();
        String issuerKey = issuer != null ? issuer.toString() : ("key:" + token.keyId());

        int base = "BUSINESS".equalsIgnoreCase(token.keyType())
                ? ann.businessPerMinute()
                : ann.personalPerMinute();
        BigDecimal multiplier = overrides.getMultiplier(issuer);
        int limit = Math.max(1, BigDecimal.valueOf(base).multiply(multiplier)
                .setScale(0, RoundingMode.HALF_UP).intValue());

        // Limit is part of the key so a multiplier change re-buckets immediately.
        String bucketKey = issuerKey + ":" + routeKey + ":" + limit;
        return consume(response, routeKey, bucketKey, limit, token, issuerKey, failClosed);
    }

    private boolean throttleAnonymous(HttpServletRequest request, HttpServletResponse response,
                                      RateLimit ann, String routeKey) throws Exception {
        String ip = clientIp(request);
        int limit = Math.max(1, ann.anonymousPerMinute());
        String issuerKey = "anon:" + ip;
        // Limit is part of the key for the same reason as the authenticated path.
        String bucketKey = issuerKey + ":" + routeKey + ":" + limit;
        return consume(response, routeKey, bucketKey, limit, null, issuerKey, ann.failClosed());
    }

    /**
     * Try to consume one token from the bucket and write the response side-effects
     * (headers on success, 429 + ErrorResponse on throttle). {@code token} is null
     * when throttling an anonymous request; the metric label set differs accordingly.
     */
    private boolean consume(HttpServletResponse response, String routeKey,
                            String bucketKey, int limit,
                            VerifiedToken token, String issuerKey, boolean failClosed) throws Exception {
        ConsumptionProbe probe;
        try {
            Bucket bucket = bucketProvider.bucketFor(bucketKey, limit);
            probe = bucket.tryConsumeAndReturnRemaining(1);
        } catch (RuntimeException e) {
            if (failClosed) {
                // A money-mutating endpoint whose limit can't be checked is
                // rejected, not waved through — stressing Redis must not strip
                // the throttle off the transfer path.
                log.warn("Rate-limit backend error on {} — failing CLOSED (request rejected): {}",
                        routeKey, e.toString());
                count(token, "backend_error");
                writeBackendUnavailable(response);
                return false;
            }
            // Fail open: a Redis/backend error must not take down public reads.
            log.warn("Rate-limit backend error on {} — failing open (request allowed): {}",
                    routeKey, e.toString());
            count(token, "allowed");
            return true;
        }

        if (probe.isConsumed()) {
            response.setHeader("X-RateLimit-Limit", Integer.toString(limit));
            response.setHeader("X-RateLimit-Remaining", Long.toString(probe.getRemainingTokens()));
            count(token, "allowed");
            return true;
        }

        long retryAfterSeconds = Math.max(1L, probe.getNanosToWaitForRefill() / 1_000_000_000L);
        log.warn("Rate limit exceeded: issuer={} route={} keyType={} limit={}/min retryAfter={}s",
                issuerKey, routeKey,
                token != null ? token.keyType() : "ANONYMOUS",
                limit, retryAfterSeconds);
        count(token, "throttled");

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
        response.setHeader("X-RateLimit-Limit", Integer.toString(limit));
        response.setHeader("X-RateLimit-Remaining", "0");
        objectMapper.writeValue(response.getWriter(), new ErrorResponse(
                "RATE_LIMIT_EXCEEDED",
                "Rate limit of " + limit + " requests/minute exceeded for this endpoint. "
                        + "Retry after " + retryAfterSeconds + " second(s)."));
        return false;
    }

    /** 503 written when a fail-closed endpoint can't reach the rate-limit backend. */
    private void writeBackendUnavailable(HttpServletResponse response) throws Exception {
        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Retry-After", "5");
        objectMapper.writeValue(response.getWriter(), new ErrorResponse(
                "RATE_LIMIT_BACKEND_UNAVAILABLE",
                "Rate limiting is temporarily unavailable; this request was rejected as a "
                        + "safety measure. Retry shortly."));
    }

    private void count(VerifiedToken token, String outcome) {
        // Only LOW-cardinality, non-identifying labels go on the metric (ADT-122/123):
        //   - key_id / issuer-UUID are deliberately NOT labels. They grow one series
        //     per API key / per human issuer (high cardinality → heap + scrape cost),
        //     and the Prometheus actuator endpoint is permitAll, so exposing them
        //     would leak which keys/issuers are active and their request volumes to
        //     anyone who can scrape it. Per-issuer/route detail already lives in the
        //     structured throttle log line for debugging.
        //   - the raw client IP is likewise never a label (rotating anon IPs are
        //     unbounded); anonymous traffic collapses to key_type=ANONYMOUS.
        // The per-key/per-IP bucket KEY used for actual throttling is unaffected.
        try {
            metrics.counter(METRIC,
                    "key_type", token != null
                            ? (token.keyType() != null ? token.keyType() : "unknown")
                            : "ANONYMOUS",
                    "outcome", outcome).increment();
        } catch (RuntimeException e) {
            log.debug("metric emit failed: {}", e.toString());
        }
    }

    /**
     * The trusted client IP for anonymous bucketing (ADT-15).
     *
     * <p>Prefers {@code X-Envoy-External-Address}. The cluster's Cilium gateway runs
     * Envoy with {@code use_remote_address: true} (verified in the live
     * {@code cilium-gateway-paradaux} CiliumEnvoyConfig), so Envoy resolves the client
     * from the real downstream TCP connection — hostNetwork, direct client connections,
     * no LB in front — and writes it to this header, <em>overwriting</em> any value a
     * client sends. It is therefore unforgeable.
     *
     * <p>We must <strong>not</strong> trust {@code X-Forwarded-For} / {@code getRemoteAddr()}
     * here: the gateway is configured with the default {@code xff_num_trusted_hops: 0}
     * and {@code skip_xff_append: false}, so it <em>appends</em> the real client to XFF
     * without stripping client-supplied entries — and under {@code
     * forward-headers-strategy: framework} the app reads the leftmost (client-controlled)
     * XFF entry. Keying the anon bucket on that let an attacker rotate XFF for a fresh
     * bucket per request.
     *
     * <p>Falls back to {@code getRemoteAddr()} when the header is absent (local/dev, or
     * in-cluster traffic that didn't traverse the gateway). This fallback is safe because
     * {@code clientIp} keys only the <em>anonymous</em> rate-limit buckets on the public
     * ChestShop read endpoints — no money path and no authenticated request keys on it
     * (those use the issuer key derived from the JWT). The worst case of a forged/absent
     * header is coarser throttling of public reads. It relies on the network policy that
     * the pod is not externally reachable off the Envoy gateway; keep that invariant.
     */
    private static String clientIp(HttpServletRequest request) {
        String envoyClient = request.getHeader("X-Envoy-External-Address");
        if (envoyClient != null && !envoyClient.isBlank()) {
            return envoyClient.trim();
        }
        return request.getRemoteAddr();
    }
}
