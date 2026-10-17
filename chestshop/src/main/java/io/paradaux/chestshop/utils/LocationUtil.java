package io.paradaux.chestshop.utils;

import org.bukkit.Location;

/**
 * An utility class providing various methods to deal with locations
 *
 * @author Acrobot
 */
public final class LocationUtil {

    private LocationUtil() {
    }

    /** Placeholder used when a location's world is unloaded/absent (ADT-140). */
    private static final String UNKNOWN_WORLD = "?";

    /**
     * Returns a string representing the location
     *
     * @param location Location represented
     * @return Representation of the location
     */
    public static String locationToString(Location location) {
        return '[' + location.getWorld().getName() + "] " + location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ();
    }

    /**
     * The name of a location's world, or a stable placeholder when the world is unloaded
     * (ADT-140: {@link Location#getWorld()} may be {@code null}). Used for the shop-location
     * placeholders in owner trade-notification messages.
     *
     * @param location the location
     * @return the world name, or {@code "?"} if the world is unavailable
     */
    public static String worldName(Location location) {
        return location.getWorld() != null ? location.getWorld().getName() : UNKNOWN_WORLD;
    }
}
