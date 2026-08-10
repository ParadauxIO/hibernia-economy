package io.paradaux.chestshop.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QuantityUtilTest {

    @ParameterizedTest
    @CsvSource({
        "'1',     1",
        "'64',    64",
        "'30000', 30000",
    })
    void parseQuantity_parsesPlainNumbers(String line, int expected) {
        assertThat(QuantityUtil.parseQuantity(line)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
        "'Q 64 : C 0',     64",
        "'Q 1 : C 12345',  1",
        "'Q 99999 : C 99999', 99999",
    })
    void parseQuantity_extractsQuantityFromCounterLine(String line, int expected) {
        assertThat(QuantityUtil.parseQuantity(line)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "1.5", "Q 64", "C 1"})
    void parseQuantity_throwsForInvalid(String line) {
        assertThatThrownBy(() -> QuantityUtil.parseQuantity(line))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Q 1 : C 0", "Q 64 : C 12", "Q 99999 : C 99999"})
    void quantityLineContainsCounter_truthyOnValidPattern(String line) {
        assertThat(QuantityUtil.quantityLineContainsCounter(line)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "1",                  // plain number
        "Q1:C0",              // missing spaces
        "Q 0 : C 0",          // quantity must be 1-9 leading
        "Q 100000 : C 0",     // quantity max 5 digits
        "Q 64 : C 100000",    // counter max 5 digits
        "q 64 : c 0",         // lower-case markers
    })
    void quantityLineContainsCounter_falsyOnMalformed(String line) {
        assertThat(QuantityUtil.quantityLineContainsCounter(line)).isFalse();
    }
}
