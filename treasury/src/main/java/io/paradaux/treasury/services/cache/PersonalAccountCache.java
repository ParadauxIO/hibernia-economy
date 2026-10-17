package io.paradaux.treasury.services.cache;

import com.google.inject.Singleton;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Caches {@code UUID -> PERSONAL account_id}. A player's personal account id is
 * immutable once created (accounts are archived, never deleted), so an entry never
 * goes stale. Only positive resolutions are cached — a missing key means
 * "not resolved yet", never "no account", so we never cache a negative.
 *
 * <p>Eliminates the {@code findPersonalAccountId} + {@code findById} round-trips on
 * the hot Vault deposit/withdraw and balance-check paths for already-seen players.
 */
@Singleton
public class PersonalAccountCache {

    private final ConcurrentMap<UUID, Integer> cache = new ConcurrentHashMap<>();

    /** Cached account id for the owner, or {@code null} if not resolved yet. */
    public Integer get(UUID ownerUuid) {
        return cache.get(ownerUuid);
    }

    public void put(UUID ownerUuid, int accountId) {
        cache.put(ownerUuid, accountId);
    }

    /** Drops a single entry (e.g. if an account is ever archived/recreated). */
    public void invalidate(UUID ownerUuid) {
        cache.remove(ownerUuid);
    }
}
