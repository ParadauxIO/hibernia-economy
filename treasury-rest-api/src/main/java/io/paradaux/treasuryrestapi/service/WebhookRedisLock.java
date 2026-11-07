package io.paradaux.treasuryrestapi.service;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import jakarta.annotation.PreDestroy;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * A single-holder lease over Redis so that, across the API's replicas, exactly
 * one pod runs the webhook dispatch loop. Reuses the same Redis the rate limiter
 * uses ({@code rate-limit.redis-*}).
 *
 * <p>The dispatcher renews the lease before every send, so the TTL only has to
 * outlast one delivery (connect timeout + request timeout, 15 s by default).
 *
 * <p>When no Redis is configured (dev / single process) the lease is a no-op and
 * always granted. When Redis IS configured but unreachable, the lease is
 * <b>denied</b> — the dispatcher pauses rather than risk two pods firing
 * webhooks (fail-safe).
 */
@Component
public class WebhookRedisLock {

    private static final Logger log = LoggerFactory.getLogger(WebhookRedisLock.class);

    // Atomic "renew if I hold it, else acquire if free".
    private static final String ACQUIRE_OR_RENEW = "if redis.call('get', KEYS[1]) == ARGV[1] then "
            + "  redis.call('pexpire', KEYS[1], ARGV[2]); return 1 "
            + "else "
            + "  if redis.call('set', KEYS[1], ARGV[1], 'NX', 'PX', tonumber(ARGV[2])) then return 1 else return 0 end "
            + "end";

    private static final String RELEASE =
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";

    private final String ownerId = UUID.randomUUID().toString();
    private final String lockKey;
    private final long ttlMs;
    private final boolean enabled;

    private RedisClient client;
    private StatefulRedisConnection<String, String> conn;

    public WebhookRedisLock(
            @Value("${rate-limit.redis-host:}") String host,
            @Value("${rate-limit.redis-port:6379}") int port,
            @Value("${rate-limit.redis-password:}") String password,
            @Value("${treasury.webhook.lock-key:treasury:webhook:dispatcher}") String lockKey,
            @Value("${treasury.webhook.lock-ttl-ms:30000}") long ttlMs) {
        this.lockKey = lockKey;
        this.ttlMs = ttlMs;
        this.enabled = host != null && !host.isBlank();
        if (enabled) {
            RedisURI.Builder b = RedisURI.builder().withHost(host).withPort(port);
            if (password != null && !password.isBlank()) b.withPassword(password.toCharArray());
            this.client = RedisClient.create(b.build());
            this.conn = client.connect();
            log.info("Webhook dispatcher lock: Redis lease at {}:{} (owner={})", host, port, ownerId);
        } else {
            log.info("Webhook dispatcher lock: disabled (no Redis) — single-process mode, lease always granted");
        }
    }

    /** True if this pod may run the dispatch loop this tick. */
    public boolean tryAcquireOrRenew() {
        if (!enabled) return true;
        try {
            RedisCommands<String, String> c = conn.sync();
            Long held = c.eval(
                    ACQUIRE_OR_RENEW, ScriptOutputType.INTEGER, new String[] {lockKey}, ownerId, Long.toString(ttlMs));
            return held != null && held == 1L;
        } catch (Exception e) {
            // Fail-safe: if Redis is unreachable we cannot prove single-holder, so
            // we don't dispatch (webhooks pause until Redis returns).
            log.warn("Webhook lock check failed, pausing dispatch this tick: {}", e.toString());
            return false;
        }
    }

    @PreDestroy
    public void release() {
        if (!enabled || conn == null) return;
        try {
            conn.sync().eval(RELEASE, ScriptOutputType.INTEGER, new String[] {lockKey}, ownerId);
        } catch (Exception e) {
            log.debug("Webhook lock release failed (ttl will expire it): {}", e.toString());
        } finally {
            try {
                conn.close();
            } catch (Exception ignored) {
            }
            try {
                client.shutdown();
            } catch (Exception ignored) {
            }
        }
    }
}
