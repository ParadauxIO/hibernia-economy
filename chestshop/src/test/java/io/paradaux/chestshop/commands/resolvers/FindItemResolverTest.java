package io.paradaux.chestshop.commands.resolvers;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.paradaux.chestshop.services.FindAliases;
import io.paradaux.chestshop.services.MarketService;
import io.paradaux.treasury.api.ShopQueryApi;
import java.util.List;
import java.util.Set;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

class FindItemResolverTest {
    @Test
    void suggestionsUseCachedRegistryCodesAndLiveSearchAliasesWithoutPerKeystrokeQueries() {
        var market = mock(MarketService.class);
        var api = mock(ShopQueryApi.class);
        var aliases = mock(FindAliases.class);
        when(market.shopQuery()).thenReturn(api);
        when(api.matchingItemKeys("", Integer.MAX_VALUE)).thenReturn(List.of("Diamond", "DIAMOND_BLOCK", "STONE"));
        when(aliases.names()).thenReturn(Set.of("gems"));
        var resolver = new FindItemResolver(mock(JavaPlugin.class), market, aliases);
        resolver.refresh();
        clearInvocations(api);
        assertThat(resolver.suggestions("mond", null)).containsExactlyInAnyOrder("Diamond", "DIAMOND_BLOCK");
        assertThat(resolver.suggestions("GEM", null)).containsExactly("gems");
        assertThat(resolver.suggestions("missing", null)).isEmpty();
        verifyNoInteractions(api);
        assertThat(resolver.resolve("Potion#code with spaces", null)
                        .orElseThrow()
                        .code())
                .isEqualTo("Potion#code with spaces");
        assertThat(resolver.resolve(" ", null)).isEmpty();
        assertThat(resolver.resolve(null, null)).isEmpty();
        assertThat(resolver.type()).isEqualTo(FindItem.class);
    }

    @Test
    void missingApiLeavesSuggestionsEmptyAndFailedRefreshKeepsTheLastSnapshot() {
        var plugin = mock(JavaPlugin.class);
        var market = mock(MarketService.class);
        var aliases = mock(FindAliases.class);
        when(aliases.names()).thenReturn(Set.of());
        var resolver = new FindItemResolver(plugin, market, aliases);
        resolver.refresh();
        assertThat(resolver.suggestions("", null)).isEmpty();
        var api = mock(ShopQueryApi.class);
        when(market.shopQuery()).thenReturn(api);
        when(api.matchingItemKeys("", Integer.MAX_VALUE)).thenReturn(List.of("STONE"));
        resolver.refresh();
        when(api.matchingItemKeys("", Integer.MAX_VALUE)).thenThrow(new IllegalStateException("offline"));
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        resolver.refresh();
        assertThat(resolver.suggestions("", null)).containsExactly("STONE");
    }
}
