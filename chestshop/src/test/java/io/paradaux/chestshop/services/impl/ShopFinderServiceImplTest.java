package io.paradaux.chestshop.services.impl;

import io.paradaux.chestshop.dialogs.FindState;
import io.paradaux.chestshop.model.FoundShop;
import io.paradaux.chestshop.services.MarketService;
import io.paradaux.chestshop.support.ServerTest;
import io.paradaux.treasury.api.ShopQueryApi;
import io.paradaux.treasury.api.market.ShopResult;
import io.paradaux.treasury.api.market.ShopSearchQuery;
import org.bukkit.Material;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ShopFinderServiceImpl}: the off-thread {@code /find} query + in-memory pipeline over a
 * mocked Treasury {@link ShopQueryApi}, on a live MockBukkit plugin/scheduler.
 */
class ShopFinderServiceImplTest extends ServerTest {

    private JavaPlugin plugin;
    private MarketService market;
    private ShopFinderServiceImpl finder;

    @BeforeEach
    void wire() {
        plugin = MockBukkit.createMockPlugin("FinderPlugin");
        market = mock(MarketService.class);
        finder = new ShopFinderServiceImpl(plugin, market);
    }

    private FindState state(String world) {
        return new FindState(item(Material.DIAMOND, 1), "Diamond", "Diamond",
                world, 0, 64, 0, true);
    }

    private ShopResult row(String world, int x) {
        return new ShopResult(
                world, UUID.randomUUID(), x, 64, 0, false,
                "PERSONAL", null, UUID.randomUUID(), "Notch",
                "DIAMOND", "Diamond", "Diamond", false, null,
                BigDecimal.valueOf(5), null, 1,
                10, 20, true, false);
    }

    // ---- available --------------------------------------------------------------

    @Test
    void available_reflectsMarketSearchEnabled() {
        when(market.searchEnabled()).thenReturn(true);
        assertThat(finder.available()).isTrue();
        when(market.searchEnabled()).thenReturn(false);
        assertThat(finder.available()).isFalse();
    }

    // ---- query ------------------------------------------------------------------

    @Test
    void query_returnsEmpty_whenShopQueryApiAbsent() {
        when(market.shopQuery()).thenReturn(null);
        assertThat(finder.query(state("world"))).isEmpty();
    }

    @Test
    void query_mapsRowsThroughPipeline() {
        ShopQueryApi api = mock(ShopQueryApi.class);
        when(market.shopQuery()).thenReturn(api);
        when(api.searchShops(any(ShopSearchQuery.class)))
                .thenReturn(List.of(row("world", 3), row("world", 9)));

        List<FoundShop> result = finder.query(state("world"));
        assertThat(result).hasSize(2);
        assertThat(result).allMatch(s -> s.itemKey().equals("Diamond"));
    }

    // ---- find (async) -----------------------------------------------------------

    @Test
    void find_completesWithResults_offThread() throws Exception {
        ShopQueryApi api = mock(ShopQueryApi.class);
        when(market.shopQuery()).thenReturn(api);
        when(api.searchShops(any(ShopSearchQuery.class))).thenReturn(List.of(row("world", 5)));

        CompletableFuture<List<FoundShop>> future = finder.find(state("world"));
        server.getScheduler().waitAsyncTasksFinished();

        assertThat(future.get()).hasSize(1);
    }

    @Test
    void find_completesExceptionally_whenQueryThrows() {
        when(market.shopQuery()).thenThrow(new IllegalStateException("boom"));

        CompletableFuture<List<FoundShop>> future = finder.find(state("world"));
        server.getScheduler().waitAsyncTasksFinished();

        assertThat(future.isCompletedExceptionally()).isTrue();
        assertThatThrownBy(future::get).isInstanceOf(ExecutionException.class);
    }
}
