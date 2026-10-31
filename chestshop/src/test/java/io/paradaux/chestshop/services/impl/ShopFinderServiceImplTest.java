package io.paradaux.chestshop.services.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.paradaux.chestshop.dialogs.FindState;
import io.paradaux.chestshop.model.Account;
import io.paradaux.chestshop.model.FoundShop;
import io.paradaux.chestshop.model.ShopType;
import io.paradaux.chestshop.services.AccountService;
import io.paradaux.chestshop.services.EconomyService;
import io.paradaux.chestshop.services.MarketService;
import io.paradaux.chestshop.support.ServerTest;
import io.paradaux.chestshop.utils.BusinessAccountUtil;
import io.paradaux.treasury.api.ShopQueryApi;
import io.paradaux.treasury.api.market.ShopResult;
import io.paradaux.treasury.api.market.ShopSearchQuery;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.bukkit.Material;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

/**
 * {@link ShopFinderServiceImpl}: the off-thread {@code /find} query + in-memory pipeline over a
 * mocked Treasury {@link ShopQueryApi}, on a live MockBukkit plugin/scheduler.
 */
class ShopFinderServiceImplTest extends ServerTest {

    private JavaPlugin plugin;
    private MarketService market;
    private ShopFinderServiceImpl finder;
    private EconomyService economy;
    private AccountService accounts;

    @BeforeEach
    void wire() {
        plugin = MockBukkit.createMockPlugin("FinderPlugin");
        market = mock(MarketService.class);
        economy = mock(EconomyService.class);
        accounts = mock(AccountService.class);
        finder = new ShopFinderServiceImpl(plugin, market, economy, accounts);
    }

    private FindState state(String world) {
        return new FindState(item(Material.DIAMOND, 1), "Diamond", "Diamond", world, 0, 64, 0, true);
    }

    private ShopResult row(String world, int x) {
        return row(world, x, UUID.randomUUID());
    }

    private ShopResult row(String world, int x, UUID worldUuid) {
        return new ShopResult(
                world,
                worldUuid,
                x,
                64,
                0,
                false,
                42,
                "PERSONAL",
                null,
                UUID.randomUUID(),
                "Notch",
                "DIAMOND",
                "Diamond",
                "Diamond",
                false,
                null,
                BigDecimal.valueOf(5),
                null,
                1,
                10,
                20,
                true,
                false);
    }

    private ShopResult offer(int x, Integer accountId, boolean admin, BigDecimal buy, BigDecimal sell) {
        return new ShopResult(
                "world",
                null,
                x,
                64,
                0,
                admin,
                accountId,
                "BUSINESS",
                999,
                null,
                "Firm",
                "DIAMOND",
                "Diamond",
                "Diamond",
                false,
                null,
                buy,
                sell,
                16,
                64,
                64,
                true,
                false);
    }

    private void shops(ShopResult... rows) {
        ShopQueryApi api = mock(ShopQueryApi.class);
        when(market.shopQuery()).thenReturn(api);
        when(api.searchShops(any(ShopSearchQuery.class))).thenReturn(List.of(rows));
    }

    @Test
    void hidesSellOffersBelowBatchPriceButKeepsExactAndHigherBalances() {
        shops(
                offer(1, 41, false, null, new BigDecimal("16.00")),
                offer(2, 42, false, null, new BigDecimal("16.00")),
                offer(3, 43, false, null, new BigDecimal("16.00")));
        when(economy.getBalance(BusinessAccountUtil.toBusinessUuid(41))).thenReturn(new BigDecimal("15.99"));
        when(economy.getBalance(BusinessAccountUtil.toBusinessUuid(42))).thenReturn(new BigDecimal("16.0"));
        when(economy.getBalance(BusinessAccountUtil.toBusinessUuid(43))).thenReturn(new BigDecimal("17"));
        assertThat(finder.query(state("world"))).extracting(FoundShop::x).containsExactlyInAnyOrder(2, 3);
    }

    @Test
    void unfundedBothShopRemainsBuyAndIsExcludedFromSellPreset() {
        shops(offer(1, 42, false, BigDecimal.TEN, BigDecimal.ONE));
        when(economy.getBalance(BusinessAccountUtil.toBusinessUuid(42))).thenReturn(BigDecimal.ZERO);
        assertThat(finder.query(state("world"))).singleElement().satisfies(shop -> {
            assertThat(shop.shopType()).isEqualTo(ShopType.BUY);
            assertThat(shop.buyPrice()).isEqualByComparingTo(BigDecimal.TEN);
            assertThat(shop.sellPrice()).isNull();
        });
        FindState sell = state("world");
        sell.presetSellBest();
        assertThat(finder.query(sell)).isEmpty();
        FindState buy = state("world");
        buy.presetBuyCheap();
        assertThat(finder.query(buy)).hasSize(1);
    }

