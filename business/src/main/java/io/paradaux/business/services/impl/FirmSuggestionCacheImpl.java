package io.paradaux.business.services.impl;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.business.model.Firm;
import io.paradaux.business.services.FirmService;
import io.paradaux.business.services.FirmSuggestionCache;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/**
 * Default {@link FirmSuggestionCache}: a short-TTL, in-memory cache of firm display
 * names for command tab-completion (PAR-13). See the interface for the two query
 * angles; entries refresh lazily once older than {@link #TTL_MS}.
 */
@Singleton
public class FirmSuggestionCacheImpl implements FirmSuggestionCache {

    static final long TTL_MS = 30_000L;

    private final FirmService firms;
    private final LongSupplier clock;

    private final Map<UUID, Entry> byPlayer = new ConcurrentHashMap<>();
    private volatile Set<String> active = Set.of();
    private volatile long activeAt = Long.MIN_VALUE;

    @Inject
    public FirmSuggestionCacheImpl(FirmService firms) {
        this(firms, System::currentTimeMillis);
    }

    FirmSuggestionCacheImpl(FirmService firms, LongSupplier clock) {
        this.firms = firms;
        this.clock = clock;
    }

    /** Names of firms the player owns or is employed by (cached per-player). */
    @Override
    public List<String> playerFirmNames(UUID playerId) {
        Entry e = byPlayer.get(playerId);
        long now = clock.getAsLong();
        if (e == null || now - e.at > TTL_MS) {
            e = new Entry(names(firms.listOwnedOrMemberFirms(playerId)), now);
            byPlayer.put(playerId, e);
        }
        return e.names;
    }

    /** Every active firm name (the broad pool, for unknown-firm suggestions). */
    @Override
    public Set<String> activeFirmNames() {
        long now = clock.getAsLong();
        // activeAt starts at MIN_VALUE; a plain (now - activeAt) would overflow, so guard the
        // first load explicitly rather than relying on the delta.
        if (activeAt == Long.MIN_VALUE || now - activeAt > TTL_MS) {
            active = Set.copyOf(names(firms.listAllActiveFirms()));
            activeAt = now;
        }
        return active;
    }

    private static List<String> names(List<Firm> firms) {
        // Never suggest disbanded firms — they no longer resolve on command paths (PAR-87),
        // so completing one would just produce a "not found". (activeFirmNames is already
        // active-only; this guards the per-player pool, which includes past memberships.)
        return firms.stream()
                .filter(f -> !Boolean.TRUE.equals(f.getArchived()))
                .map(Firm::getDisplayName)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private record Entry(List<String> names, long at) {}
}
