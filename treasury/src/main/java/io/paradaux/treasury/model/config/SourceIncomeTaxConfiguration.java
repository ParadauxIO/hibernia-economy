package io.paradaux.treasury.model.config;

import com.google.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import io.paradaux.treasury.Treasury;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Configuration for the source income tax feature.
 *
 * <p>When enabled, Treasury automatically collects a fraction of every Vault deposit
 * (and any explicit {@link io.paradaux.treasury.api.TaxApi#applySourceIncomeTax} call)
 * as income tax. The rate is looked up per calling-plugin name; plugins not listed fall back to
 * the default rate.
 *
 * <p>Config location: {@code config.yml} under the {@code tax.source-income-tax:} key.
 */
@Slf4j
public class SourceIncomeTaxConfiguration {

    // Mutable so {@link #reload()} can refresh them at runtime (Guice singleton,
    // read live per deposit). volatile so a deposit handler on another thread sees a
    // fully-published value after /treasury reload re-populates these in place,
    // rather than a stale or torn read (ADT-30). {@code pluginRates} is replaced
    // wholesale.
    private volatile boolean enabled;
    private volatile BigDecimal defaultRate;
    private volatile String governmentAccount;
    private volatile Map<String, BigDecimal> pluginRates;
    /** Set only via the {@code @Inject} ctor; null for test-factory instances. */
    private Treasury plugin;

    private SourceIncomeTaxConfiguration(boolean enabled, BigDecimal defaultRate,
                                         String governmentAccount, Map<String, BigDecimal> pluginRates) {
        this.enabled = enabled;
        this.defaultRate = defaultRate;
        this.governmentAccount = (governmentAccount == null || governmentAccount.isBlank())
                ? "DCGovernment" : governmentAccount;
        this.pluginRates = Collections.unmodifiableMap(new HashMap<>(pluginRates));
    }

    /** Test-only factory: bypasses Bukkit config parsing. Production code uses the {@code @Inject} ctor. */
    public static SourceIncomeTaxConfiguration forTesting(boolean enabled, BigDecimal defaultRate,
                                                          String governmentAccount,
                                                          Map<String, BigDecimal> pluginRates) {
        return new SourceIncomeTaxConfiguration(enabled, defaultRate, governmentAccount, pluginRates);
    }

    @Inject
    public SourceIncomeTaxConfiguration(Treasury plugin) {
        this.plugin = plugin;
        load();
    }

    /** Re-reads from the (already-reloaded) config. No-op for test instances. */
    public void reload() {
        if (plugin != null) load();
    }

    private void load() {
        FileConfiguration cfg = plugin.getConfig();

        this.enabled = cfg.getBoolean("tax.source-income-tax.enabled", false);
        // Parse from the string form (not getDouble) so no IEEE-754 error reaches
        // the ledger, and range-check to [0,1] — the same handling the per-plugin
        // rates below already use. A malformed or out-of-range default is dropped
        // to 0 rather than silently taxing at a nonsense rate.
        this.defaultRate = parseDefaultRate(cfg.getString("tax.source-income-tax.default-rate", "0"));

        String govAccount = cfg.getString("tax.source-income-tax.government-account", "DCGovernment");
        this.governmentAccount = (govAccount == null || govAccount.isBlank()) ? "DCGovernment" : govAccount;

        ConfigurationSection ratesSection = cfg.getConfigurationSection("tax.source-income-tax.plugin-rates");
        Map<String, BigDecimal> rates = new HashMap<>();
        if (ratesSection != null) {
            for (String key : ratesSection.getKeys(false)) {
                String raw = ratesSection.getString(key);
                if (raw == null) continue;
                try {
                    BigDecimal rate = new BigDecimal(raw);
                    if (rate.compareTo(BigDecimal.ZERO) < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
                        log.warn("Source income tax rate for plugin '{}' is out of range [0,1]: {} — skipping", key, raw);
                        continue;
                    }
                    rates.put(key, rate);
                } catch (NumberFormatException e) {
                    log.warn("Invalid source income tax rate for plugin '{}': '{}' — skipping", key, raw);
                }
            }
        }
        this.pluginRates = Collections.unmodifiableMap(rates);

        if (enabled) {
            log.info("Source income tax enabled: default-rate={}, government-account={}, plugin-specific-rates={}",
                    defaultRate, governmentAccount, pluginRates.size());
        }
    }

    /**
     * Parse the default rate from its configured string form and constrain it to
     * the valid {@code [0,1]} range. Falls back to {@link BigDecimal#ZERO} on a
     * blank, malformed, or out-of-range value (logging a warning), so a bad config
     * can never tax at a negative or {@code >100%} rate.
     */
    private static BigDecimal parseDefaultRate(String raw) {
        if (raw == null || raw.isBlank()) return BigDecimal.ZERO;
        try {
            BigDecimal rate = new BigDecimal(raw.trim());
            if (rate.compareTo(BigDecimal.ZERO) < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
                log.warn("Source income tax default-rate is out of range [0,1]: {} — defaulting to 0", raw);
                return BigDecimal.ZERO;
            }
            return rate;
        } catch (NumberFormatException e) {
            log.warn("Invalid source income tax default-rate: '{}' — defaulting to 0", raw);
            return BigDecimal.ZERO;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public BigDecimal getDefaultRate() {
        return defaultRate;
    }

    public String getGovernmentAccount() {
        return governmentAccount;
    }

    /**
     * Returns the effective income tax rate for a deposit originating from the given plugin.
     * Falls back to {@link #getDefaultRate()} if no specific rate is configured for that plugin.
     */
    public BigDecimal getEffectiveRate(String pluginName) {
        return pluginRates.getOrDefault(pluginName, defaultRate);
    }
}
