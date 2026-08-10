package io.paradaux.treasury.services.cache;

import io.paradaux.treasury.mappers.AccountRedirectMapper;
import io.paradaux.treasury.model.economy.AccountRedirect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AccountRedirectCache} — the in-memory mirror of
 * {@code account_redirects} that answers Vault redirect lookups without hitting the
 * DB on the hot path (PAR-testing/0003). Money-adjacent: a wrong redirect sends a
 * player's Vault deposit to the wrong account.
 */
class AccountRedirectCacheTest {

    private AccountRedirectMapper mapper;
    private AccountRedirectCache cache;

    private static AccountRedirect redirect(UUID uuid, int accountId) {
        AccountRedirect r = new AccountRedirect();
        r.setRedirectUuid(uuid);
        r.setAccountId(accountId);
        return r;
    }

    @BeforeEach
    void setUp() {
        mapper = mock(AccountRedirectMapper.class);
        cache = new AccountRedirectCache(mapper);
    }

    @Test
    void get_hit_returnsMappedAccountId() {
        UUID legacy = UUID.randomUUID();
        when(mapper.findAllRedirects()).thenReturn(List.of(redirect(legacy, 77)));

        assertThat(cache.get(legacy)).isEqualTo(77);
    }

    @Test
    void get_miss_returnsNull_authoritativeNotUnknown() {
        when(mapper.findAllRedirects()).thenReturn(List.of(redirect(UUID.randomUUID(), 77)));

        assertThat(cache.get(UUID.randomUUID())).isNull();
    }

    @Test
    void get_warmsOnce_thenServesFromMemory() {
        UUID legacy = UUID.randomUUID();
        when(mapper.findAllRedirects()).thenReturn(List.of(redirect(legacy, 5)));

        cache.get(legacy);
        cache.get(legacy);
        cache.get(UUID.randomUUID());

        // Lazy warm loads the whole set exactly once; subsequent hits never touch DB.
        verify(mapper, times(1)).findAllRedirects();
    }

    @Test
    void reload_picksUpChangedRedirects() {
        UUID legacy = UUID.randomUUID();
        when(mapper.findAllRedirects()).thenReturn(List.of(redirect(legacy, 1)));
        assertThat(cache.get(legacy)).isEqualTo(1);

        when(mapper.findAllRedirects()).thenReturn(List.of(redirect(legacy, 2)));
        cache.reload();

        assertThat(cache.get(legacy)).isEqualTo(2);
        verify(mapper, times(2)).findAllRedirects();
    }

    @Test
    void redirectIsSingleHop_notChainFollowed() {
        // A redirect maps UUID -> account_id (an int). It never points at another
        // redirectable UUID, so there is no chain to follow and no cycle to loop on.
        // Pin that contract: even if account_id 200 numerically coincides with some
        // notion of "next", get() returns it verbatim without a second lookup.
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(mapper.findAllRedirects()).thenReturn(List.of(
                redirect(a, 200),
                redirect(b, 300)));

        assertThat(cache.get(a)).isEqualTo(200);
        assertThat(cache.get(b)).isEqualTo(300);
    }

    @Test
    void selfReferentialUuidRedirect_isSafe_returnsAccountId() {
        // A UUID that redirects to the account it "owns" is a normal single hop:
        // there is no recursion because the value is an account id, not the UUID.
        UUID self = UUID.randomUUID();
        when(mapper.findAllRedirects()).thenReturn(List.of(redirect(self, 42)));

        assertThat(cache.get(self)).isEqualTo(42);
    }
}
