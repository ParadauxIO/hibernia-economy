package io.paradaux.business.model.config;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.business.Business;
import io.paradaux.common.BalanceTaxBrackets;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Configuration for the corporate balance tax feature.
 *
 * <p>On each WEEKLY tax cycle, firms pay a flat-bracket balance tax on their
 * total balance across all accounts. The rate is determined by which bracket
 * the total balance falls into.
 *
 * <p>Tax formula:
 * <pre>
 *   weekly_rate = bracket_rate(total_balance)
 *   tax         = total_balance × weekly_rate
 * </pre>
 *
 * Tax is split proportionally across each account and charged individually.
 * Firms with the {@code balance-tax.exempt} property set to {@code true} are skipped.
 *
 * <p>The bracket model (defaults, parsing, rate lookup) lives in the shared,
 * framework-free {@link BalanceTaxBrackets} (ADT-186); this class only reads the
 * Bukkit config and holds the live snapshot.
 *
 * <p>Config location: {@code config.yml} under the {@code tax.balance:} key.
 */
@Singleton
public class BalanceTaxConfiguration {

    private final Business plugin;

    // Mutable so {@link #reload()} can refresh them at runtime — this is a Guice
    // singleton, so the tax listener/command holding a reference see new values
    // on the next cycle. {@code brackets} is replaced wholesale (always an
    // unmodifiable snapshot), never mutated in place.
    private boolean enabled;
    private String governmentAccount;
    private volatile BalanceTaxBrackets brackets;

    @Inject
    public BalanceTaxConfiguration(Business plugin) {
        this.plugin = plugin;
        load();
    }

    /**
     * Re-reads the values from the (already-reloaded) {@code config.yml}. Callers
     * must run {@link Business#reloadConfig()} first so {@code getConfig()} is fresh.
     */
    public void reload() {
        load();
    }

    private void load() {
        Logger log = plugin.getLogger();
        FileConfiguration cfg = plugin.getConfig();

        this.enabled = cfg.getBoolean("tax.balance.enabled", false);

        String gov = cfg.getString("tax.balance.government-account", "DCGovernment");
        this.governmentAccount = (gov == null || gov.isBlank()) ? "DCGovernment" : gov;

        Map<String, String> raw = new LinkedHashMap<>();
        ConfigurationSection sec = cfg.getConfigurationSection("tax.balance.brackets");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                raw.put(key, sec.getString(key, "0"));
            }
        }
        this.brackets = BalanceTaxBrackets.fromRawEntries(raw, log::warning);

        if (enabled) {
            log.info("Corporate balance tax enabled: " + brackets.size() + " bracket(s), government-account=" + governmentAccount);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getGovernmentAccount() {
        return governmentAccount;
    }

    /**
     * Returns the weekly tax rate applicable to the given total balance.
     * Uses a flat-bracket model: the entire balance is taxed at the rate
     * of the bracket it falls into (not a marginal/progressive system).
     *
     * @param balance the total firm balance to evaluate
     * @return the weekly rate (e.g. {@code 0.01} for 1 %), or {@code BigDecimal.ZERO} if none match
     */
    public BigDecimal getWeeklyRate(BigDecimal balance) {
        return brackets.weeklyRate(balance);
    }
}
