package io.paradaux.chestshop.utils;

/**
 * Legacy section-code colour constants, replacing the deprecated {@code org.bukkit.ChatColor}
 * for the legacy-String display paths (sign codes, {@code /iteminfo} lines). Each value is the
 * exact {@code §}-code {@code ChatColor.X.toString()} produced, so rendering is byte-identical.
 * (The message layer is moving to Adventure Components separately; this covers the remaining
 * legacy-String building without pulling in the deprecated enum.)
 */
public final class Colours {

    public static final char SECTION_CHAR = '§';

    public static final String RED = "§c";
    public static final String GREEN = "§a";
    public static final String DARK_GREEN = "§2";
    public static final String AQUA = "§b";
    public static final String YELLOW = "§e";
    public static final String GRAY = "§7";
    public static final String DARK_GRAY = "§8";
    public static final String BOLD = "§l";

    private Colours() {
    }
}
