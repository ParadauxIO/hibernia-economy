package io.paradaux.chestshop.utils;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.paradaux.chestshop.utils.StringUtil.getMinecraftCharWidth;
import static io.paradaux.chestshop.utils.StringUtil.getMinecraftStringWidth;

/**
 * Pure, stateless item/material string helpers: empty/custom-data detection, sign-name
 * shortening, durability/{@code #metadata} parsing, and the fuzzy {@link #resolveMaterial}
 * lookup. The config-dependent, stateful parts split out of here (PAR-282):
 * item-equality and the config-sized material cache are on
 * {@link io.paradaux.chestshop.services.MaterialService}; the item↔sign-code naming is on
 * {@code ItemCodeService}.
 *
 * @author Acrobot
 */
public final class MaterialUtil {

    private MaterialUtil() {
    }

    public static final Pattern DURABILITY = Pattern.compile(":\\d+");
    public static final Pattern METADATA = Pattern.compile("#[0-9a-zA-Z]+");

    // 15 dashes fit on one sign line with the default resource pack:
    public static final int MAXIMUM_SIGN_WIDTH = (short) getMinecraftStringWidth("---------------");

    private static final Map<String, String> ABBREVIATIONS = StringUtil.map(
            "Egg", "Eg",
            "Spawn", "Spaw",
            "Pottery", "Pot",
            "Heartbreak", "Heartbr",
            "Sherd", "Sher"
    );

    private static final Map<String, String> UNIDIRECTIONAL_ABBREVIATIONS = StringUtil.map(
            "Chestplate", "Chestplt",
            "Chestplt", "Chstplt",
            "Chstplt", "Chstpl",
            "Endermite", "Endmite",
            "Endmite", "Endmit",
            "Wayfinder", "Wayfndr",
            "Wayfndr", "Wf",
            "Heartbr", "Hrtbr",
            "Hrtbr", "Hrtb"
    );

    /**
     * Checks if the itemStack is empty or null
     *
     * @param item Item to check
     * @return Is the itemStack empty?
     */
    public static boolean isEmpty(ItemStack item) {
        return item == null || item.getType() == Material.AIR;
    }

    /**
     * Check whether the provided ItemStack has custom data (in the past called "ItemMeta"). This will ignore
     * the durability of an item.
     *
     * @param itemStack The ItemStack to check
     * @return Whether the item has custom data
     */
    public static boolean hasCustomData(ItemStack itemStack) {
        if (!itemStack.hasItemMeta()) {
            return false;
        }
        ItemMeta itemMeta = itemStack.getItemMeta();
        if (itemMeta instanceof Damageable) {
            if (!((Damageable) itemMeta).hasDamage()) {
                return true;
            }
        }
        Map<String, Object> data = itemMeta.serialize();
        // if the data map contains more than the metadata type and the damage
        // then the item does indeed have custom data set
        return data.size() > 2;
    }

    /**
     * Get an item name shortened to a max length that is still reversable by
     * {@link #resolveMaterial(String)}
     *
     * @param itemName  The name of the item
     * @param maxWidth  The max width
     * @return The name shortened to the max length
     */
    public static String getShortenedName(String itemName, int maxWidth) {
        // Restore spaces in string that might be already be shortened
        String name = itemName.replaceAll("([a-z])([A-Z1-9])", "$1 $2");
        name = StringUtil.capitalizeFirstLetter(name.replace('_', ' '), ' ');
        int width = getMinecraftStringWidth(name);
        if (width <= maxWidth) {
            return name;
        }
        String[] itemParts = name.split("[ \\-]");
        String noSpaceName = String.join("", itemParts);
        width = getMinecraftStringWidth(noSpaceName);
        if (width <= maxWidth) {
            return noSpaceName;
        }

        // Abbreviate some terms manually
        for (Map.Entry<String, String> entry : ABBREVIATIONS.entrySet()) {
            name = name.replaceAll(entry.getKey() + "( |$)", entry.getValue() + "$1");
            itemParts = name.split("[ \\-]");
            noSpaceName = String.join("", itemParts);
            width = getMinecraftStringWidth(noSpaceName);
            if (width <= maxWidth) {
                return noSpaceName;
            }
        }

        // Apply unidirectional abbreviations if it still doesn't work
        for (Map.Entry<String, String> entry : UNIDIRECTIONAL_ABBREVIATIONS.entrySet()) {
            name = name.replaceAll(entry.getKey() + "( |$)", entry.getValue() + "$1");
            itemParts = name.split("[ \\-]");
            noSpaceName = String.join("", itemParts);
            width = getMinecraftStringWidth(noSpaceName);
            if (width <= maxWidth) {
                return noSpaceName;
            }
        }

        int exceeding = width - maxWidth;
        int shortestIndex = 0;
        int longestIndex = 0;
        for (int i = 0; i < itemParts.length; i++) {
            if (getMinecraftStringWidth(itemParts[longestIndex]) < getMinecraftStringWidth(itemParts[i])) {
                longestIndex = i;
            }
            if (getMinecraftStringWidth(itemParts[shortestIndex]) > getMinecraftStringWidth(itemParts[i])) {
                shortestIndex = i;
            }
        }
        int shortestWidth = getMinecraftStringWidth(itemParts[shortestIndex]);
        int longestWidth = getMinecraftStringWidth(itemParts[longestIndex]);
        int remove = longestWidth - shortestWidth;
        while (remove > 0 && exceeding > 0) {
            int endWidth = getMinecraftCharWidth(itemParts[longestIndex].charAt(itemParts[longestIndex].length() - 1));
            itemParts[longestIndex] = itemParts[longestIndex].substring(0, itemParts[longestIndex].length() - 1);
            remove -= endWidth;
            exceeding -= endWidth;
        }

        for (int i = itemParts.length - 1; i >= 0 && exceeding > 0; i--) {
            int partWidth = getMinecraftStringWidth(itemParts[i]);

            if (partWidth > shortestWidth) {
                remove = partWidth - shortestWidth;
            }

            if (remove > exceeding) {
                remove = exceeding;
            }

            while (remove > 0) {
                int endWidth = getMinecraftCharWidth(itemParts[i].charAt(itemParts[i].length() - 1));
                itemParts[i] = itemParts[i].substring(0, itemParts[i].length() - 1);
                remove -= endWidth;
                exceeding -= endWidth;
            }
        }

        while (exceeding > 0) {
            for (int i = itemParts.length - 1; i >= 0 && exceeding > 0; i--) {
                int endWidth = getMinecraftCharWidth(itemParts[i].charAt(itemParts[i].length() - 1));
                itemParts[i] = itemParts[i].substring(0, itemParts[i].length() - 1);
                exceeding -= endWidth;
            }
        }
        return String.join("", itemParts);
    }

