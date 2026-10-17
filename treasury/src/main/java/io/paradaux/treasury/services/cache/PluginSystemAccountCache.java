package io.paradaux.treasury.services.cache;

import com.google.inject.Inject;
import io.paradaux.treasury.services.AccountService;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class PluginSystemAccountCache {
    private final AccountService accountService;
    private final ConcurrentMap<String, Integer> cache = new ConcurrentHashMap<>();
    /** Per-plugin creation locks. Bounded by the (small, fixed) set of plugin systems. */
    private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<>();

    @Inject
    public PluginSystemAccountCache(AccountService accountService) {
        this.accountService = accountService;
    }

    public int getOrCreate(String pluginName, UUID treasuryOwner) {
        Integer cached = cache.get(pluginName);
        if (cached != null) {
            return cached;
        }
        // Serialise creation per plugin name with a dedicated lock rather than
        // running the @Transactional getOrCreateSystemAccountId inside the cache
        // map's computeIfAbsent bin lock. Doing the DB call under the bin lock both
        // stalls other keys hashing to that bin and risks a re-entrancy deadlock,
        // which ConcurrentHashMap explicitly forbids of a mapping function (ADT-55
        // M9). Acquiring the lock object itself does no I/O, so it is bin-lock safe.
        Object lock = locks.computeIfAbsent(pluginName, k -> new Object());
        synchronized (lock) {
            Integer rechecked = cache.get(pluginName);
            if (rechecked != null) {
                return rechecked;
            }
            int resolved = accountService.getOrCreateSystemAccountId(pluginName, treasuryOwner);
            cache.put(pluginName, resolved);
            return resolved;
        }
    }
}
