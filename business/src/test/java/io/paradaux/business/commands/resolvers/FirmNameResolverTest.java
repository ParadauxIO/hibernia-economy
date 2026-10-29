package io.paradaux.business.commands.resolvers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.paradaux.business.model.Firm;
import io.paradaux.business.services.FirmService;
import io.paradaux.business.services.FirmSuggestionCache;
import io.paradaux.business.services.impl.FirmSuggestionCacheImpl;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pure resolve/suggestions behaviour of {@link FirmNameResolver}
 * (business/testing/0004). resolve() just wraps a non-blank token (existence is
 * validated downstream); suggestions() is fed by {@link FirmSuggestionCache}.
 * Console (a null CommandSender, i.e. not a Player) falls back to all active firms.
 */
@ExtendWith(MockitoExtension.class)
class FirmNameResolverTest {

    @Mock
    FirmService firms;

    private FirmNameResolver resolver() {
        return new FirmNameResolver(new FirmSuggestionCacheImpl(firms));
    }

    private static Firm firm(String displayName) {
        Firm f = new Firm();
        f.setDisplayName(displayName);
        return f;
    }

    // ---- resolve --------------------------------------------------------------

    @Test
    void resolve_wrapsAndTrimsNonBlankToken() {
        assertThat(resolver().resolve("  Acme  ", null)).contains(new FirmName("Acme"));
    }

    @Test
    void resolve_rejectsBlankAndNull() {
        assertThat(resolver().resolve("   ", null)).isEmpty();
        assertThat(resolver().resolve(null, null)).isEmpty();
    }

    @Test
    void playerSuggestionsStayWithOwnFirmsButOtherNamesResolve() {
        FirmSuggestionCache cache = org.mockito.Mockito.mock(FirmSuggestionCache.class);
        org.bukkit.entity.Player player = org.mockito.Mockito.mock(org.bukkit.entity.Player.class);
        java.util.UUID id = java.util.UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        when(cache.playerFirmNames(id)).thenReturn(List.of("Acme"));
        FirmNameResolver resolver = new FirmNameResolver(cache);

        assertThat(resolver.suggestions("", player)).containsExactly("Acme");
        assertThat(resolver.suggestions("Glob", player)).isEmpty();
        assertThat(resolver.resolve("Globex", player)).contains(new FirmName("Globex"));
        org.mockito.Mockito.verify(cache, org.mockito.Mockito.never()).activeFirmNames();
    }

    // ---- suggestions: console (non-Player sender) → all active firms ----------

    @Test
    void suggestions_forConsole_useActiveFirmNames() {
        when(firms.listAllActiveFirms()).thenReturn(List.of(firm("Acme"), firm("Globex")));

        // A null sender is not an instanceof Player, so the console branch runs.
        assertThat(resolver().suggestions("", null)).containsExactlyInAnyOrder("Acme", "Globex");
    }

    @Test
    void suggestions_forConsole_prefixFilters() {
        when(firms.listAllActiveFirms()).thenReturn(List.of(firm("Acme"), firm("Globex")));

        assertThat(resolver().suggestions("ac", null)).containsExactly("Acme");
    }
}
