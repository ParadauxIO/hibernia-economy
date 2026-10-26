package io.paradaux.chestshop.dialogs;

import static org.assertj.core.api.Assertions.assertThat;

import io.paradaux.chestshop.model.FoundShop;
import io.paradaux.chestshop.model.ShopAttribute;
import io.paradaux.chestshop.model.ShopType;
import io.paradaux.chestshop.model.SortDirection;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@code /find} in-memory pipeline + model — the part that
 * runs without a server: shop-type filtering, hide-empty/full, weighted multi-key
 * sort, distance, presets and paging.
 */
class FindStateTest {

    private static FindState stateAt(int x, int y, int z) {
        return new FindState(null, "DIAMOND", "Diamond", "world", x, y, z, true);
    }

    /** Build a shop; nulls mean "not offered / admin-infinite". */
    private static FoundShop shop(
            BigDecimal buy, BigDecimal sell, Integer stock, Integer capacity, int x, int y, int z) {
        return new FoundShop(
                "DIAMOND", "Diamond", "DIAMOND", false, null, "Steve", buy, sell, 1, stock, capacity, "world", x, y, z,
                0);
    }

    private static BigDecimal money(String s) {
        return new BigDecimal(s);
    }

    // ── shop-type filter ────────────────────────────────────────────────────────

    @Test
    void shopTypeFilter_keepsOnlySelectedTypes() {
        FindState state = stateAt(0, 0, 0);
        FoundShop buyOnly = shop(money("5.00"), null, 10, 10, 1, 0, 0);
        FoundShop sellOnly = shop(null, money("3.00"), 10, 10, 2, 0, 0);
        FoundShop both = shop(money("5.00"), money("3.00"), 10, 10, 3, 0, 0);

        state.setShopTypes(List.of(ShopType.BUY, ShopType.BOTH));
        List<FoundShop> result = state.pipeline(List.of(buyOnly, sellOnly, both));

        assertThat(result).containsExactlyInAnyOrder(buyOnly, both);
    }

    @Test
    void emptyShopTypes_returnsNothing() {
        FindState state = stateAt(0, 0, 0);
        state.setShopTypes(List.of());
        assertThat(state.pipeline(List.of(shop(money("1.00"), null, 1, 1, 0, 0, 0))))
                .isEmpty();
    }

    // ── hide empty / full ───────────────────────────────────────────────────────

    @Test
    void hideEmpty_dropsZeroStock_keepsNullStock() {
        FindState state = stateAt(0, 0, 0);
        FoundShop empty = shop(money("5.00"), null, 0, 10, 1, 0, 0);
        FoundShop stocked = shop(money("5.00"), null, 7, 10, 2, 0, 0);
        FoundShop admin = shop(money("5.00"), null, null, 10, 3, 0, 0); // infinite stock

        state.setHideEmpty(true);
        assertThat(state.pipeline(List.of(empty, stocked, admin))).containsExactlyInAnyOrder(stocked, admin);
    }

    @Test
    void hideFull_dropsZeroCapacity_keepsNullCapacity() {
        FindState state = stateAt(0, 0, 0);
        FoundShop full = shop(null, money("3.00"), 10, 0, 1, 0, 0);
        FoundShop room = shop(null, money("3.00"), 10, 5, 2, 0, 0);
        FoundShop admin = shop(null, money("3.00"), 10, null, 3, 0, 0); // infinite room

        state.setHideFull(true);
        assertThat(state.pipeline(List.of(full, room, admin))).containsExactlyInAnyOrder(room, admin);
    }

    // ── sorting ─────────────────────────────────────────────────────────────────

    @Test
    void sort_byUnitBuyPriceAscending_cheapestFirst() {
        FindState state = stateAt(0, 0, 0);
        FoundShop dear = shop(money("10.00"), null, 10, 10, 1, 0, 0);
        FoundShop cheap = shop(money("2.00"), null, 10, 10, 2, 0, 0);

        state.select(ShopAttribute.UNIT_BUY_PRICE, SortDirection.ASCENDING, 100);
        List<FoundShop> result = state.pipeline(List.of(dear, cheap));

        assertThat(result).containsExactly(cheap, dear);
    }

