package io.paradaux.business.commands.resolvers;

import io.paradaux.business.model.Firm;
import io.paradaux.business.services.FirmService;
import io.paradaux.business.services.FirmSuggestionCache;
import io.paradaux.business.services.impl.FirmSuggestionCacheImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Pure resolve/suggestions behaviour of {@link FirmNameResolver}
 * (business/testing/0004). resolve() just wraps a non-blank token (existence is
 * validated downstream); suggestions() is fed by {@link FirmSuggestionCache}.
 * Console (a null CommandSender, i.e. not a Player) falls back to all active firms.
 */
@ExtendWith(MockitoExtension.class)
class FirmNameResolverTest {

    @Mock FirmService firms;

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

    // ---- suggestions: console (non-Player sender) → all active firms ----------

    @Test
    void suggestions_forConsole_useActiveFirmNames() {
        when(firms.listAllActiveFirms()).thenReturn(List.of(firm("Acme"), firm("Globex")));

        // A null sender is not an instanceof Player, so the console branch runs.
        assertThat(resolver().suggestions("", null))
                .containsExactlyInAnyOrder("Acme", "Globex");
    }

    @Test
    void suggestions_forConsole_prefixFilters() {
        when(firms.listAllActiveFirms()).thenReturn(List.of(firm("Acme"), firm("Globex")));

        assertThat(resolver().suggestions("ac", null)).containsExactly("Acme");
    }
}
