package io.paradaux.treasury.model.config;

import com.google.inject.Inject;
import io.paradaux.common.ProgressiveTaxBrackets;
import io.paradaux.common.TaxPeriod;
import io.paradaux.treasury.Treasury;
import io.paradaux.treasury.utils.TreasuryConstants;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Configuration for the progressive income tax.
 *
 * <p>Every payment into a player's PERSONAL account adds to that player's income for
 * the current period, and is taxed at the marginal rate(s) of the bracket(s) it moves
 * the running total through. The total resets when a new period starts; periods are
 * {@code period} long and repeat from {@code period-anchor}, so every player resets at
 * the same moment.
 *
 * <p>Transfers between accounts the player owns, and transfers from the exempt source
 * accounts / plugins below, are not income. {@code tax-player-payments} and
 * {@code tax-chestshop-sales} switch {@code /pay} and ChestShop sale proceeds in or out.
 *
 * <p>Config location: {@code config.yml} under the {@code tax.progressive-income-tax:} key.
 */
@Slf4j
public class ProgressiveIncomeTaxConfiguration {

    static final String PATH = "tax.progressive-income-tax";
    static final String DEFAULT_PERIOD = "30d";
    static final String DEFAULT_ANCHOR = "2026-01-01T00:00:00Z";
    static final List<String> DEFAULT_EXEMPT_SOURCE_ACCOUNTS = List.of("starting-balances", "Eco");
    /** Plugin name ChestShop moves sale proceeds under (its Treasury transfers and Vault deposits). */
    static final String CHESTSHOP_PLUGIN = "ChestShop";

    static final boolean DEFAULT_TAX_PLAYER_PAYMENTS = true;
    /** Off: ChestShop charges its own sales tax, so taxing the proceeds as income would tax a sale twice. */
    static final boolean DEFAULT_TAX_CHESTSHOP_SALES = false;

    /** Immutable values retained by an operation even when a reload publishes newer values. */
    public record Snapshot(
            boolean enabled,
            String governmentAccount,
            TaxPeriod period,
            ProgressiveTaxBrackets brackets,
            Set<String> exemptSourceAccounts,
            Set<String> exemptPlugins,
            boolean taxPlayerPayments,
            boolean taxChestShopSales) {
        public Snapshot {
            exemptSourceAccounts = lowerCased(exemptSourceAccounts);
            exemptPlugins = lowerCased(exemptPlugins);
        }

        /** With the default switches: player payments taxed, ChestShop sales not. */
        public Snapshot(
                boolean enabled,
                String governmentAccount,
                TaxPeriod period,
                ProgressiveTaxBrackets brackets,
                Set<String> exemptSourceAccounts,
                Set<String> exemptPlugins) {
            this(
                    enabled,
                    governmentAccount,
                    period,
                    brackets,
                    exemptSourceAccounts,
                    exemptPlugins,
                    DEFAULT_TAX_PLAYER_PAYMENTS,
                    DEFAULT_TAX_CHESTSHOP_SALES);
        }

        /** {@code true} when income from the named GOVERNMENT/SYSTEM source account is not taxed. */
        public boolean isExemptSourceAccount(String displayName) {
            return displayName != null && exemptSourceAccounts.contains(displayName.toLowerCase(Locale.ROOT));
        }

        /**
         * {@code true} when income moved by the named plugin is not taxed: it is listed in
         * {@code exempt-plugins}, or it is {@code /pay} / ChestShop with its switch off.
         */
        public boolean isExemptPlugin(String pluginName) {
            if (pluginName == null) {
                return false;
            }
            if (!taxPlayerPayments && pluginName.equalsIgnoreCase(TreasuryConstants.PAY_PLUGIN_SYSTEM)) {
                return true;
            }
            if (!taxChestShopSales && pluginName.equalsIgnoreCase(CHESTSHOP_PLUGIN)) {
                return true;
            }
            return exemptPlugins.contains(pluginName.toLowerCase(Locale.ROOT));
        }

        /** Enabled and with at least one positive marginal rate. */
        public boolean isActive() {
            return enabled && !brackets.isEmpty();
        }
    }

    private volatile Snapshot snapshot;
    private final Treasury plugin;

    public Snapshot snapshot() {
        return snapshot;
    }

    private ProgressiveIncomeTaxConfiguration(Snapshot snapshot) {
        this.plugin = null;
        this.snapshot = snapshot;
    }

    /** Test-only factory: bypasses Bukkit config parsing. Production code uses the {@code @Inject} ctor. */
    public static ProgressiveIncomeTaxConfiguration forTesting(Snapshot snapshot) {
        return new ProgressiveIncomeTaxConfiguration(Objects.requireNonNull(snapshot));
    }

    @Inject
    public ProgressiveIncomeTaxConfiguration(Treasury plugin) {
        this.plugin = plugin;
        snapshot = read(plugin.getConfig(), false);
    }

    /** Re-reads from the (already-reloaded) config. No-op for test instances. */
    public void reload() {
        if (plugin != null) snapshot = prepareReload(plugin.getConfig());
    }

