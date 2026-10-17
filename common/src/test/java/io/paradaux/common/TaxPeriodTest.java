package io.paradaux.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TaxPeriodTest {

    private static final Instant ANCHOR = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void parsesSimpleLengths() {
        assertEquals(Duration.ofSeconds(45), TaxPeriod.parseLength("45s"));
        assertEquals(Duration.ofMinutes(90), TaxPeriod.parseLength("90m"));
        assertEquals(Duration.ofHours(12), TaxPeriod.parseLength("12h"));
        assertEquals(Duration.ofDays(30), TaxPeriod.parseLength("30d"));
        assertEquals(Duration.ofDays(14), TaxPeriod.parseLength("2w"));
        assertEquals(Duration.ofDays(30), TaxPeriod.parseLength(" 30D "));
    }

    @Test
    void parsesCompoundLengths() {
        assertEquals(Duration.ofHours(36), TaxPeriod.parseLength("1d12h"));
        assertEquals(Duration.ofHours(36), TaxPeriod.parseLength("1d 12h"));
    }

    @Test
    void parsesIsoLengths() {
        assertEquals(Duration.ofDays(30), TaxPeriod.parseLength("P30D"));
        assertEquals(Duration.ofHours(12), TaxPeriod.parseLength("pt12h"));
    }

    @Test
    void rejectsInvalidLengths() {
        assertThrows(IllegalArgumentException.class, () -> TaxPeriod.parseLength(null));
        assertThrows(IllegalArgumentException.class, () -> TaxPeriod.parseLength("  "));
        assertThrows(IllegalArgumentException.class, () -> TaxPeriod.parseLength("30"));
        assertThrows(IllegalArgumentException.class, () -> TaxPeriod.parseLength("30x"));
        assertThrows(IllegalArgumentException.class, () -> TaxPeriod.parseLength("30d junk"));
        assertThrows(IllegalArgumentException.class, () -> TaxPeriod.parseLength("x30d"));
        assertThrows(IllegalArgumentException.class, () -> TaxPeriod.parseLength("Pnope"));
    }

    @Test
    void parsesAnchors() {
        assertEquals(ANCHOR, TaxPeriod.parseAnchor("2026-01-01"));
        assertEquals(ANCHOR, TaxPeriod.parseAnchor("2026-01-01T00:00:00Z"));
        assertEquals(Instant.parse("2026-03-05T03:00:00Z"), TaxPeriod.parseAnchor(" 2026-03-05T03:00:00Z "));
    }

    @Test
    void rejectsInvalidAnchors() {
        assertThrows(IllegalArgumentException.class, () -> TaxPeriod.parseAnchor(null));
        assertThrows(IllegalArgumentException.class, () -> TaxPeriod.parseAnchor(""));
        assertThrows(IllegalArgumentException.class, () -> TaxPeriod.parseAnchor("2026-13-01"));
        assertThrows(IllegalArgumentException.class, () -> TaxPeriod.parseAnchor("tomorrow"));
    }

    @Test
    void periodStartAlignsToTheAnchor() {
        TaxPeriod p = TaxPeriod.of(Duration.ofDays(30), ANCHOR);
        assertEquals(ANCHOR, p.periodStart(ANCHOR));
        assertEquals(ANCHOR, p.periodStart(ANCHOR.plus(Duration.ofDays(29)).plusSeconds(86399)));
        assertEquals(ANCHOR.plus(Duration.ofDays(30)), p.periodStart(ANCHOR.plus(Duration.ofDays(30))));
        assertEquals(ANCHOR.plus(Duration.ofDays(60)), p.periodStart(ANCHOR.plus(Duration.ofDays(75))));
    }

    @Test
    void momentsBeforeTheAnchorFallIntoEarlierPeriods() {
        TaxPeriod p = TaxPeriod.of(Duration.ofDays(7), ANCHOR);
        assertEquals(ANCHOR.minus(Duration.ofDays(7)), p.periodStart(ANCHOR.minusSeconds(1)));
        assertEquals(ANCHOR.minus(Duration.ofDays(14)), p.periodStart(ANCHOR.minus(Duration.ofDays(8))));
    }

    @Test
    void nextPeriodStartIsOneLengthLater() {
        TaxPeriod p = TaxPeriod.of(Duration.ofHours(12), ANCHOR);
        Instant moment = ANCHOR.plus(Duration.ofHours(13));
        assertEquals(ANCHOR.plus(Duration.ofHours(24)), p.nextPeriodStart(moment));
        assertTrue(p.nextPeriodStart(moment).isAfter(moment));
    }

    @Test
    void enforcesTheMinimumLength() {
        assertThrows(IllegalArgumentException.class, () -> TaxPeriod.of(Duration.ofSeconds(59), ANCHOR));
        assertEquals(
                TaxPeriod.MINIMUM_LENGTH,
                TaxPeriod.of(TaxPeriod.MINIMUM_LENGTH, ANCHOR).length());
    }

    @Test
    void exposesItsParts() {
        TaxPeriod p = TaxPeriod.of(Duration.ofDays(30), ANCHOR);
        assertEquals(Duration.ofDays(30), p.length());
        assertEquals(ANCHOR, p.anchor());
    }

    @Test
    void requiresLengthAndAnchor() {
        assertThrows(NullPointerException.class, () -> TaxPeriod.of(null, ANCHOR));
        assertThrows(NullPointerException.class, () -> TaxPeriod.of(Duration.ofDays(1), null));
    }
}
