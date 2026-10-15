package io.paradaux.business.integration;

import io.paradaux.business.api.BusinessApi;
import org.bukkit.plugin.java.JavaPlugin;

/** Keeps optional PlaceholderAPI types out of the plugin's reflected members. */
public final class BusinessPlaceholderHook {
    private BusinessPlaceholderHook() { }

    public static Runnable register(JavaPlugin plugin, BusinessApi api) {
        if (!plugin.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            return () -> { };
        }
        BusinessPlaceholderExpansion expansion =
                new BusinessPlaceholderExpansion(api, plugin.getPluginMeta().getVersion());
        if (!expansion.register()) {
            plugin.getLogger().warning("Could not register Business placeholders: the identifier may already be in use.");
            return () -> { };
        }
        return expansion;
    }
}
