package io.paradaux.business.integration;

import io.paradaux.business.api.BusinessApi;
import io.paradaux.business.model.Firm;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

/** Firm balances read exclusively through the public Business API (PAR-57). */
public final class BusinessPlaceholderExpansion extends PlaceholderExpansion implements Runnable {
    private static final String BALANCE_PREFIX = "firm_balance_";
    private final BusinessApi api;
    private final String version;

    public BusinessPlaceholderExpansion(BusinessApi api, String version) {
        this.api = api;
        this.version = version;
    }

    @Override
    public @NotNull String getIdentifier() { return "business"; }

    @Override
    public @NotNull String getAuthor() { return "ParadauxIO"; }

    @Override
    public @NotNull String getVersion() { return version; }

    @Override
    public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (!params.startsWith(BALANCE_PREFIX) || params.length() == BALANCE_PREFIX.length()) {
            return null;
        }
        // Keep the entire name, including underscores and spaces, intact.
        Firm firm = api.firms().getFirmByName(params.substring(BALANCE_PREFIX.length()));
        if (firm == null || Boolean.TRUE.equals(firm.getArchived())) {
            return null;
        }
        return api.firms().getTotalBalance(firm.getFirmId()).toPlainString();
    }

    /** Called when Business disables; persist() only covers PlaceholderAPI reloads. */
    @Override
    public void run() { unregister(); }
}
