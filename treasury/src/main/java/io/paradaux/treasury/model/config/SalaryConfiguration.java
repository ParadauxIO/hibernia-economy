package io.paradaux.treasury.model.config;

import com.google.inject.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import io.paradaux.treasury.Treasury;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Government salary configuration.
 *
 * <p>A repeating timer pays online players a salary from a GOVERNMENT account
 * (default {@code DCGovernment}) based on their LuckPerms group membership. A
 * player in several salaried groups is paid the <em>highest</em> single salary
 * (their top-paying role) — salaries are not summed. Players who are offline, or
 * whose groups carry no positive salary, are not paid.
 *
 * <p>Config location: {@code config.yml} under the {@code salaries:} key.
 * <pre>
 *   salaries:
 *     enabled: true
 *     government-account: "DCGovernment"
 *     interval: 900            # seconds between payouts
 *     amount:
 *       senator: 65.0
 *       ...
 * </pre>
 */
@Slf4j
@Getter
public class SalaryConfiguration {

    private static final String DEFAULT_GOV_ACCOUNT = "DCGovernment";
    private static final long DEFAULT_INTERVAL_SECONDS = 900L; // 15 minutes

    // Mutable so {@link #reload()} can refresh them at runtime (Guice singleton —
    // SalaryService reads enabled/amounts/account live each cycle). volatile so the
    // async payout cycle sees a fully-published value after /treasury reload
    // re-populates these in place, rather than a stale or torn read (ADT-30). Note
    // the payout INTERVAL is fixed when the timer is scheduled at startup; changing
    // it needs a restart.
    private volatile boolean enabled;
    private volatile String governmentAccount;
    /** Seconds between payout cycles. */
    private volatile long intervalSeconds;
    /** LuckPerms group name (lower-cased) → salary amount. */
    private volatile Map<String, BigDecimal> amounts;
    /** Don't pay players who are AFK (per the LuckPerms AFK context). Default true. */
    private volatile boolean skipAfk = true;
    /** LuckPerms context key that marks a player AFK (e.g. {@code afk}). */
    private volatile String afkContextKey = "afk";
    /** LuckPerms context value (paired with {@link #afkContextKey}) that marks a player AFK. */
    private volatile String afkContextValue = "true";
    /** Set only via the {@code @Inject} ctor; null for test-factory instances. */
    private Treasury plugin;

    private SalaryConfiguration(boolean enabled, String governmentAccount, long intervalSeconds,
                                Map<String, BigDecimal> amounts) {
        this.enabled = enabled;
        this.governmentAccount = (governmentAccount == null || governmentAccount.isBlank())
                ? DEFAULT_GOV_ACCOUNT : governmentAccount;
        this.intervalSeconds = intervalSeconds > 0 ? intervalSeconds : DEFAULT_INTERVAL_SECONDS;
        this.amounts = normalise(amounts);
    }

    /** Test-only factory: bypasses Bukkit config parsing. */
    public static SalaryConfiguration forTesting(boolean enabled, String governmentAccount,
                                                 long intervalSeconds, Map<String, BigDecimal> amounts) {
        return new SalaryConfiguration(enabled, governmentAccount, intervalSeconds, amounts);
    }

    @Inject
    public SalaryConfiguration(Treasury plugin) {
        this.plugin = plugin;
        load();
    }

    /** Re-reads from the (already-reloaded) config. No-op for test instances. */
    public void reload() {
        if (plugin != null) load();
    }

    private void load() {
        FileConfiguration cfg = plugin.getConfig();

        this.enabled = cfg.getBoolean("salaries.enabled", false);

        String gov = cfg.getString("salaries.government-account", DEFAULT_GOV_ACCOUNT);
        this.governmentAccount = (gov == null || gov.isBlank()) ? DEFAULT_GOV_ACCOUNT : gov;

        long iv = cfg.getLong("salaries.interval", DEFAULT_INTERVAL_SECONDS);
        this.intervalSeconds = iv > 0 ? iv : DEFAULT_INTERVAL_SECONDS;

        Map<String, BigDecimal> parsed = new HashMap<>();
        ConfigurationSection sec = cfg.getConfigurationSection("salaries.amount");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                // Parse from the string form (not getDouble) so salary amounts
                // carry no IEEE-754 error into the ledger; skip malformed values.
                String raw = sec.getString(key, "0");
                BigDecimal amt;
                try {
                    amt = new BigDecimal(raw == null ? "0" : raw.trim());
                } catch (NumberFormatException e) {
                    log.warn("Invalid salary for group '{}': '{}' — skipping", key, raw);
                    continue;
                }
                if (amt.signum() < 0) {
                    log.warn("Negative salary for group '{}' ({}) — skipping", key, amt);
                    continue;
                }
                parsed.put(key.toLowerCase(Locale.ROOT), amt);
            }
        }
        this.amounts = normalise(parsed);

        this.skipAfk = cfg.getBoolean("salaries.skip-afk", true);
        String afkKey = cfg.getString("salaries.afk-context-key", "afk");
        this.afkContextKey = (afkKey == null || afkKey.isBlank()) ? "afk" : afkKey;
        String afkVal = cfg.getString("salaries.afk-context-value", "true");
        this.afkContextValue = (afkVal == null || afkVal.isBlank()) ? "true" : afkVal;

        if (enabled) {
            log.info("Government salaries enabled: {} group(s), every {}s, from {} (skip-afk={})",
                    amounts.size(), intervalSeconds, governmentAccount, skipAfk);
        }
    }

    private static Map<String, BigDecimal> normalise(Map<String, BigDecimal> in) {
        Map<String, BigDecimal> m = new HashMap<>();
        if (in != null) {
            in.forEach((k, v) -> {
                if (k != null && v != null) m.put(k.toLowerCase(Locale.ROOT), v);
            });
        }
        return Collections.unmodifiableMap(m);
    }

    /**
     * The highest-paying salaried group among the given group names, or empty if
     * none of them carry a positive salary. Matching is case-insensitive.
     *
     * @return an entry of (matched lower-cased group name, amount), or empty
     */
    public Optional<Map.Entry<String, BigDecimal>> highestSalary(Collection<String> groupNames) {
        Map.Entry<String, BigDecimal> best = null;
        if (groupNames != null) {
            for (String g : groupNames) {
                if (g == null) continue;
                BigDecimal amt = amounts.get(g.toLowerCase(Locale.ROOT));
                if (amt != null && amt.signum() > 0
                        && (best == null || amt.compareTo(best.getValue()) > 0)) {
                    best = Map.entry(g.toLowerCase(Locale.ROOT), amt);
                }
            }
        }
        return Optional.ofNullable(best);
    }
}
