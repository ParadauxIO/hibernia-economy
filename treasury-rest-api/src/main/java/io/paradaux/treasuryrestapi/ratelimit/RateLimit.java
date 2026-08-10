package io.paradaux.treasuryrestapi.ratelimit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller endpoint as rate-limited. The interceptor at
 * {@link RateLimitInterceptor} reads this annotation off the matched
 * {@code HandlerMethod}, looks up the verified principal in the security
 * context, picks the limit that matches the principal's key type, and
 * applies a Bucket4j bucket keyed by {@code keyId × routeKey}.
 *
 * <p>The two limits are expressed as <em>requests per minute</em>; the bucket
 * refills evenly across the minute (so a clean 60/min = ~1/sec sustained,
 * not "60 fast then idle 59 seconds").
 *
 * <p>Convention: PERSONAL keys are tighter than BUSINESS keys. A virtual
 * bank running on a BUSINESS key will fan out N customers' transfers
 * through one principal, so the BUSINESS limit needs headroom for that
 * fan-in. PERSONAL keys are typically a single human pressing buttons.
 *
 * <p>The {@link RateLimitOperationCustomizer} scrapes this annotation when
 * generating OpenAPI docs, so changing the values here automatically
 * updates the published API documentation.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {
    /** Sustained requests per minute for PERSONAL / GOVERNMENT / SYSTEM keys. */
    int personalPerMinute();

    /** Sustained requests per minute for BUSINESS keys. */
    int businessPerMinute();

    /**
     * Sustained requests per minute for unauthenticated callers, bucketed by
     * client IP. The IP is taken from the trusted gateway's
     * {@code X-Envoy-External-Address} header, falling back to the socket remote
     * address; the client-controlled {@code X-Forwarded-For} is deliberately NOT
     * trusted (its leftmost hop is spoofable, which would let a caller mint a
     * fresh bucket per request — ADT-15). See {@code RateLimitInterceptor.clientIp}.
     * Default {@code 0} means anonymous traffic is not throttled and is allowed
     * straight through — keep the existing behaviour on endpoints that already
     * require auth. Set this on intentionally-public endpoints to cap scraper
     * abuse; pick a value that comfortably exceeds a human's interactive pace.
     */
    int anonymousPerMinute() default 0;

    /**
     * Behaviour when the rate-limit backend (Redis) itself errors and the limit
     * cannot be checked.
     *
     * <p>Default {@code false} = <em>fail open</em>: a backend blip lets the
     * request through rather than 503'ing the API — correct for public reads,
     * where availability beats throttling.
     *
     * <p>Set {@code true} = <em>fail closed</em> on money-mutating endpoints
     * (e.g. {@code POST /transfers}): if the limiter can't be consulted the
     * request is rejected with {@code 503}, so an attacker who can stress Redis
     * cannot also strip the throttle off the transfer path.
     */
    boolean failClosed() default false;
}
