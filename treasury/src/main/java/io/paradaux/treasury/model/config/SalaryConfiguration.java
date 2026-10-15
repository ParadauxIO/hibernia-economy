package io.paradaux.treasury.model.config;

import com.google.inject.Inject;
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
public class SalaryConfiguration {

    private static final String DEFAULT_GOV_ACCOUNT = "DCGovernment";
    private static final long DEFAULT_INTERVAL_SECONDS = 900L; // 15 minutes

    /** Immutable values retained by an operation even when a reload publishes newer values. */
    public record Snapshot(boolean enabled, String governmentAccount, long intervalSeconds, Map<String, BigDecimal> amounts, boolean skipAfk, String afkContextKey, String afkContextValue) {
        public Snapshot { amounts = Map.copyOf(amounts); }


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
        public boolean isEnabled() { return enabled; }
        public String getGovernmentAccount() { return governmentAccount; }
        public long getIntervalSeconds() { return intervalSeconds; }
        public Map<String, BigDecimal> getAmounts() { return amounts; }
        public boolean isSkipAfk() { return skipAfk; }
        public String getAfkContextKey() { return afkContextKey; }
        public String getAfkContextValue() { return afkContextValue; }
    }

    private volatile Snapshot snapshot;
    private final Treasury plugin;

    public Snapshot snapshot() { return snapshot; }

    private SalaryConfiguration(boolean enabled, String governmentAccount, long intervalSeconds,
                                Map<String, BigDecimal> amounts) {
        this.plugin = null;
        governmentAccount = (governmentAccount == null || governmentAccount.isBlank())
                ? DEFAULT_GOV_ACCOUNT : governmentAccount;
        intervalSeconds = intervalSeconds > 0 ? intervalSeconds : DEFAULT_INTERVAL_SECONDS;
        amounts = normalise(amounts);
        this.snapshot = new Snapshot(enabled, governmentAccount, intervalSeconds, amounts, true, "afk", "true");
    }

    /** Test-only factory: bypasses Bukkit config parsing. */
    public static SalaryConfiguration forTesting(boolean enabled, String governmentAccount,
                                                 long intervalSeconds, Map<String, BigDecimal> amounts) {
        return new SalaryConfiguration(enabled, governmentAccount, intervalSeconds, amounts);
    }

    @Inject
    public SalaryConfiguration(Treasury plugin) {
        this.plugin = plugin;
        snapshot = read(plugin.getConfig(), true);
    }

    /** Re-reads from the (already-reloaded) config. No-op for test instances. */
    public void reload() {
        if (plugin != null) snapshot = prepareReload(plugin.getConfig());
    }

    /** Parse completely before publishing; exceptions leave the live snapshot untouched. */
    public Snapshot prepareReload(FileConfiguration cfg) {
        ReloadValidation.section(cfg, "salaries");
        ReloadValidation.scalar(cfg, "salaries.enabled", Boolean.class);
        ReloadValidation.scalar(cfg, "salaries.government-account", String.class);
        ReloadValidation.scalar(cfg, "salaries.skip-afk", Boolean.class);
        ReloadValidation.scalar(cfg, "salaries.afk-context-key", String.class);
        ReloadValidation.scalar(cfg, "salaries.afk-context-value", String.class);
        ReloadValidation.entries(cfg, "salaries.amount", false, false);
        return read(cfg, false);
    }

    public void publish(Snapshot replacement) {
        snapshot = java.util.Objects.requireNonNull(replacement);
    }

    private Snapshot read(FileConfiguration cfg, boolean startup) {

        boolean enabled = cfg.getBoolean("salaries.enabled", false);

        String gov = cfg.getString("salaries.government-account", DEFAULT_GOV_ACCOUNT);
        String governmentAccount = (gov == null || gov.isBlank()) ? DEFAULT_GOV_ACCOUNT : gov;

        long iv = cfg.getLong("salaries.interval", DEFAULT_INTERVAL_SECONDS);
        long configuredInterval = iv > 0 ? iv : DEFAULT_INTERVAL_SECONDS;
        long intervalSeconds = startup ? configuredInterval : snapshot.intervalSeconds();
        if (!startup && configuredInterval != intervalSeconds) {
            log.warn("salaries.interval changed from {}s to {}s — ignored until restart; payouts continue every {}s.",
                    intervalSeconds, configuredInterval, intervalSeconds);
        }

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
        Map<String, BigDecimal> amounts = normalise(parsed);

        boolean skipAfk = cfg.getBoolean("salaries.skip-afk", true);
        String afkKey = cfg.getString("salaries.afk-context-key", "afk");
        String afkContextKey = (afkKey == null || afkKey.isBlank()) ? "afk" : afkKey;
        String afkVal = cfg.getString("salaries.afk-context-value", "true");
        String afkContextValue = (afkVal == null || afkVal.isBlank()) ? "true" : afkVal;

        if (enabled) {
            log.info("Government salaries enabled: {} group(s), every {}s, from {} (skip-afk={})",
                    amounts.size(), intervalSeconds, governmentAccount, skipAfk);
        }
        return new Snapshot(enabled, governmentAccount, intervalSeconds, amounts, skipAfk, afkContextKey, afkContextValue);
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
        return snapshot.highestSalary(groupNames);
    }
    public boolean isEnabled() { return snapshot.enabled(); }
    public String getGovernmentAccount() { return snapshot.governmentAccount(); }
    public long getIntervalSeconds() { return snapshot.intervalSeconds(); }
    public Map<String, BigDecimal> getAmounts() { return snapshot.amounts(); }
    public boolean isSkipAfk() { return snapshot.skipAfk(); }
    public String getAfkContextKey() { return snapshot.afkContextKey(); }
    public String getAfkContextValue() { return snapshot.afkContextValue(); }
}
