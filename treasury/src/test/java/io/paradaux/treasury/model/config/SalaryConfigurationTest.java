package io.paradaux.treasury.model.config;

import io.paradaux.treasury.Treasury;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SalaryConfigurationTest {

    private static Map<String, BigDecimal> amounts() {
        return Map.of(
                "senator", new BigDecimal("65.0"),
                "judge", new BigDecimal("65.0"),
                "president", new BigDecimal("75.0"),
                "owner", BigDecimal.ZERO);
    }

    // ---- forTesting + logic ----

    @Test
    void forTesting_normalisesAndExposesValues() {
        SalaryConfiguration c = SalaryConfiguration.forTesting(true, "GovBank", 900, amounts());
        assertThat(c.isEnabled()).isTrue();
        assertThat(c.getGovernmentAccount()).isEqualTo("GovBank");
        assertThat(c.getIntervalSeconds()).isEqualTo(900);
        assertThat(c.getAmounts()).containsKeys("senator", "president", "owner");
    }

    @Test
    void defaultsApplyForBlankAccountAndNonPositiveInterval() {
        SalaryConfiguration c = SalaryConfiguration.forTesting(false, "  ", 0, Map.of());
        assertThat(c.getGovernmentAccount()).isEqualTo("DCGovernment");
        assertThat(c.getIntervalSeconds()).isEqualTo(900);
    }

    @Test
    void highestSalary_picksTheTopPayingGroupCaseInsensitively() {
        SalaryConfiguration c = SalaryConfiguration.forTesting(true, "DCGovernment", 900, amounts());
        Optional<Map.Entry<String, BigDecimal>> best = c.highestSalary(List.of("Senator", "PRESIDENT"));
        assertThat(best).isPresent();
        assertThat(best.get().getKey()).isEqualTo("president");
        assertThat(best.get().getValue()).isEqualByComparingTo("75.0");
    }

    @Test
    void highestSalary_isEmptyWhenNoGroupIsSalaried_orOnlyZero() {
        SalaryConfiguration c = SalaryConfiguration.forTesting(true, "DCGovernment", 900, amounts());
        assertThat(c.highestSalary(List.of("guest", "visitor"))).isEmpty();
        assertThat(c.highestSalary(List.of("owner"))).isEmpty(); // 0 salary doesn't count
        assertThat(c.highestSalary(null)).isEmpty();
        assertThat(c.highestSalary(java.util.Arrays.asList((String) null))).isEmpty();
    }

    // ---- Bukkit-config parsing path ----

    @Test
    void injectCtor_parsesConfig(@Mock Treasury plugin, @Mock FileConfiguration cfg, @Mock ConfigurationSection sec) {
        when(plugin.getConfig()).thenReturn(cfg);
        when(cfg.getBoolean("salaries.enabled", false)).thenReturn(true);
        when(cfg.getString("salaries.government-account", "DCGovernment")).thenReturn("DCGovernment");
        when(cfg.getLong("salaries.interval", 900L)).thenReturn(1800L);
        when(cfg.getConfigurationSection("salaries.amount")).thenReturn(sec);
        Set<String> keys = new LinkedHashSet<>(List.of("senator", "president", "bad", "garbage"));
        when(sec.getKeys(false)).thenReturn(keys);
        when(sec.getString("senator", "0")).thenReturn("65.0");
        when(sec.getString("president", "0")).thenReturn("75.0");
        when(sec.getString("bad", "0")).thenReturn("-5.0");     // negative → skipped
        when(sec.getString("garbage", "0")).thenReturn("NaN");  // unparseable → skipped

        SalaryConfiguration c = new SalaryConfiguration(plugin);

        assertThat(c.isEnabled()).isTrue();
        assertThat(c.getIntervalSeconds()).isEqualTo(1800);
        assertThat(c.getAmounts()).containsOnlyKeys("senator", "president");
        assertThat(c.highestSalary(List.of("senator", "president")).orElseThrow().getValue())
                .isEqualByComparingTo("75.0");
    }

    @Test
    void injectCtor_handlesMissingAmountSection(@Mock Treasury plugin, @Mock FileConfiguration cfg) {
        when(plugin.getConfig()).thenReturn(cfg);
        lenient().when(cfg.getBoolean("salaries.enabled", false)).thenReturn(false);
        when(cfg.getString("salaries.government-account", "DCGovernment")).thenReturn("");
        when(cfg.getLong("salaries.interval", 900L)).thenReturn(900L);
        when(cfg.getConfigurationSection("salaries.amount")).thenReturn(null);

        SalaryConfiguration c = new SalaryConfiguration(plugin);

        assertThat(c.getAmounts()).isEmpty();
        assertThat(c.getGovernmentAccount()).isEqualTo("DCGovernment"); // blank → default
    }
}
