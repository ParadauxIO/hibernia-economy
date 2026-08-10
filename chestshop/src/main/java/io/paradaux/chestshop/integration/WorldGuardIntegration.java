package io.paradaux.chestshop.integration;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.BukkitWorldConfiguration;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.internal.permission.RegionPermissionModel;
import com.sk89q.worldguard.internal.platform.WorldGuardPlatform;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.flags.registry.FlagConflictException;
import com.sk89q.worldguard.protection.managers.RegionManager;
import io.paradaux.chestshop.model.BuildPermission;
import io.paradaux.chestshop.model.ProtectionCheck;
import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import io.paradaux.chestshop.services.ProtectionService;
import io.paradaux.chestshop.utils.BlockUtil;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.plugin.Plugin;

/**
 * WorldGuard soft-dependency, self-contained: region/built-in chest protection (the custom
 * {@code allow-shop} flag) and, when enabled, region-gated shop creation — both config-gated
 * ({@code WORLDGUARD_USE_PROTECTION} / {@code WORLDGUARD_INTEGRATION}). The providers are wired
 * into {@link ProtectionService} as method refs.
 *
 * <p>The WorldGuard-API code lives in the private nested {@link ShopFlag}/{@link Protection}/
 * {@link Building} classes so those {@code com.sk89q} types are only class-loaded once
 * WorldGuard is confirmed present: this integration is instantiated at enable to build the
 * {@code Set<Integration>} even when WorldGuard is absent (a softdepend), so the outer class
 * must stay free of {@code com.sk89q} references.
 */
@Singleton
public class WorldGuardIntegration implements Integration {

    private final ProtectionService protection;
    private final ChestShopConfiguration config;

    @Inject
    public WorldGuardIntegration(ProtectionService protection, ChestShopConfiguration config) {
        this.protection = protection;
        this.config = config;
    }

    @Override
    public String pluginName() {
        return "WorldGuard";
    }

    @Override
    public boolean hook(Plugin plugin) {
        if (!config.isWorldguardUseProtection() && !config.isWorldguardIntegration()) {
            return false;
        }
        if (config.isWorldguardUseProtection()) {
            protection.setWorldGuardProtection(new Protection(plugin)::onProtectionCheck);
        }
        if (config.isWorldguardIntegration()) {
            protection.setWorldGuardBuilding(new Building(plugin, config)::canBuild);
        }
        return true;
    }

    /**
     * Register the custom {@code allow-shop} flag. Must run in {@code onLoad} (before WorldGuard
     * enables and locks its flag registry) — that is pre-Guice, so {@code ChestShop.onLoad} calls
     * this statically, guarded by a WorldGuard-present check.
     */
    public static void registerFlag() {
        ShopFlag.ENABLE_SHOP.getName();
    }

    /** The {@code allow-shop} WorldGuard flag — isolated so its static init runs only on demand. */
    private static final class ShopFlag {
        static final StateFlag ENABLE_SHOP;

        static {
            StateFlag flag;
            try {
                flag = new StateFlag("allow-shop", false);
                WorldGuard.getInstance().getFlagRegistry().register(flag);
            } catch (FlagConflictException | IllegalStateException e) {
                flag = (StateFlag) WorldGuard.getInstance().getFlagRegistry().get("allow-shop");
            }
            ENABLE_SHOP = flag;
        }

        private ShopFlag() {
        }
    }

    /** WorldGuard region / built-in chest protection (WORLDGUARD_USE_PROTECTION). */
    private static final class Protection {
        private final WorldGuardPlugin worldGuard;
        private final WorldGuardPlatform platform;

        Protection(Plugin plugin) {
            this.worldGuard = (WorldGuardPlugin) plugin;
            this.platform = WorldGuard.getInstance().getPlatform();
        }

        void onProtectionCheck(ProtectionCheck event) {
            if (event.getResult() == Event.Result.DENY) {
                return;
            }

            Block block = event.getBlock();
            Player player = event.getPlayer();
            LocalPlayer localPlayer = worldGuard.wrapPlayer(player);
            Location location = BukkitAdapter.adapt(block.getLocation());

            if (!canAccess(localPlayer, block, location)) {
                event.setResult(Event.Result.DENY);
                return;
            }

            RegionManager manager = platform.getRegionContainer().get((World) location.getExtent());
            if (manager == null) {
                return;
            }
            ApplicableRegionSet set = manager.getApplicableRegions(location.toVector().toBlockPoint());

            StateFlag flag = Flags.CHEST_ACCESS;
            if (BlockUtil.isSign(block)) {
                flag = Flags.USE;
            }

            if (!canAccess(localPlayer, (World) location.getExtent(), set, flag)) {
                event.setResult(Event.Result.DENY);
            }
        }

        private boolean canAccess(LocalPlayer player, Block block, Location location) {
            BukkitWorldConfiguration wcfg = (BukkitWorldConfiguration) platform.getGlobalStateManager().get((World) location.getExtent());
            return !wcfg.signChestProtection
                    || !wcfg.getChestProtection().isChest(BukkitAdapter.asBlockType(block.getType()))
                    || !wcfg.getChestProtection().isProtected(location, player);
        }

        private boolean canAccess(LocalPlayer player, World world, ApplicableRegionSet set, StateFlag flag) {
            return new RegionPermissionModel(player).mayIgnoreRegionProtection(world)
                    || set.testState(player, Flags.BUILD)
                    || set.testState(player, flag);
        }
    }

    /** WorldGuard region gating for shop creation (WORLDGUARD_INTEGRATION). */
    private static final class Building {
        private final WorldGuardPlugin worldGuard;
        private final WorldGuardPlatform platform;
        private final ChestShopConfiguration config;

        Building(Plugin plugin, ChestShopConfiguration config) {
            this.worldGuard = (WorldGuardPlugin) plugin;
            this.platform = WorldGuard.getInstance().getPlatform();
            this.config = config;
        }

        void canBuild(BuildPermission event) {
            ApplicableRegionSet regions = getApplicableRegions(event.getSign().getBlock().getLocation());

            if (regions == null) {
                event.allow(false);
            } else if (config.isWorldguardUseFlag()) {
                event.allow(regions.queryState(worldGuard.wrapPlayer(event.getPlayer()), ShopFlag.ENABLE_SHOP) == StateFlag.State.ALLOW);
            } else {
                event.allow(regions.size() > 0);
            }
        }

        private ApplicableRegionSet getApplicableRegions(org.bukkit.Location location) {
            RegionManager regionManager = platform.getRegionContainer().get(BukkitAdapter.adapt(location.getWorld()));
            if (regionManager == null) {
                return null;
            }
            return regionManager.getApplicableRegions(BukkitAdapter.adapt(location).toVector().toBlockPoint());
        }
    }
}
