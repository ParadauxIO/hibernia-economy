package io.paradaux.chestshop.model;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import javax.annotation.Nullable;

/**
 * Mutable carrier for whether a shop may be built at a location, run by
 * {@link io.paradaux.chestshop.services.ProtectionService#canBuild}. Defaults to allowed;
 * the WorldGuard/GriefPrevention checks may {@link #disallow}. Formerly a Bukkit event
 * (whose {@code ignoreCancelled} "skip once disallowed" semantics the service now applies
 * via {@link #isAllowed} guards).
 *
 * @author Acrobot
 */
public class BuildPermission {

    private Player player;
    private Location chest, sign;

    private boolean allowed = true;

    public BuildPermission(Player player, @Nullable Location chest, Location sign) {
        this.player = player;
        this.chest = chest;
        this.sign = sign;
    }

    public Player getPlayer() {
        return player;
    }

    public @Nullable Location getChest() {
        return chest;
    }

    public Location getSign() {
        return sign;
    }

    public void allow() {
        allowed = true;
    }

    public boolean isAllowed() {
        return allowed;
    }

    public void allow(boolean yesOrNot) {
        allowed = yesOrNot;
    }

    public void disallow() {
        allowed = false;
    }
}
