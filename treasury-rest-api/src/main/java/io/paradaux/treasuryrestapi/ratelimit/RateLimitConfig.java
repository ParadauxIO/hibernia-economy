package io.paradaux.treasuryrestapi.ratelimit;

import tools.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Wires the rate-limiting layer.
 *
 * <p>Picks {@link LettuceBucketProvider} when {@code rate-limit.redis-host}
 * is set (production multi-pod), {@link CaffeineBucketProvider} otherwise
 * (single-process and dev). Registers the {@link RateLimitInterceptor}
 * against all paths; routes without {@link RateLimit} are no-ops.
 *
 * <p>Config keys (Spring property → env var):
 * <ul>
 *   <li>{@code rate-limit.redis-host} → {@code RATE_LIMIT_REDIS_HOST}</li>
 *   <li>{@code rate-limit.redis-port} → {@code RATE_LIMIT_REDIS_PORT}
 *       (default 6379)</li>
 * </ul>
 */
@Configuration
public class RateLimitConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(RateLimitConfig.class);

    @Value("${rate-limit.redis-host:}")
    private String redisHost;

    @Value("${rate-limit.redis-port:6379}")
    private int redisPort;

    @Value("${rate-limit.redis-password:}")
    private String redisPassword;

    private final ObjectMapper objectMapper;
    private final RateLimitOverrideService overrides;
    private final MeterRegistry metrics;
    private final ObjectProvider<RateLimitInterceptor> interceptorProvider;

    public RateLimitConfig(ObjectMapper objectMapper, RateLimitOverrideService overrides,
                           MeterRegistry metrics,
                           ObjectProvider<RateLimitInterceptor> interceptorProvider) {
        this.objectMapper = objectMapper;
        this.overrides = overrides;
        this.metrics = metrics;
        this.interceptorProvider = interceptorProvider;
    }

    @Bean
    public BucketProvider bucketProvider() {
        if (redisHost != null && !redisHost.isBlank()) {
            log.info("Rate limiting: Redis backend at {}:{} (auth={})",
                    redisHost, redisPort, redisPassword != null && !redisPassword.isBlank());
            return new LettuceBucketProvider(redisHost, redisPort, redisPassword);
        }
        log.info("Rate limiting: in-memory Caffeine backend (single-pod scope)");
        return new CaffeineBucketProvider();
    }

    @Bean
    public RateLimitInterceptor rateLimitInterceptor(BucketProvider bucketProvider) {
        return new RateLimitInterceptor(bucketProvider, objectMapper, overrides, metrics);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // No path filter — the interceptor is a no-op on routes without
        // @RateLimit, and only kicks in for authenticated requests anyway.
        // Reuse the singleton bean rather than re-invoking the @Bean factory
        // methods, which would build a second BucketProvider (and, on Lettuce,
        // a second Redis client that is never closed).
        registry.addInterceptor(interceptorProvider.getObject());
    }
}