    /** Parse completely before publishing; exceptions leave the live snapshot untouched. */
    public Snapshot prepareReload(FileConfiguration cfg) {
        ReloadValidation.section(cfg, PATH);
        ReloadValidation.scalar(cfg, PATH + ".enabled", Boolean.class);
        ReloadValidation.scalar(cfg, PATH + ".government-account", String.class);
        ReloadValidation.scalar(cfg, PATH + ".period", String.class);
        // period-anchor is validated by parsing it in read(): an unquoted YAML date
        // arrives as a java.util.Date rather than a String, and both are accepted.
        ReloadValidation.entries(cfg, PATH + ".brackets", true, true);
        ReloadValidation.stringList(cfg, PATH + ".exempt-source-accounts");
        ReloadValidation.stringList(cfg, PATH + ".exempt-plugins");
        ReloadValidation.scalar(cfg, PATH + ".tax-player-payments", Boolean.class);
        ReloadValidation.scalar(cfg, PATH + ".tax-chestshop-sales", Boolean.class);
        return read(cfg, true);
    }

    public void publish(Snapshot replacement) {
        snapshot = Objects.requireNonNull(replacement);
    }

    /**
     * @param strict when {@code true} (reload) an invalid period throws, rejecting the
     *               reload; at startup it is logged and the tax stays disabled instead.
     */
    private Snapshot read(FileConfiguration cfg, boolean strict) {
        boolean enabled = cfg.getBoolean(PATH + ".enabled", false);

        String gov = cfg.getString(PATH + ".government-account", "DCGovernment");
        String governmentAccount = (gov == null || gov.isBlank()) ? "DCGovernment" : gov;

        TaxPeriod period;
        try {
            Duration length = TaxPeriod.parseLength(cfg.getString(PATH + ".period", DEFAULT_PERIOD));
            Instant anchor = readAnchor(cfg.get(PATH + ".period-anchor"));
            period = TaxPeriod.of(length, anchor);
        } catch (IllegalArgumentException e) {
            if (strict) {
                throw new IllegalArgumentException(PATH + ": " + e.getMessage(), e);
            }
            log.error("Progressive income tax disabled: {}", e.getMessage());
            enabled = false;
            period = TaxPeriod.of(TaxPeriod.parseLength(DEFAULT_PERIOD), TaxPeriod.parseAnchor(DEFAULT_ANCHOR));
        }

        Map<String, String> raw = new LinkedHashMap<>();
        ConfigurationSection sec = cfg.getConfigurationSection(PATH + ".brackets");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                raw.put(key, sec.getString(key, "0"));
            }
        }
        ProgressiveTaxBrackets brackets = ProgressiveTaxBrackets.fromRawEntries(raw, log::warn);

        List<String> exemptAccounts = cfg.isList(PATH + ".exempt-source-accounts")
                ? cfg.getStringList(PATH + ".exempt-source-accounts")
                : DEFAULT_EXEMPT_SOURCE_ACCOUNTS;
        List<String> exemptPlugins = cfg.getStringList(PATH + ".exempt-plugins");

        boolean taxPlayerPayments = cfg.getBoolean(PATH + ".tax-player-payments", DEFAULT_TAX_PLAYER_PAYMENTS);
        boolean taxChestShopSales = cfg.getBoolean(PATH + ".tax-chestshop-sales", DEFAULT_TAX_CHESTSHOP_SALES);

        Snapshot result = new Snapshot(
                enabled,
                governmentAccount,
                period,
                brackets,
                Set.copyOf(exemptAccounts),
                Set.copyOf(exemptPlugins),
                taxPlayerPayments,
                taxChestShopSales);
        if (enabled) {
            log.info(
                    "Progressive income tax enabled: {} bracket(s), period={} from {}, government-account={}",
                    brackets.size(),
                    period.length(),
                    period.anchor(),
                    governmentAccount);
            if (brackets.isEmpty()) {
                log.warn(
                        "Progressive income tax is enabled but no bracket has a positive rate — nothing will be taxed");
            }
        }
        return result;
    }

    /**
     * YAML reads an unquoted {@code 2026-01-01} or {@code 2026-01-01T00:00:00Z} as a
     * timestamp ({@link java.util.Date}, UTC); a quoted value stays a string.
     */
    private static Instant readAnchor(Object raw) {
        if (raw == null) return TaxPeriod.parseAnchor(DEFAULT_ANCHOR);
        if (raw instanceof java.util.Date date) return date.toInstant();
        return TaxPeriod.parseAnchor(raw.toString());
    }

    private static Set<String> lowerCased(Set<String> names) {
        return names.stream()
                .filter(Objects::nonNull)
                .map(n -> n.trim().toLowerCase(Locale.ROOT))
                .filter(n -> !n.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean isEnabled() {
        return snapshot.enabled();
    }

    public String getGovernmentAccount() {
        return snapshot.governmentAccount();
    }
}
