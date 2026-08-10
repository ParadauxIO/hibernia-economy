package io.paradaux.treasury.services.impl;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.treasury.mappers.EconomyPlayerMapper;
import io.paradaux.treasury.services.PlayerDirectoryService;
import org.mybatis.guice.transactional.Transactional;

import java.util.Optional;
import java.util.UUID;

@Singleton
public class PlayerDirectoryServiceImpl implements PlayerDirectoryService {

    private final EconomyPlayerMapper economyPlayers;

    /**
     * A fixed pool of stripe locks that serialise concurrent {@link #recordLogin}
     * calls for the same UUID within this JVM. A previous version keyed a
     * {@code ConcurrentHashMap<UUID,Object>} which grew without bound (one entry per
     * player ever seen); striping by hash gives the same intra-process serialisation
     * with fixed memory. Cross-process correctness comes from the {@code FOR UPDATE}
     * row lock below, not these monitors (ADT-9).
     */
    private static final int LOGIN_LOCK_STRIPES = 64;
    private final Object[] loginLocks;

    @Inject
    public PlayerDirectoryServiceImpl(EconomyPlayerMapper economyPlayers) {
        this.economyPlayers = economyPlayers;
        Object[] locks = new Object[LOGIN_LOCK_STRIPES];
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new Object();
        }
        this.loginLocks = locks;
    }

    @Override
    @Transactional
    public Long recordLogin(UUID playerUuid, String currentName, long epochSeconds) {
        Object lock = loginLocks[Math.floorMod(playerUuid.hashCode(), LOGIN_LOCK_STRIPES)];
        synchronized (lock) {
            // Capture the previous login before overwriting it; the caller prorates
            // against this so it must be read and replaced as one atomic step. The
            // FOR UPDATE row lock serialises this across processes on a multi-writer
            // DB — the JVM monitor above only covers concurrent logins in this JVM.
            Long previousEpoch = economyPlayers.findLastLoginForUpdate(playerUuid);

            // A name belongs to exactly one player at a time; evict any stale claim by a
            // different player first so the upsert can't collide on UNIQUE(name_lower)
            // when a name is changed or reused (the old holder re-registers on next login).
            economyPlayers.releaseNameFromOthers(currentName, playerUuid);
            economyPlayers.upsertLogin(playerUuid, currentName, epochSeconds);

            return previousEpoch;
        }
    }

    @Override
    @Transactional
    public Optional<UUID> resolveUuidByName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(economyPlayers.findUuidByName(name.trim()));
    }

    @Override
    @Transactional
    public Optional<String> resolveNameByUuid(UUID playerUuid) {
        return Optional.ofNullable(economyPlayers.findNameByUuid(playerUuid));
    }
}
