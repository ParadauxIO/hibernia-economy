package io.paradaux.treasury.utils;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void normalize_roundsHalfEvenToTwoDecimals() {
        assertThat(Money.normalize(new BigDecimal("1.005"))).isEqualByComparingTo("1.00");
        assertThat(Money.normalize(new BigDecimal("1.015"))).isEqualByComparingTo("1.02");
        assertThat(Money.normalize(new BigDecimal("1.235"))).isEqualByComparingTo("1.24");
        assertThat(Money.normalize(new BigDecimal("1.245"))).isEqualByComparingTo("1.24");
    }

    @Test
    void normalize_preservesAlreadyNormalized() {
        assertThat(Money.normalize(new BigDecimal("100.00"))).isEqualByComparingTo("100.00");
        assertThat(Money.normalize(new BigDecimal("0.01"))).isEqualByComparingTo("0.01");
    }

    @Test
    void requirePositive_acceptsPositiveAmounts_fromMinimumToLedgerMaximum() {
        assertThatCode(() -> Money.requirePositive(new BigDecimal("0.01"), "should pass")).doesNotThrowAnyException();
        assertThatCode(() -> Money.requirePositive(new BigDecimal("1.00"), "should pass")).doesNotThrowAnyException();
        assertThatCode(() -> Money.requirePositive(Money.MAXIMUM_AMOUNT, "should pass")).doesNotThrowAnyException();
    }

    @Test
    void requireValidAmount_rejectsAmountsBeyondTheLedgerColumn() {
        // DECIMAL(19,2): one cent over the max, and an exponent that would take
        // minutes to normalize, are both rejected up front.
        assertThatThrownBy(() -> Money.requireValidAmount(new BigDecimal("100000000000000000.00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Amount cannot exceed 99999999999999999.99");
        assertThatThrownBy(() -> Money.requirePositive(new BigDecimal("1e999999999"), "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot exceed");
    }

    @Test
    void requirePositive_rejectsZero() {
        assertThatThrownBy(() -> Money.requirePositive(BigDecimal.ZERO, "no zero"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("no zero");
    }

    @Test
    void requirePositive_rejectsNegative() {
        assertThatThrownBy(() -> Money.requirePositive(new BigDecimal("-0.01"), "no negative"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("no negative");
    }

    @Test
    void requirePositive_rejectsNull() {
        assertThatThrownBy(() -> Money.requirePositive(null, "no null"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("no null");
    }

    @Test
    void requirePositive_rejectsBelowMinimum() {
        assertThatThrownBy(() -> Money.requirePositive(new BigDecimal("0.001"), "below"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Amount cannot have more than");
    }

    @Test
    void requireValidAmount_rejectsExtraDecimalPlaces() {
        assertThatThrownBy(() -> Money.requireValidAmount(new BigDecimal("1.234")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more than 2 decimal places");
    }

    @Test
    void requireValidAmount_rejectsAmountBelowMinimum() {
        assertThatThrownBy(() -> Money.requireValidAmount(new BigDecimal("0.00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least " + Money.MINIMUM_AMOUNT);
    }

    @Test
    void requireValidAmount_acceptsTrailingZerosBeyondTwoDecimals() {
        // stripTrailingZeros gives scale ≤ 2 here, so this should pass.
        assertThatCode(() -> Money.requireValidAmount(new BigDecimal("1.10"))).doesNotThrowAnyException();
        assertThatCode(() -> Money.requireValidAmount(new BigDecimal("100.000"))).doesNotThrowAnyException();
        assertThatCode(() -> Money.requireValidScale(new BigDecimal("0.000"))).doesNotThrowAnyException();
    }
}
