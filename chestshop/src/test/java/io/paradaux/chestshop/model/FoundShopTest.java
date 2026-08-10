package io.paradaux.chestshop.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-data tests for the {@link FoundShop} record: the canonical constructor / accessors and
 * every derived getter (shop type, price/unit-price nullability, sort keys, distance,
 * similarity).
 *
 * <p>{@link FoundShop#from(io.paradaux.treasury.api.market.ShopResult, String, int, int, int,
 * boolean)} is intentionally NOT exercised here: its {@code ShopResult} parameter comes from
 * {@code treasury-api}, which is a {@code compileOnly} dependency and is absent from the test
 * runtime classpath, so the class cannot be loaded in the test JVM (referencing it fails test
 * discovery with {@code NoClassDefFoundError}). See the sibling tests for the derived logic.
 */
class FoundShopTest {

    private static FoundShop shop(BigDecimal buy, BigDecimal sell, int batchQty,
                                  Integer stock, Integer capacity, long distanceSquared) {
        return new FoundShop("stone", "Stone", "STONE", false, null, "Steve",
                buy, sell, batchQty, stock, capacity, "world", 1, 2, 3, distanceSquared);
    }

    @Test
    void canonicalConstructor_exposesComponents() {
        FoundShop s = shop(BigDecimal.ONE, null, 4, 5, 6, 25);
        assertThat(s.itemKey()).isEqualTo("stone");
        assertThat(s.itemName()).isEqualTo("Stone");
        assertThat(s.material()).isEqualTo("STONE");
        assertThat(s.ownerName()).isEqualTo("Steve");
        assertThat(s.world()).isEqualTo("world");
        assertThat(s.x()).isEqualTo(1);
        assertThat(s.y()).isEqualTo(2);
        assertThat(s.z()).isEqualTo(3);
        assertThat(s.batchQty()).isEqualTo(4);
        assertThat(s.distanceSquared()).isEqualTo(25L);
    }

    // ── shopType ───────────────────────────────────────────────────────────────

    @Test
    void shopType_bothPricesPresent_isBoth() {
        assertThat(shop(BigDecimal.ONE, BigDecimal.TEN, 1, 1, 1, 0).shopType()).isEqualTo(ShopType.BOTH);
    }

    @Test
    void shopType_onlySell_isSell() {
        assertThat(shop(null, BigDecimal.TEN, 1, 1, 1, 0).shopType()).isEqualTo(ShopType.SELL);
    }

    @Test
    void shopType_onlyBuyOrNeither_isBuy() {
        assertThat(shop(BigDecimal.ONE, null, 1, 1, 1, 0).shopType()).isEqualTo(ShopType.BUY);
        assertThat(shop(null, null, 1, 1, 1, 0).shopType()).isEqualTo(ShopType.BUY);
    }

    // ── price value getters ──────────────────────────────────────────────────────

    @Test
    void priceValues_nullWhenAbsent_valueWhenPresent() {
        FoundShop absent = shop(null, null, 1, 1, 1, 0);
        assertThat(absent.buyPriceValue()).isNull();
        assertThat(absent.sellPriceValue()).isNull();

        FoundShop present = shop(new BigDecimal("2.5"), new BigDecimal("7.5"), 1, 1, 1, 0);
        assertThat(present.buyPriceValue()).isEqualTo(2.5);
        assertThat(present.sellPriceValue()).isEqualTo(7.5);
    }

    @Test
    void unitPrices_nullWhenAbsent_dividedByBatchWhenPresent() {
        FoundShop absent = shop(null, null, 4, 1, 1, 0);
        assertThat(absent.unitBuyPrice()).isNull();
        assertThat(absent.unitSellPrice()).isNull();

        FoundShop present = shop(new BigDecimal("8"), new BigDecimal("16"), 4, 1, 1, 0);
        assertThat(present.unitBuyPrice()).isEqualTo(2.0);
        assertThat(present.unitSellPrice()).isEqualTo(4.0);
    }

    // ── sort keys ────────────────────────────────────────────────────────────────

    @Test
    void stockAndCapacityForSort_nullSortsAsUnbounded() {
        FoundShop admin = shop(BigDecimal.ONE, null, 1, null, null, 0);
        assertThat(admin.stockForSort()).isEqualTo(Long.MAX_VALUE);
        assertThat(admin.capacityForSort()).isEqualTo(Long.MAX_VALUE);

        FoundShop limited = shop(BigDecimal.ONE, null, 1, 12, 34, 0);
        assertThat(limited.stockForSort()).isEqualTo(12L);
        assertThat(limited.capacityForSort()).isEqualTo(34L);
    }

    // ── distanceBlocks ───────────────────────────────────────────────────────────

    @Test
    void distanceBlocks_unknownReturnsMinusOne_elseFloorSqrt() {
        assertThat(shop(BigDecimal.ONE, null, 1, 1, 1, Long.MAX_VALUE).distanceBlocks()).isEqualTo(-1);
        assertThat(shop(BigDecimal.ONE, null, 1, 1, 1, 50).distanceBlocks()).isEqualTo(7); // floor(sqrt(50))
    }

    // ── isSimilarTo ──────────────────────────────────────────────────────────────

    @Test
    void isSimilarTo_nullQuery_isFalse() {
        assertThat(shop(BigDecimal.ONE, null, 1, 1, 1, 0).isSimilarTo(null)).isFalse();
    }

    @Test
    void isSimilarTo_sameKey_isFalse() {
        assertThat(shop(BigDecimal.ONE, null, 1, 1, 1, 0).isSimilarTo("stone")).isFalse();
    }

    @Test
    void isSimilarTo_differentKey_isTrue() {
        assertThat(shop(BigDecimal.ONE, null, 1, 1, 1, 0).isSimilarTo("diamond")).isTrue();
    }
}
