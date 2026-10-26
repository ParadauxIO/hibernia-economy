package io.paradaux.treasury.model.config;

import io.paradaux.treasury.Treasury;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Exercises the {@code @Inject Treasury plugin} constructor of
 * {@link SourceIncomeTaxConfiguration}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SourceIncomeTaxConfigurationBukkitCtorTest {

    @Mock Treasury plugin;
    @Mock FileConfiguration fileConfig;
    @Mock ConfigurationSection ratesSection;

    @BeforeEach
    void wireConfig() {
        lenient().when(plugin.getConfig()).thenReturn(fileConfig);
    }

    @Test
    void disabled_default_returnsZeroRateForAnyPlugin() {
        when(fileConfig.getBoolean("tax.source-income-tax.enabled", false)).thenReturn(false);
        when(fileConfig.getString("tax.source-income-tax.default-rate", "0")).thenReturn("0");
        when(fileConfig.getString("tax.source-income-tax.government-account", "DCGovernment"))
                .thenReturn("DCGov");
        when(fileConfig.getConfigurationSection("tax.source-income-tax.plugin-rates")).thenReturn(null);

        SourceIncomeTaxConfiguration cfg = new SourceIncomeTaxConfiguration(plugin);

        assertThat(cfg.isEnabled()).isFalse();
        assertThat(cfg.getEffectiveRate("Anything")).isEqualByComparingTo("0.0");
        assertThat(cfg.getGovernmentAccount()).isEqualTo("DCGov");
    }

    @Test
    void enabled_withDefaultRateAndOneOverride_picksRightValue() {
        when(fileConfig.getBoolean("tax.source-income-tax.enabled", false)).thenReturn(true);
        when(fileConfig.getString("tax.source-income-tax.default-rate", "0")).thenReturn("0.05");
        when(fileConfig.getString("tax.source-income-tax.government-account", "DCGovernment"))
                .thenReturn("DCGov");
        when(fileConfig.getConfigurationSection("tax.source-income-tax.plugin-rates"))
                .thenReturn(ratesSection);

        Set<String> keys = new LinkedHashSet<>(Set.of("Realty"));
        when(ratesSection.getKeys(false)).thenReturn(keys);
        when(ratesSection.getString("Realty")).thenReturn("0.10");

        SourceIncomeTaxConfiguration cfg = new SourceIncomeTaxConfiguration(plugin);

        assertThat(cfg.isEnabled()).isTrue();
        assertThat(cfg.getDefaultRate()).isEqualByComparingTo("0.05");
        assertThat(cfg.getEffectiveRate("Realty")).isEqualByComparingTo("0.10");
        assertThat(cfg.getEffectiveRate("Other")).isEqualByComparingTo("0.05");
    }

    @Test
    void blankGovernmentAccount_fallsBackToDefault() {
        when(fileConfig.getBoolean("tax.source-income-tax.enabled", false)).thenReturn(true);
        when(fileConfig.getString("tax.source-income-tax.default-rate", "0")).thenReturn("0");
        when(fileConfig.getString("tax.source-income-tax.government-account", "DCGovernment")).thenReturn("");
        when(fileConfig.getConfigurationSection("tax.source-income-tax.plugin-rates")).thenReturn(null);

        SourceIncomeTaxConfiguration cfg = new SourceIncomeTaxConfiguration(plugin);
        assertThat(cfg.getGovernmentAccount()).isEqualTo("DCGovernment");
    }

    @Test
    void rateOutOfRange_isSkipped() {
        when(fileConfig.getBoolean("tax.source-income-tax.enabled", false)).thenReturn(true);
        when(fileConfig.getString("tax.source-income-tax.default-rate", "0")).thenReturn("0.02");
        when(fileConfig.getString("tax.source-income-tax.government-account", "DCGovernment")).thenReturn("X");
        when(fileConfig.getConfigurationSection("tax.source-income-tax.plugin-rates"))
                .thenReturn(ratesSection);

        Set<String> keys = new LinkedHashSet<>(Set.of("Realty", "Bad"));
        when(ratesSection.getKeys(false)).thenReturn(keys);
        when(ratesSection.getString("Realty")).thenReturn("0.10");
        when(ratesSection.getString("Bad")).thenReturn("2.0"); // > 1 → skip

        SourceIncomeTaxConfiguration cfg = new SourceIncomeTaxConfiguration(plugin);
        assertThat(cfg.getEffectiveRate("Realty")).isEqualByComparingTo("0.10");
        // Bad got dropped → falls back to default
        assertThat(cfg.getEffectiveRate("Bad")).isEqualByComparingTo("0.02");
    }

    @Test
    void invalidNumberFormat_isSkipped() {
        when(fileConfig.getBoolean("tax.source-income-tax.enabled", false)).thenReturn(true);
        when(fileConfig.getString("tax.source-income-tax.default-rate", "0")).thenReturn("0");
        when(fileConfig.getString("tax.source-income-tax.government-account", "DCGovernment")).thenReturn("X");
        when(fileConfig.getConfigurationSection("tax.source-income-tax.plugin-rates"))
                .thenReturn(ratesSection);

        Set<String> keys = new LinkedHashSet<>(Set.of("Realty"));
        when(ratesSection.getKeys(false)).thenReturn(keys);
        when(ratesSection.getString("Realty")).thenReturn("not-a-number");

        SourceIncomeTaxConfiguration cfg = new SourceIncomeTaxConfiguration(plugin);
        assertThat(cfg.getEffectiveRate("Realty")).isEqualByComparingTo("0.0");
    }

    @Test
    void defaultRateOutOfRange_fallsBackToZero() {
        when(fileConfig.getBoolean("tax.source-income-tax.enabled", false)).thenReturn(true);
        when(fileConfig.getString("tax.source-income-tax.default-rate", "0")).thenReturn("1.5"); // > 1
        when(fileConfig.getString("tax.source-income-tax.government-account", "DCGovernment")).thenReturn("X");
        when(fileConfig.getConfigurationSection("tax.source-income-tax.plugin-rates")).thenReturn(null);

        SourceIncomeTaxConfiguration cfg = new SourceIncomeTaxConfiguration(plugin);
        assertThat(cfg.getDefaultRate()).isEqualByComparingTo("0");
    }

    @Test
    void defaultRateInvalidNumber_fallsBackToZero() {
        when(fileConfig.getBoolean("tax.source-income-tax.enabled", false)).thenReturn(true);
        when(fileConfig.getString("tax.source-income-tax.default-rate", "0")).thenReturn("not-a-number");
        when(fileConfig.getString("tax.source-income-tax.government-account", "DCGovernment")).thenReturn("X");
        when(fileConfig.getConfigurationSection("tax.source-income-tax.plugin-rates")).thenReturn(null);

        SourceIncomeTaxConfiguration cfg = new SourceIncomeTaxConfiguration(plugin);
        assertThat(cfg.getDefaultRate()).isEqualByComparingTo("0");
    }

    @Test
    void nullRateString_isSkipped() {
        when(fileConfig.getBoolean("tax.source-income-tax.enabled", false)).thenReturn(true);
        when(fileConfig.getString("tax.source-income-tax.default-rate", "0")).thenReturn("0");
        when(fileConfig.getString("tax.source-income-tax.government-account", "DCGovernment")).thenReturn("X");
        when(fileConfig.getConfigurationSection("tax.source-income-tax.plugin-rates"))
                .thenReturn(ratesSection);

        Set<String> keys = new LinkedHashSet<>(Set.of("Realty"));
        when(ratesSection.getKeys(false)).thenReturn(keys);
        when(ratesSection.getString("Realty")).thenReturn(null);

        SourceIncomeTaxConfiguration cfg = new SourceIncomeTaxConfiguration(plugin);
        assertThat(cfg.getEffectiveRate("Realty")).isEqualByComparingTo("0.0");
    }
}
