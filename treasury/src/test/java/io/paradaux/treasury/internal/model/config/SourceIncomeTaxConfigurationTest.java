package io.paradaux.treasury.internal.model.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SourceIncomeTaxConfigurationTest {

    @Test
    void getEffectiveRate_explicitPluginRateBeatsDefault() {
        Map<String, BigDecimal> rates = new HashMap<>();
        rates.put("Realty", new BigDecimal("0.08"));
        rates.put("Business", new BigDecimal("0.10"));

        SourceIncomeTaxConfiguration cfg =
                SourceIncomeTaxConfiguration.forTesting(true, new BigDecimal("0.05"), "DCGovernment", rates);

        assertThat(cfg.getEffectiveRate("Realty")).isEqualByComparingTo("0.08");
        assertThat(cfg.getEffectiveRate("Business")).isEqualByComparingTo("0.10");
    }

    @Test
    void getEffectiveRate_unknownPluginFallsBackToDefault() {
        Map<String, BigDecimal> rates = new HashMap<>();
        rates.put("Realty", new BigDecimal("0.08"));

        SourceIncomeTaxConfiguration cfg =
                SourceIncomeTaxConfiguration.forTesting(true, new BigDecimal("0.03"), "DCGovernment", rates);

        assertThat(cfg.getEffectiveRate("Unknown")).isEqualByComparingTo("0.03");
    }

    @Test
    void getEffectiveRate_isCaseSensitive() {
        Map<String, BigDecimal> rates = new HashMap<>();
        rates.put("Realty", new BigDecimal("0.08"));

        SourceIncomeTaxConfiguration cfg =
                SourceIncomeTaxConfiguration.forTesting(true, new BigDecimal("0.0"), "DCGovernment", rates);

        // 'realty' (lowercase) is not the same key — falls back to default
        assertThat(cfg.getEffectiveRate("realty")).isEqualByComparingTo("0.0");
    }

    @Test
    void emptyPluginRatesMap_alwaysUsesDefault() {
        SourceIncomeTaxConfiguration cfg =
                SourceIncomeTaxConfiguration.forTesting(true, new BigDecimal("0.07"), "DCGovernment", Map.of());

        assertThat(cfg.getEffectiveRate("Anything")).isEqualByComparingTo("0.07");
    }

    @Test
    void blankGovernmentAccount_fallsBackToDefault() {
        SourceIncomeTaxConfiguration cfg = SourceIncomeTaxConfiguration.forTesting(true, BigDecimal.ZERO, "", Map.of());
        assertThat(cfg.getGovernmentAccount()).isEqualTo("DCGovernment");
    }

    @Test
    void disabledConfig_stillReturnsRates() {
        // Note: callers check isEnabled() separately. The rate accessor is just a lookup.
        SourceIncomeTaxConfiguration cfg =
                SourceIncomeTaxConfiguration.forTesting(false, new BigDecimal("0.05"), "DCGovernment", Map.of());
        assertThat(cfg.isEnabled()).isFalse();
        assertThat(cfg.getEffectiveRate("X")).isEqualByComparingTo("0.05");
    }
}
