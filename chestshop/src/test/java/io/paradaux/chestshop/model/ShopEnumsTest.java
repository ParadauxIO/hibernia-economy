package io.paradaux.chestshop.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Covers the small {@link ShopType} and {@link SortDirection} enums. */
class ShopEnumsTest {

    @Test
    void shopType_displayNames() {
        assertThat(ShopType.BUY.displayName()).isEqualTo("Buy");
        assertThat(ShopType.SELL.displayName()).isEqualTo("Sell");
        assertThat(ShopType.BOTH.displayName()).isEqualTo("Buy & Sell");
    }

    @Test
    void shopType_hasExactlyThreeValues() {
        assertThat(ShopType.values()).containsExactly(ShopType.BUY, ShopType.SELL, ShopType.BOTH);
        assertThat(ShopType.valueOf("BOTH")).isEqualTo(ShopType.BOTH);
    }

    @Test
    void sortDirection_hasBothDirections() {
        assertThat(SortDirection.values()).containsExactly(SortDirection.ASCENDING, SortDirection.DESCENDING);
        assertThat(SortDirection.valueOf("ASCENDING")).isEqualTo(SortDirection.ASCENDING);
    }
}
