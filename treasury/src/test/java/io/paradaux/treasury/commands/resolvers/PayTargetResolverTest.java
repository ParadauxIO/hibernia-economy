package io.paradaux.treasury.commands.resolvers;

import io.paradaux.treasury.model.economy.Account;
import io.paradaux.treasury.services.AccountService;
import io.paradaux.treasury.services.cache.OnlinePlayerRoster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PayTargetResolver}. After PAR-0003 the resolver reads online
 * player names from {@link OnlinePlayerRoster} (never live Bukkit), so its suggestion
 * and bind paths are pure and testable without a server.
 */
class PayTargetResolverTest {

    private AccountService accountService;
    private OnlinePlayerRoster roster;
    private PayTargetResolver resolver;

    private static Account gov(String name, boolean archived) {
        Account a = new Account();
        a.setDisplayName(name);
        a.setArchived(archived);
        return a;
    }

    @BeforeEach
    void setUp() {
        accountService = mock(AccountService.class);
        roster = new OnlinePlayerRoster();
        resolver = new PayTargetResolver(accountService, roster);
        when(accountService.listGovernmentAccounts()).thenReturn(List.of());
    }

    @Test
    void resolve_wrapsTokenVerbatim_neverEmpty() {
        // The framework must not short-circuit; PayCommand owns validity.
        assertThat(resolver.resolve("Anybody", null))
                .isPresent()
                .get()
                .isEqualTo(new PayTarget("Anybody"));
    }

    @Test
    void type_isPayTarget() {
        assertThat(resolver.type()).isEqualTo(PayTarget.class);
    }

    @Test
    void suggestions_onlyOnlinePlayers_fromRoster_notLiveBukkit() {
        roster.add(UUID.randomUUID(), "Steve");
        roster.add(UUID.randomUUID(), "Alex");

        List<String> hits = resolver.suggestions("St", null);

        assertThat(hits).containsExactly("Steve");
    }

    @Test
    void suggestions_mergePlayersAndGovernmentNames() {
        roster.add(UUID.randomUUID(), "GovBanker");
        when(accountService.listGovernmentAccounts())
                .thenReturn(List.of(gov("GovReserve", false)));

        List<String> hits = resolver.suggestions("Gov", null);

        assertThat(hits).containsExactlyInAnyOrder("GovBanker", "GovReserve");
    }

    @Test
    void suggestions_archivedGovernmentAccounts_areExcluded() {
        when(accountService.listGovernmentAccounts())
                .thenReturn(List.of(gov("GovOld", true), gov("GovReserve", false)));

        List<String> hits = resolver.suggestions("Gov", null);

        assertThat(hits).containsExactly("GovReserve");
    }

    @Test
    void suggestions_isCappedAtTwenty() {
        for (int i = 0; i < 30; i++) {
            roster.add(UUID.randomUUID(), String.format("Player%02d", i));
        }
        assertThat(resolver.suggestions("Player", null)).hasSize(20);
    }

    @Test
    void suggestions_emptyPrefix_matchesEverything() {
        roster.add(UUID.randomUUID(), "Steve");
        when(accountService.listGovernmentAccounts())
                .thenReturn(List.of(gov("GovReserve", false)));

        assertThat(resolver.suggestions("", null))
                .containsExactlyInAnyOrder("Steve", "GovReserve");
    }
}