    @Test
    void sort_weightDecidesPrimaryKey() {
        FindState state = stateAt(0, 0, 0);
        // Two shops: A cheaper unit price but farther; B nearer but dearer.
        FoundShop a = new FoundShop(
                "DIAMOND",
                "Diamond",
                "DIAMOND",
                false,
                null,
                "A",
                money("1.00"),
                null,
                1,
                10,
                10,
                "world",
                100,
                0,
                0,
                100 * 100);
        FoundShop b = new FoundShop(
                "DIAMOND", "Diamond", "DIAMOND", false, null, "B", money("5.00"), null, 1, 10, 10, "world", 1, 0, 0, 1);

        // Distance is the primary key (higher weight) → nearer B first.
        state.select(ShopAttribute.DISTANCE, SortDirection.ASCENDING, 100);
        state.select(ShopAttribute.UNIT_BUY_PRICE, SortDirection.ASCENDING, 50);
        assertThat(state.pipeline(List.of(a, b))).containsExactly(b, a);

        // Flip: unit price primary → cheaper A first.
        FindState state2 = stateAt(0, 0, 0);
        state2.select(ShopAttribute.UNIT_BUY_PRICE, SortDirection.ASCENDING, 100);
        state2.select(ShopAttribute.DISTANCE, SortDirection.ASCENDING, 50);
        assertThat(state2.pipeline(List.of(a, b))).containsExactly(a, b);
    }

    // ── presets ─────────────────────────────────────────────────────────────────

    @Test
    void presetBuyCheap_setsTypesSortAndHideEmpty() {
        FindState state = stateAt(0, 0, 0);
        state.presetBuyCheap();

        assertThat(state.shopTypes()).containsExactlyInAnyOrder(ShopType.BUY, ShopType.BOTH);
        assertThat(state.hideEmpty()).isTrue();
        assertThat(state.sortMeta(ShopAttribute.UNIT_BUY_PRICE).weight()).isEqualTo(100);
        assertThat(state.sortMeta(ShopAttribute.UNIT_BUY_PRICE).direction()).isEqualTo(SortDirection.ASCENDING);
    }

    @Test
    void presetSellBest_sellTypesDescendingPriceHideFull() {
        FindState state = stateAt(0, 0, 0);
        state.presetSellBest();

        assertThat(state.shopTypes()).containsExactlyInAnyOrder(ShopType.SELL, ShopType.BOTH);
        assertThat(state.hideFull()).isTrue();
        assertThat(state.sortMeta(ShopAttribute.UNIT_SELL_PRICE).direction()).isEqualTo(SortDirection.DESCENDING);
    }

    // ── FoundShop derivations ───────────────────────────────────────────────────

    @Test
    void foundShop_derivesTypeUnitPriceAndDistance() {
        FoundShop both = new FoundShop(
                "k", "n", "m", false, null, "o", money("10.00"), money("6.00"), 4, 100, 50, "world", 3, 4, 0, 25);
        assertThat(both.shopType()).isEqualTo(ShopType.BOTH);
        assertThat(both.unitBuyPrice()).isEqualTo(2.5); // 10 / 4
        assertThat(both.unitSellPrice()).isEqualTo(1.5); // 6 / 4
        assertThat(both.distanceBlocks()).isEqualTo(5); // sqrt(25)

        FoundShop crossWorld = new FoundShop(
                "k", "n", "m", false, null, "o", money("1.00"), null, 1, null, null, "world", 0, 0, 0, Long.MAX_VALUE);
        assertThat(crossWorld.distanceBlocks()).isEqualTo(-1);
        assertThat(crossWorld.stockForSort()).isEqualTo(Long.MAX_VALUE);
        assertThat(crossWorld.shopType()).isEqualTo(ShopType.BUY);
    }
}
