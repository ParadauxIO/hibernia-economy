package io.paradaux.treasuryrestapi.service;

import io.paradaux.treasuryrestapi.dto.ChestShopItemDetailResponse;
import io.paradaux.treasuryrestapi.dto.ChestShopItemsResponse;
import io.paradaux.treasuryrestapi.dto.ChestShopMarketStatsResponse;
import io.paradaux.treasuryrestapi.dto.ChestShopShopResponse;
import io.paradaux.treasuryrestapi.dto.ChestShopShopsResponse;
import io.paradaux.treasuryrestapi.exception.ApiException;
import io.paradaux.treasuryrestapi.testsupport.EmbeddedDbIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Through-DAL integration tests for {@link ChestShopService} against the embedded
 * MariaDB: real {@code chestshop_sale}/{@code chestshop_shop} SQL (GROUP BY,
 * DATE_FORMAT, the in-stock/cheapest ORDER BY, the nullable-filter idiom), the
 * windowed item stats, pagination math, and every validation guard.
 */
class ChestShopServiceIT extends EmbeddedDbIT {

    @Autowired private ChestShopService service;
    @Autowired private CacheManager cacheManager;

    private static final UUID CUST = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SELLER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }

    @BeforeEach
    void clearCaches() {
        // @Cacheable results would otherwise survive truncateAll() and leak between tests.
        cacheManager.getCacheNames().forEach(n -> {
            var c = cacheManager.getCache(n);
            if (c != null) c.clear();
        });
    }

    // ── shops directory ───────────────────────────────────────────────────────────

    @Test
    void listShops_returnsActiveShops_inStockBeforeSoldOut_thenCheapest() {
        // sold-out (stock 0), pricey in-stock, cheap in-stock — expect cheap, pricey, sold-out.
        insertShop("world", 1, 64, 1, false, "PERSONAL", null, SELLER, "DIRT", "minecraft:dirt", "Dirt",
                "10.00", null, 64, 0, true);
        insertShop("world", 2, 64, 2, false, "PERSONAL", null, SELLER, "DIRT", "minecraft:dirt", "Dirt",
                "5.00", null, 64, 100, true);
        insertShop("world", 3, 64, 3, false, "PERSONAL", null, SELLER, "DIRT", "minecraft:dirt", "Dirt",
                "1.00", null, 64, 100, true);
        // an inactive shop must never appear
        insertShop("world", 9, 64, 9, false, "PERSONAL", null, SELLER, "DIRT", "minecraft:dirt", "Dirt",
                "0.01", null, 64, 100, false);

        ChestShopShopsResponse resp = service.listShops(null, null, null, false, false, null, 1, 50);

        assertThat(resp.totalItems()).isEqualTo(3);
        assertThat(resp.items()).extracting(ChestShopShopResponse::buyPrice)
                .containsExactly("1.00", "5.00", "10.00");
    }

    @Test
    void listShops_filtersAndResolvesOwnerNames() {
        insertFirm(7, "Acme");
        insertPlayer(SELLER, "Steve");
        insertShop("world", 1, 64, 1, false, "BUSINESS", 7, null, "DIAMOND", "minecraft:diamond", "Diamond",
                "100.00", null, 1, 50, true);
        insertShop("world", 2, 64, 2, false, "PERSONAL", null, SELLER, "DIRT", "minecraft:dirt", "Dirt",
                "2.00", null, 64, 50, true);

        // firmId filter → firm shop, name resolved from firm.display_name
        ChestShopShopsResponse byFirm = service.listShops(null, null, 7, false, false, null, 1, 50);
        assertThat(byFirm.items()).singleElement()
                .satisfies(s -> {
                    assertThat(s.firmId()).isEqualTo(7);
                    assertThat(s.ownerName()).isEqualTo("Acme");
                    assertThat(s.itemKey()).isEqualTo("minecraft:diamond");
                });

        // itemKey filter → personal shop, name resolved from economy_players IGN cache
        ChestShopShopsResponse byItem = service.listShops("minecraft:dirt", null, null, false, false, null, 1, 50);
        assertThat(byItem.items()).singleElement()
                .satisfies(s -> {
                    assertThat(s.ownerName()).isEqualTo("Steve");
                    assertThat(s.ownerUuid()).isEqualTo(SELLER.toString());
                });

        // material filter
        assertThat(service.listShops(null, "DIAMOND", null, false, false, null, 1, 50).totalItems()).isEqualTo(1);
        // search on item name
        assertThat(service.listShops(null, null, null, false, false, "dia", 1, 50).totalItems()).isEqualTo(1);
    }

    @Test
    void listShops_inStockExcludesSoldOut_buyableExcludesSellOnly() {
        insertShop("world", 1, 64, 1, false, "PERSONAL", null, SELLER, "DIRT", "minecraft:dirt", "Dirt",
                "5.00", null, 64, 0, true);         // sold out
        insertShop("world", 2, 64, 2, false, "PERSONAL", null, SELLER, "DIRT", "minecraft:dirt", "Dirt",
                null, "3.00", 64, 100, true);       // sell-only (no buy price)
        insertShop("world", 3, 64, 3, true, "SYSTEM", null, null, "DIRT", "minecraft:dirt", "Dirt",
                "5.00", null, 64, null, true);      // admin/infinite — always in stock

        assertThat(service.listShops(null, null, null, false, true, null, 1, 50).totalItems())
                .as("inStock keeps the admin shop and the stocked sell-only one, drops the sold-out")
                .isEqualTo(2);
        assertThat(service.listShops(null, null, null, true, false, null, 1, 50).totalItems())
                .as("buyable keeps only shops that offer a buy price")
                .isEqualTo(2);
    }

    @Test
    void listShops_paginates() {
        for (int i = 0; i < 5; i++) {
            insertShop("world", i, 64, i, false, "PERSONAL", null, SELLER, "DIRT", "minecraft:dirt", "Dirt",
                    String.valueOf(i + 1) + ".00", null, 64, 100, true);
        }
        ChestShopShopsResponse p1 = service.listShops(null, null, null, false, false, null, 1, 2);
        assertThat(p1.totalItems()).isEqualTo(5);
        assertThat(p1.totalPages()).isEqualTo(3);
        assertThat(p1.items()).hasSize(2);
        ChestShopShopsResponse p3 = service.listShops(null, null, null, false, false, null, 3, 2);
        assertThat(p3.items()).hasSize(1);
    }

    // ── items directory ─────────────────────────────────────────────────────────

    @Test
    void listItems_aggregatesByItemKey_orderedByTradeCount() {
        // dirt: 2 sales (qty 5 @ 10, qty 3 @ 6) ; diamond: 1 sale
        insertSale("BUY", CUST, "PERSONAL", null, SELLER, false, "DIRT", "minecraft:dirt", "Dirt", 5, "50.00", now());
        insertSale("BUY", CUST, "PERSONAL", null, SELLER, false, "DIRT", "minecraft:dirt", "Dirt", 3, "18.00", now());
        insertSale("BUY", CUST, "BUSINESS", 7, null, false, "DIAMOND", "minecraft:diamond", "Diamond", 1, "100.00", now());

        ChestShopItemsResponse resp = service.listItems(null, 1, 50);
        assertThat(resp.totalItems()).isEqualTo(2);
        assertThat(resp.items().get(0).itemKey()).isEqualTo("minecraft:dirt");
        assertThat(resp.items().get(0).tradeCount()).isEqualTo(2);
        assertThat(resp.items().get(0).totalQuantity()).isEqualTo(8);
        assertThat(resp.items().get(0).totalVolume()).isEqualTo("68.00");
    }

    @Test
    void listItems_searchFiltersDistinctItems() {
        insertSale("BUY", CUST, "PERSONAL", null, SELLER, false, "DIRT", "minecraft:dirt", "Dirt", 1, "1.00", now());
        insertSale("BUY", CUST, "PERSONAL", null, SELLER, false, "DIAMOND", "minecraft:diamond", "Diamond", 1, "1.00", now());

        ChestShopItemsResponse resp = service.listItems("diamond", 1, 50);
        assertThat(resp.totalItems()).isEqualTo(1);
        assertThat(resp.items()).singleElement()
                .satisfies(i -> assertThat(i.itemKey()).isEqualTo("minecraft:diamond"));
    }

    // ── item detail ───────────────────────────────────────────────────────────────

    @Test
    void getItem_windowedStats_excludeOldSales_andSurfaceCheapestShops() {
        insertSale("BUY", CUST, "PERSONAL", null, SELLER, false, "DIRT", "minecraft:dirt", "Dirt", 10, "100.00", now().minusDays(2));
        insertSale("BUY", CUST, "PERSONAL", null, SELLER, false, "DIRT", "minecraft:dirt", "Dirt", 10, "100.00", now().minusDays(40)); // outside 30d
        insertShop("world", 1, 64, 1, false, "PERSONAL", null, SELLER, "DIRT", "minecraft:dirt", "Dirt", "9.00", null, 64, 100, true);
        insertShop("world", 2, 64, 2, false, "PERSONAL", null, SELLER, "DIRT", "minecraft:dirt", "Dirt", "7.00", null, 64, 100, true);

        ChestShopItemDetailResponse d = service.getItem("minecraft:dirt", 30);
        assertThat(d.itemKey()).isEqualTo("minecraft:dirt");
        assertThat(d.tradeCount()).as("only the 2-day-old sale is in the 30d window").isEqualTo(1);
        assertThat(d.totalQuantity()).isEqualTo(10);
        assertThat(d.totalVolume()).isEqualTo("100.00");
        assertThat(d.avgUnitPrice()).isEqualTo("10.0000");
        assertThat(d.activeShopCount()).isEqualTo(2);
        assertThat(d.cheapestShops()).extracting(ChestShopShopResponse::buyPrice).containsExactly("7.00", "9.00");
        assertThat(d.priceByDay()).isNotEmpty();
    }

    @Test
    void getItem_foundViaLiveShopEvenWithNoSales() {
        insertShop("world", 1, 64, 1, false, "PERSONAL", null, SELLER, "DIRT", "minecraft:dirt", "Dirt", "5.00", null, 64, 100, true);
        ChestShopItemDetailResponse d = service.getItem("minecraft:dirt", 30);
        assertThat(d.tradeCount()).isZero();
        assertThat(d.activeShopCount()).isEqualTo(1);
        assertThat(d.avgUnitPrice()).isEqualTo("0");
    }

    @Test
    void getItem_unknownItem_is404() {
        ApiException ex = catchThrowableOfType(ApiException.class, () -> service.getItem("minecraft:nope", 30));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.getErrorCode()).isEqualTo("ITEM_NOT_FOUND");
    }

    // ── global stats ────────────────────────────────────────────────────────────

    @Test
    void marketStats_totalsAndActiveShops() {
        insertSale("BUY", CUST, "PERSONAL", null, SELLER, false, "DIRT", "minecraft:dirt", "Dirt", 5, "50.00", now());
        insertSale("BUY", CUST, "PERSONAL", null, SELLER, false, "DIAMOND", "minecraft:diamond", "Diamond", 1, "100.00", now());
        insertShop("world", 1, 64, 1, false, "PERSONAL", null, SELLER, "DIRT", "minecraft:dirt", "Dirt", "5.00", null, 64, 100, true);
        insertShop("world", 2, 64, 2, false, "PERSONAL", null, SELLER, "DIRT", "minecraft:dirt", "Dirt", "5.00", null, 64, 100, false); // inactive

        ChestShopMarketStatsResponse s = service.marketStats();
        assertThat(s.totalSales()).isEqualTo(2);
        assertThat(s.totalVolume()).isEqualTo("150.00");
        assertThat(s.distinctItems()).isEqualTo(2);
        assertThat(s.activeShops()).isEqualTo(1);
    }

    @Test
    void marketStats_emptyMarketIsZeros() {
        ChestShopMarketStatsResponse s = service.marketStats();
        assertThat(s.totalSales()).isZero();
        assertThat(s.totalVolume()).isEqualTo("0.00");
        assertThat(s.distinctItems()).isZero();
        assertThat(s.activeShops()).isZero();
    }

    // ── validation ────────────────────────────────────────────────────────────────

    @Test
    void invalidLimit_rejected() {
        assertBadParam(() -> service.listShops(null, null, null, false, false, null, 1, 0));
        assertBadParam(() -> service.listShops(null, null, null, false, false, null, 1, 101));
        assertBadParam(() -> service.listItems(null, 1, 101));
    }

    @Test
    void invalidPage_rejected() {
        assertBadParam(() -> service.listShops(null, null, null, false, false, null, 0, 50));
        assertBadParam(() -> service.listItems(null, 0, 50));
    }

    @Test
    void invalidDays_rejected() {
        assertBadParam(() -> service.getItem("minecraft:dirt", 0));
        assertBadParam(() -> service.getItem("minecraft:dirt", 366));
    }

    @Test
    void tooShortSearch_rejected() {
        assertBadParam(() -> service.listItems("a", 1, 50));
        assertBadParam(() -> service.listShops(null, null, null, false, false, "a", 1, 50));
    }

    private void assertBadParam(org.junit.jupiter.api.function.Executable call) {
        ApiException ex = catchThrowableOfType(ApiException.class, call::execute);
        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getErrorCode()).isEqualTo("INVALID_PARAM");
    }
}