    @Test
    void buyAndFreeSellOffersDoNotRequireOwnerFunds() {
        shops(offer(1, null, false, BigDecimal.TEN, null), offer(2, null, false, null, BigDecimal.ZERO));
        assertThat(finder.query(state("world"))).extracting(FoundShop::x).containsExactlyInAnyOrder(1, 2);
        verifyNoInteractions(economy);
    }

    @Test
    void paidSellOfferWithUnresolvedAccountIsHidden() {
        shops(offer(1, null, false, null, BigDecimal.TEN));
        assertThat(finder.query(state("world"))).isEmpty();
        verifyNoInteractions(economy);
    }

    @Test
    void unlimitedAdminShopRemainsVisible() {
        shops(offer(1, null, true, null, BigDecimal.TEN));
        assertThat(finder.query(state("world"))).hasSize(1);
        verifyNoInteractions(economy);
    }

    @Test
    void adminShopUsesConfiguredServerAccountBalance() {
        UUID serverOwner = UUID.randomUUID();
        Account serverAccount = mock(Account.class);
        when(serverAccount.getUuid()).thenReturn(serverOwner);
        when(accounts.getServerEconomyAccount()).thenReturn(serverAccount);
        when(economy.getBalance(serverOwner)).thenReturn(BigDecimal.ONE, BigDecimal.TEN);
        shops(offer(1, null, true, null, BigDecimal.TEN));
        assertThat(finder.query(state("world"))).isEmpty();
        assertThat(finder.query(state("world"))).hasSize(1);
    }

    @Test
    void balancesAreSharedWithinSearchAndRefreshedForTheNextSearch() {
        UUID payer = BusinessAccountUtil.toBusinessUuid(42);
        when(economy.getBalance(payer)).thenReturn(BigDecimal.ONE, BigDecimal.TEN);
        shops(offer(1, 42, false, null, BigDecimal.ONE), offer(2, 42, false, null, BigDecimal.TEN));
        assertThat(finder.query(state("world"))).extracting(FoundShop::x).containsExactly(1);
        verify(economy).getBalance(payer);
        assertThat(finder.query(state("world"))).hasSize(2);
        verify(economy, times(2)).getBalance(payer);
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
        when(api.searchShops(any(ShopSearchQuery.class))).thenReturn(List.of(row("world", 3), row("world", 9)));

        List<FoundShop> result = finder.query(state("world"));
        assertThat(result).hasSize(2);
        assertThat(result).allMatch(s -> s.itemKey().equals("Diamond"));
    }

    @Test
    void ranksTheBestShopEvenWhenItFallsBeyondTheFirst500Matches() {
        ShopQueryApi api = mock(ShopQueryApi.class);
        when(market.shopQuery()).thenReturn(api);
        var rows = java.util.stream.IntStream.rangeClosed(0, 500)
                .mapToObj(i -> row("world", 500 - i))
                .toList();
        when(api.searchShops(any(ShopSearchQuery.class))).thenAnswer(inv -> {
            ShopSearchQuery query = inv.getArgument(0);
            return query.isAllMatches() ? rows : rows.subList(0, query.getLimit());
        });
        FindState search = state("world");
        search.presetBuyNearby();

        var results = finder.query(search);

        assertThat(results).hasSize(501);
        assertThat(results.getFirst().x()).isZero();
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

        // Bounded get rather than an immediate isCompletedExceptionally(): under load the
        // async task can still be completing the future when waitAsyncTasksFinished returns.
        assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasRootCauseMessage("boom");
        assertThat(future.isCompletedExceptionally()).isTrue();
    }

    @Test
    void query_excludesPreviousGenerationOfTheSameNamedWorld() {
        UUID current = UUID.randomUUID();
        FindState request =
                new FindState(item(Material.DIAMOND, 1), "Diamond", "Diamond", "world", current, 0, 64, 0, true);
        ShopQueryApi api = mock(ShopQueryApi.class);
        when(market.shopQuery()).thenReturn(api);
        when(api.searchShops(any(ShopSearchQuery.class)))
                .thenReturn(
                        List.of(row("world", 1, current), row("world", 2, UUID.randomUUID()), row("world", 3, null)));
        assertThat(finder.query(request)).extracting(FoundShop::x).containsExactlyInAnyOrder(1, 3);
    }
}
