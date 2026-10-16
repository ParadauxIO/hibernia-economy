package io.paradaux.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The money-amount rules every payment/transfer command applies to what the
 * player typed. Inputs are written the way the command framework produces them:
 * the typed word with {@code ,} removed, through {@code new BigDecimal(..)}.
 */
class AmountInputTest {

    /** Mirrors the framework's BigDecimal argument resolver (strip ',' then parse). */
    private static BigDecimal typed(String word) {
        return new BigDecimal(word.replace(",", ""));
    }

    private static void assertAccepted(Optional<BigDecimal> result, String expected) {
        assertTrue(result.isPresent(), "expected " + expected + " to be accepted");
        // equals (not compareTo): the ledger value must come back at scale 2.
        assertEquals(new BigDecimal(expected), result.get());
    }

    @ParameterizedTest
    @CsvSource({
        "0.01, 0.01", // the smallest payable amount
        "1, 1.00",
        "1.5, 1.50",
        "10.000, 10.00", // trailing zeros beyond cents are not sub-cent
        "'1,000.50', 1000.50", // grouping separators are stripped by the framework
        "'1,000,000', 1000000.00",
        "1e3, 1000.00", // scientific notation parses
        "1.5E+2, 150.00",
        "2.5e-1, 0.25",
        "99999999999999999.99, 99999999999999999.99", // DECIMAL(19,2) max
    })
    void positive_acceptsPayableAmounts_atScaleTwo(String word, String expected) {
        assertAccepted(AmountInput.positive(typed(word)), expected);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "0",
                "0.00",
                "-0.00",
                "-1",
                "-0.01",
                "-1e3",
                "0.001",
                "0.004",
                "0.005", // round to 0.00 — never a payment
                "10.005",
                "10.015",
                "0.019", // sub-cent: rejected, not rounded
                "'1.000,50'", // European 1000.50 -> 1.00050 -> rejected
                "1E-3",
                "100000000000000000", // 18 integer digits: over DECIMAL(19,2)
                "99999999999999999.999", // sub-cent at the max
                "1e17",
                "1e999999999",
                "-1e999999999",
            })
    void positive_rejects(String word) {
        assertTrue(AmountInput.positive(typed(word.replace("'", ""))).isEmpty(), word);
    }

    @Test
    void positive_rejectsNull() {
        assertTrue(AmountInput.positive(null).isEmpty());
        assertTrue(AmountInput.nonNegative(null).isEmpty());
    }

    @Test
    void maxAmount_isTheLargestAccepted_andOneCentMoreIsNot() {
        assertAccepted(AmountInput.positive(AmountInput.MAX_AMOUNT), "99999999999999999.99");
        assertTrue(AmountInput.positive(AmountInput.MAX_AMOUNT.add(new BigDecimal("0.01")))
                .isEmpty());
        assertTrue(AmountInput.nonNegative(AmountInput.MAX_AMOUNT.add(new BigDecimal("0.01")))
                .isEmpty());
    }

    @ParameterizedTest
    @CsvSource({
        "0, 0.00",
        "0.00, 0.00",
        "-0, 0.00",
        "0E-999999999, 0.00", // a zero with an absurd scale collapses cheaply
        "0E+999999999, 0.00",
        "100, 100.00",
        "100.10, 100.10",
    })
    void nonNegative_allowsZero_forSettingBalances(String word, String expected) {
        assertAccepted(AmountInput.nonNegative(typed(word)), expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.01", "-5", "100.001", "1e18", "1e999999999"})
    void nonNegative_rejects(String word) {
        assertTrue(AmountInput.nonNegative(typed(word)).isEmpty(), word);
    }

    @Test
    @Timeout(5)
    void hugeExponents_areRejectedWithoutMaterialisingTheValue() {
        // Scaling 1e999999999 to cents builds a ~3.3-billion-bit integer; the
        // range check must happen first. (Pre-fix, any player could pin a CPU
        // for minutes with `/pay x 1e100000000`.)
        assertTrue(AmountInput.positive(new BigDecimal("1e999999999")).isEmpty());
        assertTrue(AmountInput.positive(new BigDecimal("1.0e-999999999")).isEmpty());
        assertTrue(AmountInput.nonNegative(new BigDecimal("-1e999999999")).isEmpty());
    }
}
