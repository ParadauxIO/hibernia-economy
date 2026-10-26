package io.paradaux.chestshop.integration;
import lombok.extern.slf4j.Slf4j;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.chestshop.ChestShop;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;

import java.util.HashSet;
import java.util.Set;

/**
 * Detects and hooks ChestShop's {@link Integration}s. Replaces the {@code Dependencies}
 * god-class (PAR-307): the set of integrations is contributed by a Guice
 * {@code Multibinder<Integration>}, and this class only orchestrates when each one hooks.
 *
 * <p>At enable {@link #hookAll} hooks every integration whose plugin is already present and
 * enabled, and fails (returns {@code false}) if a {@linkplain Integration#required() required}
 * one is missing. As a {@link Listener}, it also hooks integrations whose plugin enables
 * <em>after</em> ChestShop.
 */
@Singleton
@Slf4j
public class IntegrationRegistrar implements Listener {

    private final Set<Integration> integrations;
    private final Set<String> hooked = new HashSet<>();

    @Inject
    public IntegrationRegistrar(Set<Integration> integrations) {
        this.integrations = integrations;
    }

    /**
     * Hook every integration whose plugin is present and enabled.
     *
     * @return {@code false} if a required integration is unavailable (caller should disable
     *         ChestShop), {@code true} otherwise
     */
    public boolean hookAll() {
        for (Integration integration : integrations) {
            Plugin plugin = Bukkit.getPluginManager().getPlugin(integration.pluginName());
            if (plugin != null && plugin.isEnabled()) {
                tryHook(integration, plugin);
            }
        }

        for (Integration integration : integrations) {
            if (integration.required() && !hooked.contains(integration.pluginName())) {
                log.error("Required integration missing: "
                        + integration.pluginName() + " — you need to install it!");
                return false;
            }
        }
        return true;
    }

    private void tryHook(Integration integration, Plugin plugin) {
        if (hooked.contains(integration.pluginName())) {
            return;
        }
        try {
            if (integration.hook(plugin)) {
                hooked.add(integration.pluginName());
                log.info(integration.pluginName() + " "
                        + plugin.getDescription().getVersion() + " hooked.");
            }
        } catch (Exception e) {
            log.warn("Unable to hook into " + integration.pluginName(), e);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginEnable(PluginEnableEvent event) {
        Plugin plugin = event.getPlugin();
        for (Integration integration : integrations) {
            boolean matches = integration.pluginName().equalsIgnoreCase(plugin.getName())
                    || plugin.getDescription().getProvides().stream()
                            .anyMatch(integration.pluginName()::equalsIgnoreCase);
            if (matches) {
                tryHook(integration, plugin);
            }
        }
    }
}
