package io.paradaux.business.model.config;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.business.Business;

/**
 * General firm settings.
 *
 * <p>Config location: {@code config.yml} under the {@code firm:} key.
 */
@Singleton
public class FirmConfiguration {

    private static final int DEFAULT_OWNED_FIRM_LIMIT = 3;
    private static final int DEFAULT_CREATE_COOLDOWN_SECONDS = 300;
    private static final int DEFAULT_MAX_SALES_EXPORT_DAYS = 30;
    private static final int DEFAULT_SALES_NOTIFY_FLUSH_SECONDS = 15;

    private final Business plugin;

    // Mutable so {@link #reload()} can refresh them from disk at runtime — this is
    // a Guice singleton, so services holding a reference see the new values.
    private int ownedFirmLimit;
    private int createCooldownSeconds;
    private String salesExplorerUrl;
    private int maxSalesExportDays;
    private boolean salesNotifyDefault;
    private int salesNotifyFlushSeconds;

    @Inject
    public FirmConfiguration(Business plugin) {
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
        // Max firms a single player may own (be proprietor of). 0 or below = unlimited.
        this.ownedFirmLimit = plugin.getConfig().getInt("firm.owned-limit", DEFAULT_OWNED_FIRM_LIMIT);
        plugin.getLogger().info(ownedFirmLimit > 0
                ? "Firm ownership limit: " + ownedFirmLimit + " per player"
                : "Firm ownership limit: unlimited");

        // Minimum seconds between a player creating firms. Keyed on creation time
        // (not active count) so rapid create/disband cycling is throttled too.
        // 0 or below = no cooldown.
        this.createCooldownSeconds = plugin.getConfig().getInt("firm.create-cooldown-seconds", DEFAULT_CREATE_COOLDOWN_SECONDS);
        plugin.getLogger().info(createCooldownSeconds > 0
                ? "Firm creation cooldown: " + createCooldownSeconds + "s per player"
                : "Firm creation cooldown: disabled");

        // economy-explorer base URL that /firm sales export deep-links into (per
        // tenant). Empty disables the export command. The day cap mirrors the
        // legacy max-sales-export-days.
        this.salesExplorerUrl = plugin.getConfig().getString("sales.explorer-url", "");
        this.maxSalesExportDays = plugin.getConfig().getInt("sales.max-export-days", DEFAULT_MAX_SALES_EXPORT_DAYS);

        // Real-time firm sale notifications: per-firm default state (opt-in) and the
        // digest flush cadence — bursts within a window are condensed into one message.
        this.salesNotifyDefault = plugin.getConfig().getBoolean("sales.notify-default", false);
        this.salesNotifyFlushSeconds = Math.max(1,
                plugin.getConfig().getInt("sales.notify-flush-seconds", DEFAULT_SALES_NOTIFY_FLUSH_SECONDS));
    }

    /** Maximum number of firms a player may own; {@code <= 0} means unlimited. */
    public int getOwnedFirmLimit() {
        return ownedFirmLimit;
    }

    /** Whether a finite ownership limit is enforced. */
    public boolean hasOwnedFirmLimit() {
        return ownedFirmLimit > 0;
    }

    /** Minimum seconds between firm creations per player; {@code <= 0} means no cooldown. */
    public int getCreateCooldownSeconds() {
        return createCooldownSeconds;
    }

    /** Whether a creation cooldown is enforced. */
    public boolean hasCreateCooldown() {
        return createCooldownSeconds > 0;
    }

    /** economy-explorer base URL for sales-export deep links; blank if unset. */
    public String getSalesExplorerUrl() {
        return salesExplorerUrl;
    }

    /** Whether a sales-export explorer URL is configured. */
    public boolean hasSalesExplorerUrl() {
        return salesExplorerUrl != null && !salesExplorerUrl.isBlank();
    }

    /** Maximum window (days) a sales export may cover; {@code <= 0} means unlimited. */
    public int getMaxSalesExportDays() {
        return maxSalesExportDays;
    }

    /** Default per-firm state for real-time sale notifications (opt-in by default). */
    public boolean isSalesNotifyDefault() {
        return salesNotifyDefault;
    }

    /** Seconds between sale-notification digest flushes (>= 1). */
    public int getSalesNotifyFlushSeconds() {
        return salesNotifyFlushSeconds;
    }
}