    /**
     * Returns the durability from a string
     *
     * @param itemName Item name
     * @return Durability found
     */
    public static Integer getDurability(String itemName) {
        Matcher m = DURABILITY.matcher(itemName);

        if (!m.find()) {
            return null;
        }

        // The DURABILITY pattern (":\d+") guarantees a non-empty, always-parseable digit run.
        return Integer.parseInt(m.group().substring(1));
    }

    /**
     * Extract the #code metadata token from an item string (the part after '#'), or
     * {@code null} if none. Pure parsing — {@link io.paradaux.chestshop.services.ItemCodeService#decode}
     * resolves the token to an {@link ItemMeta}.
     *
     * @param itemName Item name
     * @return Metadata token found
     */
    public static String parseMetadata(String itemName) {
        Matcher m = METADATA.matcher(itemName);

        if (!m.find()) {
            return null;
        }

        return m.group().substring(1);
    }

    /**
     * Fuzzy-resolve a {@link Material} from a (possibly partial/abbreviated) name — the pure
     * lookup: exact {@link Material#matchMaterial}, else the enum-prefix parser after reversing
     * the unidirectional sign abbreviations. Returns {@code null} if unresolved. The config-sized
     * cache wrapper lives on {@link io.paradaux.chestshop.services.MaterialService#getMaterial}.
     *
     * @param name Name of the material
     * @return Material found, or null
     */
    public static Material resolveMaterial(String name) {
        String replacedName = name;
        // revert unidirectional abbreviations
        List<Map.Entry<String, String>> abbreviations = new ArrayList<>(UNIDIRECTIONAL_ABBREVIATIONS.entrySet());
        for (int i = abbreviations.size() - 1; i >= 0; i--) {
            Map.Entry<String, String> entry = abbreviations.get(i);
            replacedName = replacedName.replaceAll(entry.getValue() + "(_|$|[A-Z\\d])", entry.getKey() + "$1");
        }

        Material material = Material.matchMaterial(name);
        if (material != null) {
            return material;
        }

        return new EnumParser<Material>().parse(replacedName, Material.values());
    }

    private static class EnumParser<E extends Enum<E>> {
        private E parse(String name, E[] values) {
            String formatted = name.replaceAll("(?<!^)(?>\\s?)([A-Z1-9])", "_$1").toUpperCase(Locale.ROOT).replace(' ', '_');
            try {
                return E.valueOf(values[0].getDeclaringClass(), formatted);
            } catch (IllegalArgumentException exception) {
                List<E> possibleEnums = new ArrayList<>();
                String[] typeParts = formatted.split("_");
                int length = Short.MAX_VALUE;
                for (E e : values) {
                    String enumName = e.name();
                    if (enumName.length() < length && enumName.startsWith(formatted)) {
                        length = enumName.length();
                        possibleEnums.add(e);
                    } else if (typeParts.length > 1) {
                        String[] nameParts = enumName.split("_");
                        if (typeParts.length == nameParts.length) {
                            boolean matched = true;
                            for (int i = 0; i < nameParts.length; i++) {
                                if (!nameParts[i].startsWith(typeParts[i])) {
                                    matched = false;
                                    break;
                                }
                            }
                            if (matched) {
                                possibleEnums.add(e);
                            }
                        }
                    }
                }

                if (possibleEnums.size() == 1) {
                    return possibleEnums.get(0);
                } else if (possibleEnums.size() > 1) {
                    int formattedLength = formatted.length();
                    int closestDeviation = Short.MAX_VALUE;
                    E closestEnum = null;
                    for (E possibleEnum : possibleEnums) {
                        int deviation = possibleEnum.name().length() - formattedLength;
                        if (deviation < closestDeviation) {
                            closestDeviation = deviation;
                            closestEnum = possibleEnum;
                        }
                    }
                    return closestEnum;
                }
                return null;
            }
        }
    }
}
