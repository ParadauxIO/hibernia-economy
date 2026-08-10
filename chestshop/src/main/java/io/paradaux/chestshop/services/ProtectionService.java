package io.paradaux.chestshop.services;

import io.paradaux.chestshop.model.BuildPermission;
import io.paradaux.chestshop.model.ProtectionCheck;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;

import javax.annotation.Nullable;
import java.util.function.Consumer;

/**
 * Owns all shop/chest protection checks, replacing the {@code ProtectionCheck} and
 * {@code BuildPermission} bus with direct, ordered calls. The vanilla shop-member check
 * always runs first; the optional WorldGuard/GriefPrevention integrations run after it only
 * when those plugins are hooked and their config flags are on — the integrations register
 * them here as method references (so this service never names the {@code com.sk89q}/
 * {@code me.ryanhamshire} classes, keeping them off the call path when the plugin is absent).
 *
 * <p>Absorbed the former {@code Security} shop-block facade (the caller-facing access/view/
 * sign-placement checks) and the {@code VanillaShopProtection} default provider (PAR-316).
 * Block-level protection (LWC/Lockette) was removed, so no provider ever claims shop blocks.
 */
public interface ProtectionService {

    /** Hook WorldGuard region/built-in protection into access checks (WORLDGUARD_USE_PROTECTION). */
    void setWorldGuardProtection(Consumer<ProtectionCheck> check);

    /** Hook WorldGuard region gating into shop-creation build checks (WORLDGUARD_INTEGRATION). */
    void setWorldGuardBuilding(Consumer<BuildPermission> check);

    /** Hook GriefPrevention claim gating into shop-creation build checks (GRIEFPREVENTION_INTEGRATION). */
    void setGriefPreventionBuilding(Consumer<BuildPermission> check);

    /** Whether {@code player} may access {@code block} (vanilla shop membership + WorldGuard). */
    boolean canAccess(Block block, Player player, boolean ignoreBuiltInProtection);

    /** Convenience for {@code canAccess(block, player, false)} (was {@code Security.canAccess}). */
    boolean canAccess(Player player, Block block);

    /** Whether {@code player} may view {@code block} (as {@link #canAccess} but without the manage check). */
    boolean canView(Block block, Player player, boolean ignoreBuiltInProtection);

    /**
     * Whether {@code player} may place a shop {@code sign} — no other player's shop hangs off the
     * same block (unless multi-shop blocks are allowed) and the player can access the surrounding
     * container(s) (was {@code Security.canPlaceSign}).
     */
    boolean canPlaceSign(Player player, Sign sign);

    /**
     * Whether a shop may be created at {@code sign}/{@code chest} by {@code player}.
     * Defaults to allowed; WorldGuard then GriefPrevention may disallow. Both ran with
     * {@code ignoreCancelled=true} (skip once disallowed), so each only runs while the
     * build is still allowed — WorldGuard first (it is loaded first).
     */
    boolean canBuild(Player player, @Nullable Location chest, Location sign);
}
