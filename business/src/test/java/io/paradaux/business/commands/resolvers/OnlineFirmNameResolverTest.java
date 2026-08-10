package io.paradaux.business.commands.resolvers;

import io.paradaux.business.model.Firm;
import io.paradaux.business.services.FirmService;
import io.paradaux.business.services.FirmSuggestionCache;
import io.paradaux.business.services.OnlineRosterCache;
import io.paradaux.business.services.impl.FirmSuggestionCacheImpl;
import io.paradaux.business.services.impl.OnlineRosterCacheImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Pins that {@code OnlineFirmNameResolver.suggestions()} is fed purely by the
 * service-managed {@link OnlineRosterCache} + {@link FirmSuggestionCache} — no live
 * Bukkit access (business/plugin-architecture/0003). The resolver is driven with a
 * null CommandSender to prove nothing dereferences Bukkit state.
 */
@ExtendWith(MockitoExtension.class)
class OnlineFirmNameResolverTest {

    @Mock FirmService firms;

    private static Firm firm(String displayName) {
        Firm f = new Firm();
        f.setDisplayName(displayName);
        return f;
    }

    private OnlineFirmNameResolver resolver(OnlineRosterCache roster) {
        return new OnlineFirmNameResolver(new FirmSuggestionCacheImpl(firms), roster);
    }

    // ---- resolve --------------------------------------------------------------

    @Test
    void resolve_wrapsNonBlankToken() {
        OnlineFirmNameResolver r = resolver(new OnlineRosterCacheImpl());
        assertThat(r.resolve("  Acme  ", null)).contains(new OnlineFirmName("Acme"));
    }

    @Test
    void resolve_rejectsBlankAndNull() {
        OnlineFirmNameResolver r = resolver(new OnlineRosterCacheImpl());
        assertThat(r.resolve("   ", null)).isEmpty();
        assertThat(r.resolve(null, null)).isEmpty();
    }

    // ---- suggestions: fed by the roster + firm cache, no Bukkit ---------------

    @Test
    void suggestions_unionOnlineFirms() {
        OnlineRosterCache roster = new OnlineRosterCacheImpl();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        roster.add(a);
        roster.add(b);
        when(firms.listOwnedOrMemberFirms(a)).thenReturn(List.of(firm("Acme")));
        when(firms.listOwnedOrMemberFirms(b)).thenReturn(List.of(firm("Globex")));

        assertThat(resolver(roster).suggestions("", null))
                .containsExactlyInAnyOrder("Acme", "Globex");
    }

    @Test
    void suggestions_prefixFilters() {
        OnlineRosterCache roster = new OnlineRosterCacheImpl();
        UUID a = UUID.randomUUID();
        roster.add(a);
        when(firms.listOwnedOrMemberFirms(a)).thenReturn(List.of(firm("Acme"), firm("Globex")));

        assertThat(resolver(roster).suggestions("ac", null)).containsExactly("Acme");
    }

    @Test
    void suggestions_emptyWhenRosterEmpty() {
        assertThat(resolver(new OnlineRosterCacheImpl()).suggestions("", null)).isEmpty();
    }
}
