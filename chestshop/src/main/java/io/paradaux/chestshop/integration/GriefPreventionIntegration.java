package io.paradaux.chestshop.integration;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.chestshop.model.BuildPermission;
import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import io.paradaux.chestshop.services.ProtectionService;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import org.bukkit.plugin.Plugin;

/**
 * GriefPrevention soft-dependency, self-contained: gates shop creation on the player's claim
 * permissions when {@code GRIEFPREVENTION_INTEGRATION} is on, wired into
 * {@link ProtectionService} as a method ref.
 *
 * <p>The GriefPrevention-API code lives in the private nested {@link Building} class so
 * {@code me.ryanhamshire.GriefPrevention} is only class-loaded once GriefPrevention is
 * confirmed present (this integration is instantiated at enable to build the
 * {@code Set<Integration>} even when GriefPrevention is absent — a softdepend).
 */
@Singleton
public class GriefPreventionIntegration implements Integration {

    private final ProtectionService protection;
    private final ChestShopConfiguration config;

    @Inject
    public GriefPreventionIntegration(ProtectionService protection, ChestShopConfiguration config) {
        this.protection = protection;
        this.config = config;
    }

    @Override
    public String pluginName() {
        return "GriefPrevention";
    }

    @Override
    public boolean hook(Plugin plugin) {
        if (!config.isGriefpreventionIntegration()) {
            return false;
        }
        protection.setGriefPreventionBuilding(new Building(plugin)::canBuild);
        return true;
    }

    /** GriefPrevention claim gating for shop creation. */
    private static final class Building {
        private final GriefPrevention griefPrevention;

        Building(Plugin plugin) {
            this.griefPrevention = (GriefPrevention) plugin;
        }

        void canBuild(BuildPermission event) {
            event.allow(griefPrevention.dataStore.getClaimAt(event.getSign(), false, null) != null);
        }
    }
}
