package io.paradaux.chestshop.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class PriceUtilTest {

    // ── getExact / getExactBuyPrice / getExactSellPrice ───────────────────────

    @ParameterizedTest
    @CsvSource({
        // text                indicator  expected
        "'b 100',              b,         100",
        "'B 100',              b,         100",   // case-insensitive
        "'100b',               b,         100",   // suffix form
        "'b100',               b,         100",   // no spaces
        "'b 100 : s 50',       b,         100",   // colon-separated; pick buy side
        "'b 100 : s 50',       s,         50",    // pick sell side
        "'b free',             b,         0",     // FREE_TEXT → 0
        "'b 1.5k',             b,         1500",
        "'b 2m',               b,         2000000",
        "'b 1.234',            b,         1.234",
    })
    void getExact_parsesValidPrices(String text, char indicator, String expected) {
        assertThat(PriceUtil.getExact(text, indicator))
                .isEqualByComparingTo(new BigDecimal(expected));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        "100",                 // no indicator
        "s 100",               // wrong indicator (asking for buy)
        "b abc",               // not a number
        "b -1",                // negative — invalid
        "b 1.7976931348623159E309", // larger than MAX
    })
    void getExact_returnsNoPriceForInvalid(String text) {
        assertThat(PriceUtil.getExact(text, 'b')).isEqualByComparingTo(PriceUtil.NO_PRICE);
    }

    @Test
    void getExactBuyPrice_andSellPrice_delegateToCorrectIndicator() {
        assertThat(PriceUtil.getExactBuyPrice("b 50 : s 25"))
                .isEqualByComparingTo(new BigDecimal("50"));
        assertThat(PriceUtil.getExactSellPrice("b 50 : s 25"))
                .isEqualByComparingTo(new BigDecimal("25"));
    }

    // ── hasPrice / hasBuyPrice / hasSellPrice ─────────────────────────────────

    @Test
    void hasBuyPrice_returnsTrueWhenPresent() {
        assertThat(PriceUtil.hasBuyPrice("b 10")).isTrue();
        assertThat(PriceUtil.hasBuyPrice("s 10")).isFalse();
    }

    @Test
    void hasSellPrice_returnsTrueWhenPresent() {
        assertThat(PriceUtil.hasSellPrice("s 10")).isTrue();
        assertThat(PriceUtil.hasSellPrice("b 10")).isFalse();
    }

    @Test
    void hasBuyPrice_treatsZeroBuyAsValid() {
        // "b free" → 0 → still a valid price (≠ NO_PRICE = -1).
        assertThat(PriceUtil.hasBuyPrice("b free")).isTrue();
    }

    // ── isPrice ───────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"0", "100", "1.5", "free", "FREE", "1k", "2m", "1.5k"})
    void isPrice_acceptsValidPriceText(String text) {
        assertThat(PriceUtil.isPrice(text)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "-1", "1.0.0", "  ", ""})
    void isPrice_rejectsInvalid(String text) {
        assertThat(PriceUtil.isPrice(text)).isFalse();
    }

    // ── hasSingleMultiplier ──────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"100", "1k", "2m", "1.5K", "2.5M"})
    void hasSingleMultiplier_returnsTrueForOneOrZeroMultipliers(String text) {
        assertThat(PriceUtil.hasSingleMultiplier(text)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1km", "2mk"})
    void hasSingleMultiplier_returnsFalseForMultipleDistinctMultipliers(String text) {
        assertThat(PriceUtil.hasSingleMultiplier(text)).isFalse();
    }
}
