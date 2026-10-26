package io.paradaux.chestshop.integration;

import org.bukkit.plugin.Plugin;

/**
 * A soft-dependency integration — ChestShop's hook into one external plugin. One
 * implementation per plugin, named {@code <PluginName>Integration} and contributed to the
 * Guice {@code Multibinder<Integration>} in {@code ChestShopModule}. The
 * {@link IntegrationRegistrar} detects the target plugin and calls {@link #hook} once it's
 * present and enabled — at ChestShop enable, and again for any plugin that enables later.
 *
 * <p>This replaced the {@code Dependencies} switch-on-an-enum god-class (PAR-307): adding a
 * new integration is now a new {@code Integration} + one binding, with no central class to edit.
 */
public interface Integration {

    /** The Bukkit plugin name this integration targets, as {@code PluginManager#getPlugin} sees it. */
    String pluginName();

    /**
     * Wire this integration. Called once — the registrar guarantees the target plugin is present
     * and enabled before calling, and won't call again once hooked.
     *
     * @param plugin the (present, enabled) target plugin
     * @return {@code true} if the integration actually hooked; {@code false} if it declined
     *         (e.g. config gated it off), so the registrar can retry / not mark it hooked
     */
    boolean hook(Plugin plugin);

    /**
     * Whether ChestShop cannot function without this plugin. A missing required integration
     * fails enable (as the economy does). Defaults to {@code false} — soft-depends are optional.
     */
    default boolean required() {
        return false;
    }
}
