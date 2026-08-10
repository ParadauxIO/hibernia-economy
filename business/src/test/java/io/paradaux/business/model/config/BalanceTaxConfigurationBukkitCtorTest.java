package io.paradaux.business.model.config;

import io.paradaux.business.Business;
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
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

/**
 * Exercises the {@code @Inject Business plugin} constructor of
 * {@link BalanceTaxConfiguration} — the path that reads from {@code plugin.getConfig()}
 * and delegates bracket parsing to the shared {@code BalanceTaxBrackets} (ADT-186,
 * also closes the business-side gap noted in ADT-98).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BalanceTaxConfigurationBukkitCtorTest {

    @Mock Business plugin;
    @Mock FileConfiguration fileConfig;
    @Mock ConfigurationSection bracketsSection;

    @BeforeEach
    void wireConfig() {
        lenient().when(plugin.getConfig()).thenReturn(fileConfig);
        lenient().when(plugin.getLogger()).thenReturn(Logger.getLogger("BalanceTaxConfigurationBukkitCtorTest"));
    }

    @Test
    void readsEnabledFlag_andGovernmentAccount() {
        when(fileConfig.getBoolean("tax.balance.enabled", false)).thenReturn(true);
        when(fileConfig.getString("tax.balance.government-account", "DCGovernment")).thenReturn("MyGov");
        when(fileConfig.getConfigurationSection("tax.balance.brackets")).thenReturn(null); // → defaults

        BalanceTaxConfiguration cfg = new BalanceTaxConfiguration(plugin);

        assertThat(cfg.isEnabled()).isTrue();
        assertThat(cfg.getGovernmentAccount()).isEqualTo("MyGov");
        // Default brackets kicked in: balance ≥ 100,000 → 1 %
        assertThat(cfg.getWeeklyRate(new BigDecimal("100000"))).isEqualByComparingTo("0.01");
    }

    @Test
    void blankGovernmentAccount_fallsBackToDefault() {
        when(fileConfig.getBoolean("tax.balance.enabled", false)).thenReturn(false);
        when(fileConfig.getString("tax.balance.government-account", "DCGovernment")).thenReturn("   ");
        when(fileConfig.getConfigurationSection("tax.balance.brackets")).thenReturn(null);

        BalanceTaxConfiguration cfg = new BalanceTaxConfiguration(plugin);
        assertThat(cfg.getGovernmentAccount()).isEqualTo("DCGovernment");
    }

    @Test
    void parsesValidBrackets_fromConfigurationSection() {
        when(fileConfig.getBoolean("tax.balance.enabled", false)).thenReturn(true);
        when(fileConfig.getString("tax.balance.government-account", "DCGovernment")).thenReturn("DCGov");
        when(fileConfig.getConfigurationSection("tax.balance.brackets")).thenReturn(bracketsSection);

        Set<String> keys = new LinkedHashSet<>();
        keys.add("0");
        keys.add("50000");
        keys.add("250000");
        when(bracketsSection.getKeys(false)).thenReturn(keys);
        when(bracketsSection.getString("0",      "0")).thenReturn("0");
        when(bracketsSection.getString("50000",  "0")).thenReturn("0.005");
        when(bracketsSection.getString("250000", "0")).thenReturn("0.02");

        BalanceTaxConfiguration cfg = new BalanceTaxConfiguration(plugin);

        assertThat(cfg.getWeeklyRate(BigDecimal.ZERO)).isEqualByComparingTo("0");
        assertThat(cfg.getWeeklyRate(new BigDecimal("100000"))).isEqualByComparingTo("0.005");
        assertThat(cfg.getWeeklyRate(new BigDecimal("999999"))).isEqualByComparingTo("0.02");
    }

    @Test
    void rateOutOfRange_isSkipped() {
        when(fileConfig.getBoolean("tax.balance.enabled", false)).thenReturn(true);
        when(fileConfig.getString("tax.balance.government-account", "DCGovernment")).thenReturn("X");
        when(fileConfig.getConfigurationSection("tax.balance.brackets")).thenReturn(bracketsSection);

        Set<String> keys = new LinkedHashSet<>(Set.of("0", "100"));
        when(bracketsSection.getKeys(false)).thenReturn(keys);
        // 1.5 is > 1, must be skipped; 0 is valid → keeps 0 bracket
        when(bracketsSection.getString("0",   "0")).thenReturn("0");
        when(bracketsSection.getString("100", "0")).thenReturn("1.5");

        BalanceTaxConfiguration cfg = new BalanceTaxConfiguration(plugin);
        // Only the 0 bracket survived; rate is 0
        assertThat(cfg.getWeeklyRate(new BigDecimal("100"))).isEqualByComparingTo("0");
    }

    @Test
    void invalidKey_isSkipped() {
        when(fileConfig.getBoolean("tax.balance.enabled", false)).thenReturn(true);
        when(fileConfig.getString("tax.balance.government-account", "DCGovernment")).thenReturn("X");
        when(fileConfig.getConfigurationSection("tax.balance.brackets")).thenReturn(bracketsSection);

        Set<String> keys = new LinkedHashSet<>(Set.of("0", "not-a-number"));
        when(bracketsSection.getKeys(false)).thenReturn(keys);
        when(bracketsSection.getString("0", "0")).thenReturn("0");
        when(bracketsSection.getString("not-a-number", "0")).thenReturn("0.05");

        BalanceTaxConfiguration cfg = new BalanceTaxConfiguration(plugin);
        assertThat(cfg.getWeeklyRate(BigDecimal.ZERO)).isEqualByComparingTo("0");
    }

    @Test
    void reload_reReadsConfig() {
        when(fileConfig.getBoolean("tax.balance.enabled", false)).thenReturn(false);
        when(fileConfig.getString("tax.balance.government-account", "DCGovernment")).thenReturn("Gov1");
        when(fileConfig.getConfigurationSection("tax.balance.brackets")).thenReturn(null);

        BalanceTaxConfiguration cfg = new BalanceTaxConfiguration(plugin);
        assertThat(cfg.getGovernmentAccount()).isEqualTo("Gov1");

        // Config changed under us; reload() must pick up the new value.
        when(fileConfig.getString("tax.balance.government-account", "DCGovernment")).thenReturn("Gov2");
        cfg.reload();
        assertThat(cfg.getGovernmentAccount()).isEqualTo("Gov2");
    }

    @Test
    void emptyParsedMap_fallsBackToDefaults() {
        when(fileConfig.getBoolean("tax.balance.enabled", false)).thenReturn(true);
        when(fileConfig.getString("tax.balance.government-account", "DCGovernment")).thenReturn("X");
        when(fileConfig.getConfigurationSection("tax.balance.brackets")).thenReturn(bracketsSection);

        Set<String> keys = new LinkedHashSet<>(Set.of("bad"));
        when(bracketsSection.getKeys(false)).thenReturn(keys);
        when(bracketsSection.getString("bad", "0")).thenReturn("0.05");

        BalanceTaxConfiguration cfg = new BalanceTaxConfiguration(plugin);
        // Defaults applied: 100k → 1 %
        assertThat(cfg.getWeeklyRate(new BigDecimal("100000"))).isEqualByComparingTo("0.01");
    }

    private static <T> org.mockito.stubbing.OngoingStubbing<T> when(T mock) {
        return org.mockito.Mockito.when(mock);
    }
}
