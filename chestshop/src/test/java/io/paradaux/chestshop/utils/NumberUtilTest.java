package io.paradaux.chestshop.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class NumberUtilTest {

    @ParameterizedTest
    @ValueSource(strings = {"0", "1", "-1", "2147483647", "-2147483648"})
    void isInteger_acceptsValidInts(String s) {
        assertThat(NumberUtil.isInteger(s)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "1.0", "1e10", "abc", "2147483648", "-2147483649", "1 "})
    void isInteger_rejectsNonInts(String s) {
        assertThat(NumberUtil.isInteger(s)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
        "0,    '00:00'",
        "59,   '00:59'",
        "60,   '01:00'",
        "3599, '59:59'",
        "3600, '00:00'",   // wraps within hour as documented (mm:ss)
        "3661, '01:01'",
    })
    void toTime_formatsAsMinutesAndSeconds(int seconds, String expected) {
        assertThat(NumberUtil.toTime(seconds)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
        "1, I", "2, II", "3, III", "4, IV", "5, V",
        "6, VI", "7, VII", "8, VIII", "9, IX",
    })
    void toRoman_returnsRomanForOneThroughNine(int n, String expected) {
        assertThat(NumberUtil.toRoman(n)).isEqualTo(expected);
    }

    @Test
    void toRoman_fallsBackToDecimalOutsideRange() {
        // The doc says it only handles 1-9 (enchantment levels).
        assertThat(NumberUtil.toRoman(0)).isEqualTo("0");
        assertThat(NumberUtil.toRoman(10)).isEqualTo("10");
        assertThat(NumberUtil.toRoman(-3)).isEqualTo("-3");
    }

    @Test
    void toInt_clampsLongOverflowToMaxValue() {
        assertThat(NumberUtil.toInt(123L)).isEqualTo(123);
        assertThat(NumberUtil.toInt((long) Integer.MAX_VALUE)).isEqualTo(Integer.MAX_VALUE);
        assertThat(NumberUtil.toInt((long) Integer.MAX_VALUE + 1)).isEqualTo(Integer.MAX_VALUE);
        assertThat(NumberUtil.toInt(Long.MAX_VALUE)).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void toInt_passesNegativeLongsThroughWithoutClamp() {
        // The cast is unsigned-to-int; negative longs that fit in int round-trip.
        assertThat(NumberUtil.toInt(-1L)).isEqualTo(-1);
        assertThat(NumberUtil.toInt((long) Integer.MIN_VALUE)).isEqualTo(Integer.MIN_VALUE);
    }
}
