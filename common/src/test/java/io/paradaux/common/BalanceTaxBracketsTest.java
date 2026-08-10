package io.paradaux.common;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BalanceTaxBracketsTest {

    @Test
    void emptyConfigFallsBackToDefaults() {
        List<String> warnings = new ArrayList<>();
        BalanceTaxBrackets b = BalanceTaxBrackets.fromRawEntries(Map.of(), warnings::add);
        assertEquals(BalanceTaxBrackets.DEFAULTS.size(), b.size());
        assertTrue(warnings.isEmpty());
        // default top bracket 500000 -> 0.018
        assertEquals(new BigDecimal("0.018"), b.weeklyRate(new BigDecimal("999999")));
    }

    @Test
    void nullEntriesFallBackToDefaults() {
        BalanceTaxBrackets b = BalanceTaxBrackets.fromRawEntries(null, w -> { });
        assertEquals(BalanceTaxBrackets.DEFAULTS.size(), b.size());
    }

    @Test
    void parsesValidEntries() {
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("0", "0");
        raw.put("1000", "0.05");
        BalanceTaxBrackets b = BalanceTaxBrackets.fromRawEntries(raw, w -> { });
        assertEquals(2, b.size());
        assertEquals(BigDecimal.ZERO, b.weeklyRate(new BigDecimal("500")));
        assertEquals(new BigDecimal("0.05"), b.weeklyRate(new BigDecimal("1000")));
        assertEquals(new BigDecimal("0.05"), b.weeklyRate(new BigDecimal("5000")));
    }

    @Test
    void belowLowestFloorIsZeroRate() {
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("1000", "0.05");
        BalanceTaxBrackets b = BalanceTaxBrackets.fromRawEntries(raw, w -> { });
        assertEquals(BigDecimal.ZERO, b.weeklyRate(new BigDecimal("999")));
    }

    @Test
    void skipsOutOfRangeRateWithWarning() {
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("0", "0");
        raw.put("1000", "1.5");   // > 1
        raw.put("2000", "-0.1");  // < 0
        List<String> warnings = new ArrayList<>();
        BalanceTaxBrackets b = BalanceTaxBrackets.fromRawEntries(raw, warnings::add);
        assertEquals(1, b.size()); // only the 0 -> 0 bracket survives
        assertEquals(2, warnings.size());
        assertTrue(warnings.get(0).contains("out of range"));
    }

    @Test
    void skipsUnparseableKeyWithWarning() {
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("0", "0");
        raw.put("notanumber", "0.05");
        List<String> warnings = new ArrayList<>();
        BalanceTaxBrackets b = BalanceTaxBrackets.fromRawEntries(raw, warnings::add);
        assertEquals(1, b.size());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("Invalid balance tax bracket key"));
    }

    @Test
    void unparseableRateIsNamedAsRateNotKey_andSkipped() {
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("0", "0");
        raw.put("1000", "notarate");   // key parses, value does not
        List<String> warnings = new ArrayList<>();
        BalanceTaxBrackets b = BalanceTaxBrackets.fromRawEntries(raw, warnings::add);
        assertEquals(1, b.size());     // only 0 -> 0 survives; 1000 skipped, not coerced to 0
        assertEquals(1, warnings.size());
        String w = warnings.get(0);
        assertTrue(w.contains("rate"), "warning must name the rate field: " + w);
        assertFalse(w.contains("key '"), "warning must not misreport an unparseable rate as a key: " + w);
        // The skipped bracket must not have been silently added at rate 0.
        assertEquals(BigDecimal.ZERO, b.weeklyRate(new BigDecimal("1000")));
    }

    @Test
    void nullRateValueIsWarnedAndSkipped_notCoercedToZero() {
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("0", "0");
        raw.put("1000", null);
        List<String> warnings = new ArrayList<>();
        BalanceTaxBrackets b = BalanceTaxBrackets.fromRawEntries(raw, warnings::add);
        assertEquals(1, b.size());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).toLowerCase().contains("missing"));
    }

    @Test
    void blankRateValueIsWarnedAndSkipped() {
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("1000", "   ");
        List<String> warnings = new ArrayList<>();
        BalanceTaxBrackets b = BalanceTaxBrackets.fromRawEntries(raw, warnings::add);
        // no valid entry -> defaults
        assertEquals(BalanceTaxBrackets.DEFAULTS.size(), b.size());
        assertEquals(1, warnings.size());
    }

    @Test
    void nullOnWarnConsumerIsRejected() {
        assertThrows(NullPointerException.class,
                () -> BalanceTaxBrackets.fromRawEntries(Map.of("bad", "x"), null));
    }

    @Test
    void allInvalidFallsBackToDefaults() {
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("bad", "0.05");
        BalanceTaxBrackets b = BalanceTaxBrackets.fromRawEntries(raw, w -> { });
        assertEquals(BalanceTaxBrackets.DEFAULTS.size(), b.size());
    }

    @Test
    void ofBuildsFromExplicitBrackets() {
        NavigableMap<BigDecimal, BigDecimal> m = new TreeMap<>();
        m.put(new BigDecimal("0"), new BigDecimal("0.02"));
        BalanceTaxBrackets b = BalanceTaxBrackets.of(m);
        assertEquals(1, b.size());
        assertEquals(new BigDecimal("0.02"), b.weeklyRate(new BigDecimal("10")));
    }
}
