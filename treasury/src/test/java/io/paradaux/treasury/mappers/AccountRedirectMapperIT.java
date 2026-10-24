package io.paradaux.treasury.mappers;

import io.paradaux.treasury.model.economy.Account;
import io.paradaux.treasury.model.economy.AccountRedirect;
import io.paradaux.treasury.model.economy.AccountType;
import io.paradaux.treasury.services.cache.AccountRedirectCache;
import io.paradaux.treasury.testsupport.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the {@code account_redirects} chain against real MariaDB
 * (economy-flyway V2): {@link AccountRedirectMapper} find/upsert plus
 * {@link AccountRedirectCache} warming from the live table (PAR-testing/0003).
 *
 * <p>Money-adjacent: a redirect routes a legacy player's Vault deposit/withdraw onto
 * a canonical GOVERNMENT account, so resolution, idempotent re-mapping, and
 * self/"cycle" inputs must behave predictably.
 */
class AccountRedirectMapperIT extends IntegrationTestBase {

    private AccountRedirectMapper mapper;
    private AccountMapper accountMapper;

    @BeforeEach
    void setUp() {
        mapper = injector.getInstance(AccountRedirectMapper.class);
        accountMapper = injector.getInstance(AccountMapper.class);
    }

    /** Inserts a GOVERNMENT account and returns its generated id (satisfies fk_redirect_account). */
    private int newGovernmentAccount(String displayName) {
        Account a = new Account();
        a.setAccountType(AccountType.GOVERNMENT);
        a.setOwnerUuid(UUID.randomUUID());
        a.setDisplayName(displayName);
        a.setRequiresAuthorization(false);
        a.setArchived(false);
        a.setAllowOverdraft(false);
        a.setCreditLimit(BigDecimal.ZERO);
        accountMapper.insertAccount(a);
        assertThat(a.getAccountId()).isNotNull();
        return a.getAccountId();
    }

    @Test
    void upsert_thenFind_resolvesToAccount() {
        int accountId = newGovernmentAccount("DCGovernment");
        UUID legacy = UUID.randomUUID();

        int rows = mapper.upsertRedirect(legacy, accountId, "essentialsx-migration");
        assertThat(rows).isPositive();

        assertThat(mapper.findRedirectAccountId(legacy)).isEqualTo(accountId);
    }

    @Test
    void findRedirectAccountId_noRedirect_returnsNull() {
        assertThat(mapper.findRedirectAccountId(UUID.randomUUID())).isNull();
    }

    @Test
    void upsert_isIdempotent_secondCallRepointsSameUuid() {
        int first = newGovernmentAccount("GovReserve");
        int second = newGovernmentAccount("GovEducation");
        UUID legacy = UUID.randomUUID();

        mapper.upsertRedirect(legacy, first, "v1");
        mapper.upsertRedirect(legacy, second, "v2");

        // ON DUPLICATE KEY UPDATE re-points the same UUID, no duplicate row.
        assertThat(mapper.findRedirectAccountId(legacy)).isEqualTo(second);
        assertThat(mapper.findAllRedirects()).hasSize(1);
    }

    @Test
    void findAllRedirects_returnsEveryRow_forCacheWarm() {
        int govA = newGovernmentAccount("GovA");
        int govB = newGovernmentAccount("GovB");
        UUID uA = UUID.randomUUID();
        UUID uB = UUID.randomUUID();
        mapper.upsertRedirect(uA, govA, null);
        mapper.upsertRedirect(uB, govB, null);

        List<AccountRedirect> all = mapper.findAllRedirects();

        assertThat(all).hasSize(2);
        assertThat(all).anySatisfy(r -> {
            assertThat(r.getRedirectUuid()).isEqualTo(uA);
            assertThat(r.getAccountId()).isEqualTo(govA);
        });
        assertThat(all).anySatisfy(r -> {
            assertThat(r.getRedirectUuid()).isEqualTo(uB);
            assertThat(r.getAccountId()).isEqualTo(govB);
        });
    }

    @Test
    void cache_warmsFromLiveTable_hitAndMiss() {
        int accountId = newGovernmentAccount("DCGovernment");
        UUID legacy = UUID.randomUUID();
        mapper.upsertRedirect(legacy, accountId, null);

        AccountRedirectCache cache = injector.getInstance(AccountRedirectCache.class);

        assertThat(cache.get(legacy)).isEqualTo(accountId);
        assertThat(cache.get(UUID.randomUUID())).isNull();
    }

    @Test
    void cache_reload_reflectsNewRedirect() {
        AccountRedirectCache cache = injector.getInstance(AccountRedirectCache.class);
        UUID legacy = UUID.randomUUID();

        // Warm empty: miss.
        assertThat(cache.get(legacy)).isNull();

        int accountId = newGovernmentAccount("GovLate");
        mapper.upsertRedirect(legacy, accountId, null);
        cache.reload();

        assertThat(cache.get(legacy)).isEqualTo(accountId);
    }

    @Test
    void selfAndCrossRedirects_areSingleHop_noChainOrCycle() {
        // Two accounts, two legacy UUIDs cross-pointing. There is no UUID-to-UUID
        // edge (a redirect targets an account id), so nothing chains and no cycle
        // can form — each resolves in exactly one hop.
        int govA = newGovernmentAccount("GovA");
        int govB = newGovernmentAccount("GovB");
        UUID uA = UUID.randomUUID();
        UUID uB = UUID.randomUUID();
        mapper.upsertRedirect(uA, govB, "a->B");
        mapper.upsertRedirect(uB, govA, "b->A");

        assertThat(mapper.findRedirectAccountId(uA)).isEqualTo(govB);
        assertThat(mapper.findRedirectAccountId(uB)).isEqualTo(govA);

        AccountRedirectCache cache = injector.getInstance(AccountRedirectCache.class);
        assertThat(cache.get(uA)).isEqualTo(govB);
        assertThat(cache.get(uB)).isEqualTo(govA);
    }
}
