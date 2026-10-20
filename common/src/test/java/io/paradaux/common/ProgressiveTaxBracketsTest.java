package io.paradaux.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class ProgressiveTaxBracketsTest {

    /** 0 -> 0%, 5000 -> 3%, 20000 -> 10%. */
    private static ProgressiveTaxBrackets sample() {
        NavigableMap<BigDecimal, BigDecimal> m = new TreeMap<>();
        m.put(new BigDecimal("0"), new BigDecimal("0"));
        m.put(new BigDecimal("5000"), new BigDecimal("0.03"));
        m.put(new BigDecimal("20000"), new BigDecimal("0.10"));
        return ProgressiveTaxBrackets.of(m);
    }

    private static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    @Test
    void cumulativeTaxIsMarginal() {
        ProgressiveTaxBrackets b = sample();
        assertEquals(0, b.cumulativeTax(d("5000")).compareTo(BigDecimal.ZERO));
        // Only the $500 above the 5000 floor is taxed at 3%.
        assertEquals(0, b.cumulativeTax(d("5500")).compareTo(d("15")));
        // 15000 * 3% + 5000 * 10%
        assertEquals(0, b.cumulativeTax(d("25000")).compareTo(d("950")));
    }

    @Test
    void cumulativeTaxOfNothingIsZero() {
        ProgressiveTaxBrackets b = sample();
        assertEquals(BigDecimal.ZERO, b.cumulativeTax(null));
        assertEquals(BigDecimal.ZERO, b.cumulativeTax(BigDecimal.ZERO));
        assertEquals(BigDecimal.ZERO, b.cumulativeTax(d("-10")));
    }

    @Test
    void incomeBelowTheLowestFloorIsUntaxed() {
        NavigableMap<BigDecimal, BigDecimal> m = new TreeMap<>();
        m.put(d("1000"), d("0.10"));
        ProgressiveTaxBrackets b = ProgressiveTaxBrackets.of(m);
        assertEquals(0, b.cumulativeTax(d("1000")).compareTo(BigDecimal.ZERO));
        assertEquals(0, b.cumulativeTax(d("1500")).compareTo(d("50")));
    }

    @Test
    void taxOnIncrementMatchesTheWorkedExample() {
        ProgressiveTaxBrackets b = sample();
        // $500 earned: still in the 0% bracket.
        assertEquals(d("0.00"), b.taxOnIncrement(BigDecimal.ZERO, d("500")));
        // Another $5000 crosses 5000; only the $500 over the floor is taxed at 3%.
        assertEquals(d("15.00"), b.taxOnIncrement(d("500"), d("5000")));
        // Further income in the same bracket is taxed at 3% outright.
        assertEquals(d("30.00"), b.taxOnIncrement(d("5500"), d("1000")));
    }

    @Test
    void taxOnIncrementSpansSeveralBrackets() {
        ProgressiveTaxBrackets b = sample();
        // 0 -> 25000 in one payment: the full schedule.
        assertEquals(d("950.00"), b.taxOnIncrement(BigDecimal.ZERO, d("25000")));
    }

    @Test
    void perPaymentTaxesSumToTheTaxOnTheTotal() {
        ProgressiveTaxBrackets b = sample();
        BigDecimal income = BigDecimal.ZERO;
        BigDecimal paid = BigDecimal.ZERO;
        // Awkward amounts so each slice rounds; the running total must still be exact.
        for (int i = 0; i < 200; i++) {
            BigDecimal amount = d("123.37");
            paid = paid.add(b.taxOnIncrement(income, amount));
            income = income.add(amount);
        }
        assertEquals(b.cumulativeTax(income).setScale(2, java.math.RoundingMode.HALF_UP), paid);
    }

    @Test
    void taxOnIncrementIgnoresNonPositiveAmounts() {
        ProgressiveTaxBrackets b = sample();
        assertEquals(d("0.00"), b.taxOnIncrement(d("6000"), BigDecimal.ZERO));
        assertEquals(d("0.00"), b.taxOnIncrement(d("6000"), d("-5")));
        assertEquals(d("0.00"), b.taxOnIncrement(d("6000"), null));
    }

    @Test
    void taxOnIncrementTreatsMissingOrNegativeHistoryAsZero() {
        ProgressiveTaxBrackets b = sample();
        assertEquals(d("15.00"), b.taxOnIncrement(null, d("5500")));
        assertEquals(d("15.00"), b.taxOnIncrement(d("-100"), d("5500")));
    }

    @Test
    void taxOnIncrementNeverExceedsTheAmount() {
        NavigableMap<BigDecimal, BigDecimal> m = new TreeMap<>();
        m.put(d("0"), BigDecimal.ONE);
        ProgressiveTaxBrackets b = ProgressiveTaxBrackets.of(m);
        // Cumulative rounding would ask for 0.01 on a 0.002 payment (0.004 -> 0.006
        // rounds 0.00 -> 0.01); the cap holds it to what was actually received.
        assertEquals(d("0.00"), b.taxOnIncrement(d("0.004"), d("0.002")));
        assertEquals(d("100.00"), b.taxOnIncrement(BigDecimal.ZERO, d("100")));
    }

    @Test
    void parsesValidEntries() {
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("0", "0");
        raw.put(" 5000 ", " 0.03 ");
        List<String> warnings = new ArrayList<>();
        ProgressiveTaxBrackets b = ProgressiveTaxBrackets.fromRawEntries(raw, warnings::add);
        assertEquals(2, b.size());
        assertTrue(warnings.isEmpty());
        assertEquals(d("0.03"), b.asMap().get(d("5000")));
        assertFalse(b.isEmpty());
    }

    @Test
    void skipsInvalidEntriesWithAWarning() {
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("abc", "0.1");
        raw.put("-1", "0.1");
        raw.put("100", "");
        raw.put("200", "x");
        raw.put("300", "1.5");
        raw.put("400", "-0.1");
        raw.put("500", "0.2");
        List<String> warnings = new ArrayList<>();
        ProgressiveTaxBrackets b = ProgressiveTaxBrackets.fromRawEntries(raw, warnings::add);
        assertEquals(1, b.size());
        assertEquals(6, warnings.size());
        assertTrue(warnings.get(0).contains("abc"));
        assertTrue(warnings.get(1).contains("negative"));
        assertTrue(warnings.get(2).contains("Missing"));
        assertTrue(warnings.get(3).contains("Invalid"));
        assertTrue(warnings.get(4).contains("out of range"));
        assertTrue(warnings.get(5).contains("out of range"));
    }

    @Test
    void acceptsTheRateBounds() {
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("0", "0");
        raw.put("100", "1");
        List<String> warnings = new ArrayList<>();
        ProgressiveTaxBrackets b = ProgressiveTaxBrackets.fromRawEntries(raw, warnings::add);
        assertEquals(2, b.size());
        assertTrue(warnings.isEmpty());
    }

    @Test
    void emptyOrNullConfigTaxesNothing() {
        ProgressiveTaxBrackets empty = ProgressiveTaxBrackets.fromRawEntries(Map.of(), w -> {});
        ProgressiveTaxBrackets none = ProgressiveTaxBrackets.fromRawEntries(null, w -> {});
        assertEquals(0, empty.size());
        assertTrue(empty.isEmpty());
        assertTrue(none.isEmpty());
        assertEquals(d("0.00"), empty.taxOnIncrement(BigDecimal.ZERO, d("1000000")));
    }

    @Test
    void zeroRateBracketsAreEmpty() {
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("0", "0");
        raw.put("1000", "0.0");
        assertTrue(ProgressiveTaxBrackets.fromRawEntries(raw, w -> {}).isEmpty());
    }

    @Test
    void requiresAWarningSink() {
        assertThrows(NullPointerException.class, () -> ProgressiveTaxBrackets.fromRawEntries(Map.of(), null));
    }

    @Test
    void asMapIsUnmodifiable() {
        ProgressiveTaxBrackets b = sample();
        assertThrows(UnsupportedOperationException.class, () -> b.asMap().put(d("1"), d("0.5")));
    }
}
