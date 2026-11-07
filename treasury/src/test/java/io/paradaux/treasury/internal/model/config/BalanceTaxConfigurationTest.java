package io.paradaux.treasury.internal.model.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.NavigableMap;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class BalanceTaxConfigurationTest {

    private static NavigableMap<BigDecimal, BigDecimal> brackets() {
        NavigableMap<BigDecimal, BigDecimal> m = new TreeMap<>();
        m.put(new BigDecimal("0.00"), new BigDecimal("0"));
        m.put(new BigDecimal("100000.00"), new BigDecimal("0.01"));
        m.put(new BigDecimal("200000.00"), new BigDecimal("0.012"));
        m.put(new BigDecimal("500000.00"), new BigDecimal("0.018"));
        return m;
    }

    @Test
    void getWeeklyRate_picksFlatBracketAtFloor() {
        BalanceTaxConfiguration cfg = BalanceTaxConfiguration.forTesting(true, "DCGovernment", brackets());

        assertThat(cfg.getWeeklyRate(new BigDecimal("0.00"))).isEqualByComparingTo("0");
        assertThat(cfg.getWeeklyRate(new BigDecimal("100000.00"))).isEqualByComparingTo("0.01");
        assertThat(cfg.getWeeklyRate(new BigDecimal("200000.00"))).isEqualByComparingTo("0.012");
        assertThat(cfg.getWeeklyRate(new BigDecimal("500000.00"))).isEqualByComparingTo("0.018");
    }

    @Test
    void getWeeklyRate_picksLowerBracketWhenBetweenFloors() {
        BalanceTaxConfiguration cfg = BalanceTaxConfiguration.forTesting(true, "DCGovernment", brackets());

        // 150,000 falls between the 100k and 200k floors → uses the 100k rate (flat, not marginal)
        assertThat(cfg.getWeeklyRate(new BigDecimal("150000.00"))).isEqualByComparingTo("0.01");
        // 499,999.99 → still 200k bracket
        assertThat(cfg.getWeeklyRate(new BigDecimal("499999.99"))).isEqualByComparingTo("0.012");
        // 1M → 500k bracket
        assertThat(cfg.getWeeklyRate(new BigDecimal("1000000.00"))).isEqualByComparingTo("0.018");
    }

    @Test
    void getWeeklyRate_returnsZeroForBalanceBelowLowestFloor() {
        // Brackets start at 100k — anything lower returns 0
        NavigableMap<BigDecimal, BigDecimal> m = new TreeMap<>();
        m.put(new BigDecimal("100000.00"), new BigDecimal("0.01"));
        BalanceTaxConfiguration cfg = BalanceTaxConfiguration.forTesting(true, "DCGovernment", m);

        assertThat(cfg.getWeeklyRate(new BigDecimal("99999.99"))).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(cfg.getWeeklyRate(BigDecimal.ZERO)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void blankGovernmentAccount_fallsBackToDefault() {
        BalanceTaxConfiguration cfg = BalanceTaxConfiguration.forTesting(true, "  ", brackets());
        assertThat(cfg.getGovernmentAccount()).isEqualTo("DCGovernment");
    }

    @Test
    void enabledFlagPropagates() {
        assertThat(BalanceTaxConfiguration.forTesting(true, "X", brackets()).isEnabled())
                .isTrue();
        assertThat(BalanceTaxConfiguration.forTesting(false, "X", brackets()).isEnabled())
                .isFalse();
    }
}
