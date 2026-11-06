package io.paradaux.business.internal.model.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import io.paradaux.business.Business;
import java.math.BigDecimal;
import java.util.Set;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BalanceTaxConfigurationTest {

    @Mock
    Business plugin;

    @Mock
    FileConfiguration cfg;

    @Mock
    ConfigurationSection bracketsSec;

    @BeforeEach
    void setUp() {
        // The constructor reads via plugin.getLogger() and plugin.getConfig().
        // Logger has no abstract methods we care about here; a real anonymous
        // subclass keeps Mockito off Java 21's reflection-restriction list.
        lenient().when(plugin.getLogger()).thenReturn(Logger.getLogger("BalanceTaxConfigurationTest"));
        lenient().when(plugin.getConfig()).thenReturn(cfg);
    }

    @Test
    void disabledByDefaultUsesDefaultBracketsAndDefaultAccount() {
        when(cfg.getBoolean("tax.balance.enabled", false)).thenReturn(false);
        when(cfg.getString("tax.balance.government-account", "DCGovernment")).thenReturn(null);
        when(cfg.getConfigurationSection("tax.balance.brackets")).thenReturn(null);

        BalanceTaxConfiguration tax = new BalanceTaxConfiguration(plugin);

        assertThat(tax.isEnabled()).isFalse();
        assertThat(tax.getGovernmentAccount()).isEqualTo("DCGovernment");
        // Default brackets cap out at 500k → 1.8%; under 100k → 0.
        assertThat(tax.getWeeklyRate(new BigDecimal("0"))).isEqualByComparingTo("0");
        assertThat(tax.getWeeklyRate(new BigDecimal("99999"))).isEqualByComparingTo("0");
        assertThat(tax.getWeeklyRate(new BigDecimal("100000"))).isEqualByComparingTo("0.01");
        assertThat(tax.getWeeklyRate(new BigDecimal("250000"))).isEqualByComparingTo("0.012");
        assertThat(tax.getBracket(new BigDecimal("250000")).ledgerTag()).isEqualTo(" [bracket:200000:300000:0.012]");
        assertThat(tax.getWeeklyRate(new BigDecimal("500000"))).isEqualByComparingTo("0.018");
        assertThat(tax.getWeeklyRate(new BigDecimal("999999999"))).isEqualByComparingTo("0.018");
    }

    @Test
    void blankGovernmentAccountFallsBackToDefault() {
        when(cfg.getBoolean("tax.balance.enabled", false)).thenReturn(false);
        when(cfg.getString("tax.balance.government-account", "DCGovernment")).thenReturn("   ");
        when(cfg.getConfigurationSection("tax.balance.brackets")).thenReturn(null);

        BalanceTaxConfiguration tax = new BalanceTaxConfiguration(plugin);
        assertThat(tax.getGovernmentAccount()).isEqualTo("DCGovernment");
    }

    @Test
    void customGovernmentAccountIsUsed() {
        when(cfg.getBoolean("tax.balance.enabled", false)).thenReturn(false);
        when(cfg.getString("tax.balance.government-account", "DCGovernment")).thenReturn("MyTreasury");
        when(cfg.getConfigurationSection("tax.balance.brackets")).thenReturn(null);

        BalanceTaxConfiguration tax = new BalanceTaxConfiguration(plugin);
        assertThat(tax.getGovernmentAccount()).isEqualTo("MyTreasury");
    }

    @Test
    void customBracketsOverrideDefaults() {
        when(cfg.getBoolean("tax.balance.enabled", false)).thenReturn(true);
        when(cfg.getString("tax.balance.government-account", "DCGovernment")).thenReturn("DCGovernment");
        when(cfg.getConfigurationSection("tax.balance.brackets")).thenReturn(bracketsSec);
        when(bracketsSec.getKeys(false)).thenReturn(Set.of("0", "50000"));
        when(bracketsSec.getString(eq("0"), any())).thenReturn("0");
        when(bracketsSec.getString(eq("50000"), any())).thenReturn("0.05");

        BalanceTaxConfiguration tax = new BalanceTaxConfiguration(plugin);

        assertThat(tax.isEnabled()).isTrue();
        assertThat(tax.getWeeklyRate(new BigDecimal("0"))).isEqualByComparingTo("0");
        assertThat(tax.getWeeklyRate(new BigDecimal("49999"))).isEqualByComparingTo("0");
        assertThat(tax.getWeeklyRate(new BigDecimal("50000"))).isEqualByComparingTo("0.05");
        assertThat(tax.getWeeklyRate(new BigDecimal("100000"))).isEqualByComparingTo("0.05");
    }

    @Test
    void invalidBracketKeyIsSkippedAndDefaultsApplyIfAllInvalid() {
        when(cfg.getBoolean("tax.balance.enabled", false)).thenReturn(false);
        when(cfg.getString("tax.balance.government-account", "DCGovernment")).thenReturn("DCGovernment");
        when(cfg.getConfigurationSection("tax.balance.brackets")).thenReturn(bracketsSec);
        when(bracketsSec.getKeys(false)).thenReturn(Set.of("not-a-number"));

        BalanceTaxConfiguration tax = new BalanceTaxConfiguration(plugin);

        // Falls back to default brackets when the user-provided map ends up empty.
        assertThat(tax.getWeeklyRate(new BigDecimal("100000"))).isEqualByComparingTo("0.01");
    }

    @Test
    void outOfRangeRateIsSkipped() {
        when(cfg.getBoolean("tax.balance.enabled", false)).thenReturn(false);
        when(cfg.getString("tax.balance.government-account", "DCGovernment")).thenReturn("DCGovernment");
        when(cfg.getConfigurationSection("tax.balance.brackets")).thenReturn(bracketsSec);
        when(bracketsSec.getKeys(false)).thenReturn(Set.of("0"));
        when(bracketsSec.getString(eq("0"), any())).thenReturn("1.5"); // > 1.0 — invalid

        BalanceTaxConfiguration tax = new BalanceTaxConfiguration(plugin);

        // 1.5 was skipped, falling back to defaults.
        assertThat(tax.getWeeklyRate(new BigDecimal("100000"))).isEqualByComparingTo("0.01");
    }
}
