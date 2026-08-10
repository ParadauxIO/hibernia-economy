package io.paradaux.treasury.services.cache;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.treasury.mappers.AccountRedirectMapper;
import io.paradaux.treasury.model.economy.AccountRedirect;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * In-memory mirror of {@code account_redirects}. The table is small and effectively
 * static at runtime (only TreasuryIngest writes to it, during one-shot migrations),
 * yet it was being queried on <em>every</em> Vault deposit/withdraw/balance call.
 *
 * <p>We load the whole set once and answer lookups from memory. Because the entire
 * set is loaded, a miss is authoritative — {@code null} means "no redirect for this
 * UUID", not "unknown". Warming happens lazily on first use inside the caller's
 * transaction (the loader joins the active MyBatis session); after that, hits never
 * touch the connection pool. Call {@link #reload()} if redirects change at runtime.
 */
@Singleton
public class AccountRedirectCache {

    private final AccountRedirectMapper redirectMapper;
    private volatile Map<UUID, Integer> redirects;

    @Inject
    public AccountRedirectCache(AccountRedirectMapper redirectMapper) {
        this.redirectMapper = redirectMapper;
    }

    /** Redirected account id for the UUID, or {@code null} if none. Warms on first use. */
    public Integer get(UUID uuid) {
        Map<UUID, Integer> map = redirects;
        if (map == null) {
            synchronized (this) {
                map = redirects;
                if (map == null) {
                    map = load();
                    redirects = map;
                }
            }
        }
        return map.get(uuid);
    }

    /** Reloads the full set from the DB. Call after TreasuryIngest writes redirects. */
    public synchronized void reload() {
        redirects = load();
    }

    private Map<UUID, Integer> load() {
        Map<UUID, Integer> map = new HashMap<>();
        for (AccountRedirect r : redirectMapper.findAllRedirects()) {
            map.put(r.getRedirectUuid(), r.getAccountId());
        }
        return map;
    }
}
