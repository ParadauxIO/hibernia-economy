package io.paradaux.treasuryrestapi.service;

import io.paradaux.treasuryrestapi.model.RateLimitOverride;
import io.paradaux.treasuryrestapi.ratelimit.RateLimitOverrideService;
import io.paradaux.treasuryrestapi.testsupport.EmbeddedDbIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-DB tests for {@link RateLimitOverrideService} (finding
 * treasury-rest-api/testing/0008) — the interceptor test only ever hits it through a
 * NOOP in-memory mapper. Here the service runs against the embedded MariaDB so the
 * default-when-absent behaviour, the DB-backed multiplier lookup, and cache
 * invalidation on set/clear are pinned to observable outcomes.
 */
class RateLimitOverrideServiceIT extends EmbeddedDbIT {

    @Autowired
    private RateLimitOverrideService service;

    private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ADMIN = UUID.fromString("99999999-9999-9999-9999-999999999999");

    @Test
    void getMultiplier_nullOwner_isDefaultOne() {
        assertThat(service.getMultiplier(null)).isEqualByComparingTo("1.00");
    }

    @Test
    void getMultiplier_absentOverride_isDefaultOne() {
        assertThat(service.getMultiplier(OWNER)).isEqualByComparingTo("1.00");
    }

    @Test
    void set_thenGet_returnsPersistedMultiplier_andInvalidatesCache() {
        // Prime the cache with the default (no row yet).
        assertThat(service.getMultiplier(OWNER)).isEqualByComparingTo("1.00");
        // set() upserts AND invalidates the cached default so the new value is visible
        // immediately (not after the 60s cache window).
        service.set(OWNER, new BigDecimal("7.50"), "trusted", ADMIN);
        assertThat(service.getMultiplier(OWNER)).isEqualByComparingTo("7.50");
    }

    @Test
    void clear_removesRow_andCacheFallsBackToDefault() {
        service.set(OWNER, new BigDecimal("5.00"), null, ADMIN);
        assertThat(service.getMultiplier(OWNER)).isEqualByComparingTo("5.00");
        service.clear(OWNER);
        assertThat(rowCount("api_rate_limit_override")).isZero();
        assertThat(service.getMultiplier(OWNER)).isEqualByComparingTo("1.00");
    }

    @Test
    void listAll_returnsPersistedOverrides() {
        service.set(OWNER, new BigDecimal("3.25"), "note-a", ADMIN);
        List<RateLimitOverride> all = service.listAll();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getOwnerUuid()).isEqualTo(OWNER);
        assertThat(all.get(0).getMultiplier()).isEqualByComparingTo("3.25");
        assertThat(all.get(0).getNote()).isEqualTo("note-a");
    }
}
