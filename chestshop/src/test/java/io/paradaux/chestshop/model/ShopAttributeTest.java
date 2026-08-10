package io.paradaux.chestshop.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers {@link ShopAttribute}: display names, the ascending base comparator, direction
 * honouring, and every branch of the private {@code nullsLastDouble} tie-breaker exercised
 * through the price comparators.
 */
class ShopAttributeTest {

    private static FoundShop withBuyPrice(BigDecimal buy) {
        return new FoundShop("k", "n", "STONE", false, null, "Steve",
                buy, null, 1, 1, 1, "world", 0, 0, 0, 0);
    }

    private static FoundShop withDistance(long distSq) {
        return new FoundShop("k", "n", "STONE", false, null, "Steve",
                BigDecimal.ONE, null, 1, 1, 1, "world", 0, 0, 0, distSq);
    }

    @Test
    void displayNames_areHumanReadable() {
        assertThat(ShopAttribute.BUY_PRICE.displayName()).isEqualTo("Buy Price");
        assertThat(ShopAttribute.SELL_PRICE.displayName()).isEqualTo("Sell Price");
        assertThat(ShopAttribute.UNIT_BUY_PRICE.displayName()).isEqualTo("Unit Buy Price");
        assertThat(ShopAttribute.UNIT_SELL_PRICE.displayName()).isEqualTo("Unit Sell Price");
        assertThat(ShopAttribute.STOCK.displayName()).isEqualTo("Stock");
        assertThat(ShopAttribute.REMAINING_CAPACITY.displayName()).isEqualTo("Remaining Capacity");
        assertThat(ShopAttribute.QUANTITY.displayName()).isEqualTo("Quantity");
        assertThat(ShopAttribute.DISTANCE.displayName()).isEqualTo("Distance");
    }

    @Test
    void everyAttributeExposesAComparator() {
        for (ShopAttribute attr : ShopAttribute.values()) {
            assertThat(attr.comparator()).isNotNull();
        }
    }

    @Test
    void comparator_ascendingVsDescending_areReversed() {
        Comparator<FoundShop> asc = ShopAttribute.DISTANCE.comparator(SortDirection.ASCENDING);
        Comparator<FoundShop> desc = ShopAttribute.DISTANCE.comparator(SortDirection.DESCENDING);

        FoundShop near = withDistance(1);
        FoundShop far = withDistance(100);

        assertThat(asc.compare(near, far)).isNegative();
        assertThat(desc.compare(near, far)).isPositive();
    }

    @Test
    void comparator_defaultIsAscending() {
        assertThat(ShopAttribute.DISTANCE.comparator().compare(withDistance(1), withDistance(2))).isNegative();
    }

    // ── nullsLastDouble branches, via the BUY_PRICE comparator ──────────────────

    @Test
    void nullsLastDouble_bothNull_areEqual() {
        Comparator<FoundShop> cmp = ShopAttribute.BUY_PRICE.comparator();
        assertThat(cmp.compare(withBuyPrice(null), withBuyPrice(null))).isZero();
    }

    @Test
    void nullsLastDouble_firstNull_sortsAfter() {
        Comparator<FoundShop> cmp = ShopAttribute.BUY_PRICE.comparator();
        assertThat(cmp.compare(withBuyPrice(null), withBuyPrice(BigDecimal.ONE))).isPositive();
    }

    @Test
    void nullsLastDouble_secondNull_sortsBefore() {
        Comparator<FoundShop> cmp = ShopAttribute.BUY_PRICE.comparator();
        assertThat(cmp.compare(withBuyPrice(BigDecimal.ONE), withBuyPrice(null))).isNegative();
    }

    @Test
    void nullsLastDouble_bothPresent_comparesNumerically() {
        Comparator<FoundShop> cmp = ShopAttribute.BUY_PRICE.comparator();
        assertThat(cmp.compare(withBuyPrice(new BigDecimal("1")), withBuyPrice(new BigDecimal("2")))).isNegative();
        assertThat(cmp.compare(withBuyPrice(new BigDecimal("2")), withBuyPrice(new BigDecimal("2")))).isZero();
    }
}
