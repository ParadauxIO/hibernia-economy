package io.paradaux.chestshop.services.impl;

import io.paradaux.chestshop.services.PartialFillCalculator;

import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.MathContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Exact-arithmetic coverage for the partial-fulfilment money maths in
 * {@link PartialFillCalculator} (ADT-138): affordability (FLOOR to whole items),
 * price scaling (HALF_UP to the configured {@code PRICE_PRECISION}) and the
 * rounded-to-zero affordability guard. These are precisely the steps where a
 * scale/rounding/off-by-one slip would let a buyer underpay or an owner be
 * overpaid on a partial fill, and they previously had no direct coverage.
 */
class TransactionMathTest {

    private PartialFillCalculatorImpl serviceWithPrecision(int precision) {
        ChestShopConfiguration config = mock(ChestShopConfiguration.class);
        when(config.getPricePrecision()).thenReturn(precision);
        // Only config is consulted by scalePrice; every other collaborator is unused here.
        return new PartialFillCalculatorImpl(null, null, null, config);
    }

    // ── getAmountOfAffordableItems: floor(wallet / pricePerItem) ───────────────

    @Test
    void affordableItems_floorsToWholeItems() {
        assertThat(PartialFillCalculatorImpl.getAmountOfAffordableItems(new BigDecimal("10.00"), new BigDecimal("3.00"), 64)).isEqualTo(3); // 3.33 -> 3
        assertThat(PartialFillCalculatorImpl.getAmountOfAffordableItems(new BigDecimal("9.00"), new BigDecimal("3.00"), 64)).isEqualTo(3);  // exact
    }

    @Test
    void affordableItems_isZeroWhenCannotAffordEvenOne() {
        assertThat(PartialFillCalculatorImpl.getAmountOfAffordableItems(new BigDecimal("2.99"), new BigDecimal("3.00"), 64)).isEqualTo(0);
        assertThat(PartialFillCalculatorImpl.getAmountOfAffordableItems(BigDecimal.ZERO, new BigDecimal("3.00"), 64)).isEqualTo(0);
    }

    @Test
    void affordableItems_handlesNonTerminatingPerItemPrice() {
        // pricePerItem = 10 / 3 = 3.333...; with 10 in the wallet you can afford 3.
        BigDecimal pricePerItem = new BigDecimal("10.00").divide(new BigDecimal(3), MathContext.DECIMAL128);
        assertThat(PartialFillCalculatorImpl.getAmountOfAffordableItems(new BigDecimal("10.00"), pricePerItem, 64)).isEqualTo(3);
    }

    @Test
    void affordableItems_clampsToCap_notExceedingRequestedCount() {
        // A far larger wallet than the requested count → the result is the cap, never more.
        assertThat(PartialFillCalculatorImpl.getAmountOfAffordableItems(new BigDecimal("1000000"), new BigDecimal("1.00"), 64)).isEqualTo(64);
    }

    @Test
    void affordableItems_doesNotOverflow_whenQuotientExceedsIntMax() {
        // chestshop/behaviour/0003: an unbounded wallet (Double.MAX_VALUE) over a tiny per-item
        // price yields a quotient far beyond Integer.MAX_VALUE. intValueExact() would throw and
        // abort the trade; the cap must bound it instead.
        assertThat(PartialFillCalculatorImpl.getAmountOfAffordableItems(
                BigDecimal.valueOf(Double.MAX_VALUE), new BigDecimal("0.0001"), 10)).isEqualTo(10);
    }

    // ── scalePrice: pricePerItem * count, rounded HALF_UP to PRICE_PRECISION ────

    @Test
    void scalePrice_roundsHalfUpToConfiguredScale() {
        PartialFillCalculatorImpl service = serviceWithPrecision(2);
        // 0.125 * 1 = 0.125 -> HALF_UP at 2dp -> 0.13
        BigDecimal scaled = service.scalePrice(new BigDecimal("0.125"), 1);
        assertThat(scaled).isEqualByComparingTo("0.13");
        assertThat(scaled.scale()).isEqualTo(2);
    }

    @Test
    void scalePrice_recombinesNonTerminatingPerItemPriceWithoutDrift() {
        PartialFillCalculatorImpl service = serviceWithPrecision(2);
        BigDecimal pricePerItem = new BigDecimal("10.00").divide(new BigDecimal(3), MathContext.DECIMAL128); // 3.333...
        // 3.333... * 3 = 9.999... -> HALF_UP at 2dp -> 10.00 (not 9.99)
        BigDecimal scaled = service.scalePrice(pricePerItem, 3);
        assertThat(scaled).isEqualByComparingTo("10.00");
        assertThat(scaled.scale()).isEqualTo(2);
    }

    @Test
    void scalePrice_honoursAHigherConfiguredPrecision() {
        PartialFillCalculatorImpl service = serviceWithPrecision(4);
        assertThat(service.scalePrice(new BigDecimal("0.12345"), 1)).isEqualByComparingTo("0.1235"); // HALF_UP at 4dp
    }

    // ── roundedToZero: a positive per-item price that scales to nothing ─────────

    @Test
    void roundedToZero_trueOnlyWhenPositivePriceScalesToZero() {
        assertThat(PartialFillCalculatorImpl.roundedToZero(new BigDecimal("0.001"), new BigDecimal("0.00"))).isTrue();
    }

    @Test
    void roundedToZero_falseForAFreeShop() {
        // pricePerItem 0 (free shop) is not "rounded to zero" — it is genuinely free.
        assertThat(PartialFillCalculatorImpl.roundedToZero(BigDecimal.ZERO, BigDecimal.ZERO)).isFalse();
    }

    @Test
    void roundedToZero_falseWhenScaledIsPositive() {
        assertThat(PartialFillCalculatorImpl.roundedToZero(new BigDecimal("0.01"), new BigDecimal("0.01"))).isFalse();
    }
}
