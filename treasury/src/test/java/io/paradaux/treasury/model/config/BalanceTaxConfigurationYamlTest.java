package io.paradaux.treasury.model.config;

import io.paradaux.treasury.Treasury;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Real-YAML parsing tests for {@link BalanceTaxConfiguration} (PAR-272).
 *
 * <p>The mock-based {@link BalanceTaxConfigurationBukkitCtorTest} stubs an
 * already-parsed {@code ConfigurationSection}, so it cannot catch the
 * decimal-bracket-key bug — that bug lived in how YAML/Bukkit parse the bracket
 * <em>keys</em>. These tests load actual YAML so the key parsing is exercised.
 */
@ExtendWith(MockitoExtension.class)
class BalanceTaxConfigurationYamlTest {

    @Mock Treasury plugin;

    private static YamlConfiguration yaml(String s) {
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.loadFromString(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return y;
    }

    private BalanceTaxConfiguration load(YamlConfiguration cfg) {
        when(plugin.getConfig()).thenReturn(cfg);
        return new BalanceTaxConfiguration(plugin);
    }

    @Test
    void integerBracketKeys_takeEffect_evenWhenDifferentFromTheDefaults() {
        // Custom rates that DON'T match DEFAULT_BRACKETS, so a silent fallback to the
        // defaults (the decimal-key bug) would fail this — proving the edit took effect.
        BalanceTaxConfiguration cfg = load(yaml(
                "tax:\n" +
                "  balance:\n" +
                "    enabled: true\n" +
                "    brackets:\n" +
                "      0: 0.0\n" +
                "      150000: 0.05\n"));

        assertThat(cfg.getWeeklyRate(new BigDecimal("100000"))).isEqualByComparingTo("0");    // below custom floor
        assertThat(cfg.getWeeklyRate(new BigDecimal("150000"))).isEqualByComparingTo("0.05"); // custom rate applied
        assertThat(cfg.getWeeklyRate(new BigDecimal("999999"))).isEqualByComparingTo("0.05");
    }

    @Test
    void shippedConfigYml_usesFlatWholeNumberBracketKeys_andParsesEndToEnd() throws Exception {
        YamlConfiguration y;
        try (InputStream in = getClass().getResourceAsStream("/config.yml")) {
            assertThat(in).as("config.yml on classpath").isNotNull();
            y = yaml(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        ConfigurationSection sec = y.getConfigurationSection("tax.balance.brackets");
        assertThat(sec).isNotNull();
        // Flat whole-number keys, each mapping to a scalar rate (not a nested section):
        assertThat(sec.getKeys(false)).containsExactlyInAnyOrder("0", "100000", "200000", "300000", "500000");
        assertThat(sec.getString("100000")).isEqualTo("0.01");
        assertThat(sec.getString("500000")).isEqualTo("0.018");

        // And it parses end-to-end to the documented rates (not a silent fallback):
        BalanceTaxConfiguration cfg = load(y);
        assertThat(cfg.getWeeklyRate(new BigDecimal("99999"))).isEqualByComparingTo("0");
        assertThat(cfg.getWeeklyRate(new BigDecimal("100000"))).isEqualByComparingTo("0.01");
        assertThat(cfg.getWeeklyRate(new BigDecimal("250000"))).isEqualByComparingTo("0.012");
        assertThat(cfg.getWeeklyRate(new BigDecimal("400000"))).isEqualByComparingTo("0.014");
        assertThat(cfg.getWeeklyRate(new BigDecimal("1000000"))).isEqualByComparingTo("0.018");
    }

    @Test
    void decimalBracketKeys_areSilentlyIgnored_andFallBackToDefaults() {
        // The pitfall the config comment warns about (PAR-272): a decimal key like
        // `150000.00` is parsed by YAML/Bukkit as a nested path, so the bracket's rate
        // reads back as a section (not a number), throws, and is dropped. With every
        // bracket dropped the loader falls back to DEFAULT_BRACKETS — so a custom rate
        // under a decimal floor is silently lost (you get the defaults, not your edit).
        BalanceTaxConfiguration cfg = load(yaml(
                "tax:\n" +
                "  balance:\n" +
                "    enabled: true\n" +
                "    brackets:\n" +
                "      0.00: 0.0\n" +
                "      150000.00: 0.05\n"));

        // Custom 0.05 @ 150k is NOT honoured; the default 100k→0.01 bracket applies.
        assertThat(cfg.getWeeklyRate(new BigDecimal("150000"))).isEqualByComparingTo("0.01");
        assertThat(cfg.getWeeklyRate(new BigDecimal("1000000"))).isEqualByComparingTo("0.018");
    }
}
