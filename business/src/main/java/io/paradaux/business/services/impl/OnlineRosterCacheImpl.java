package io.paradaux.business.services.impl;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.business.services.FirmSuggestionCache;
import io.paradaux.business.services.OnlineRosterCache;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Default {@link OnlineRosterCache}: a lock-free roster of online UUIDs, maintained
 * on the main thread by {@code OnlineRosterListener} and read off the suggestion
 * thread without touching live Bukkit state. (business/plugin-architecture/0003)
 */
@Singleton
public class OnlineRosterCacheImpl implements OnlineRosterCache {

    private final Set<UUID> online = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Inject
    public OnlineRosterCacheImpl() {
    }

    /** Record a player as online. Called on join from the main thread. */
    @Override
    public void add(UUID playerId) {
        if (playerId != null) online.add(playerId);
    }

    /** Record a player as no longer online. Called on quit from the main thread. */
    @Override
    public void remove(UUID playerId) {
        if (playerId != null) online.remove(playerId);
    }

    /** A lock-free snapshot of the online roster. */
    @Override
    public Set<UUID> snapshot() {
        return Set.copyOf(online);
    }

    /**
     * The union of firm display names across every currently-online player, drawn
     * from {@code cache} (per-player, TTL-backed). Iterates a lock-free view of the
     * roster; a concurrent join/quit cannot break iteration.
     */
    @Override
    public Set<String> onlineFirmNames(FirmSuggestionCache cache) {
        Set<String> pool = new LinkedHashSet<>();
        for (UUID id : online) {
            pool.addAll(cache.playerFirmNames(id));
        }
        return pool;
    }
}
