package io.paradaux.chestshop.services.impl;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import io.paradaux.chestshop.services.MaterialService;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

/**
 * Readable sign names for the items players trade most that otherwise only fit on a sign as an
 * opaque {@code Potion#3d} / {@code Enchanted Book#Eg} database code: vanilla potions (in all
 * four forms) and single-enchant enchanted books.
 *
 * <p>The format is {@code <form> <name> [<tier>]}:
 * <ul>
 *   <li>potions: {@code Pot} / {@code Spl} / {@code Lin} / {@code Arr}, the effect, then
 *       {@code Ext} for the extended variant or {@code II} for the strong one
 *       ({@code Spl Healing II}, {@code Arr Swiftness Ext});</li>
 *   <li>books: {@code Bk}, the enchantment, then its level as a roman numeral, omitted when the
 *       enchantment only has one level ({@code Bk Sharpness V}, {@code Bk Mending}).</li>
 * </ul>
 * Every name fits one sign line and uses only characters the sign's item line accepts.
 *
 * <p>An item has a shorthand only if it {@link MaterialService#equals equals} the plain item
 * the shorthand parses back to. So a renamed potion, a multi-enchant book or an over-levelled
 * book keeps its {@code #code}; and an anvil-merged book (which carries a repair cost) shares
 * the clean book's name exactly when the operator lists {@code repair-cost} under
 * {@code EXCLUDED_ITEM_ATTRIBUTES} — the same switch that makes the two trade as one item.
 *
 * <p>Potion types and enchantments missing from the tables below (a new game version, a data
 * pack) simply have no shorthand and fall back to their {@code #code}.
 */
@Singleton
public class ShorthandItemCodec {

    private static final String BOOK_PREFIX = "Bk";
    private static final String EXTENDED = "Ext";
    private static final String STRONG = "II";
    private static final String LONG_KEY_PREFIX = "long_";
    private static final String STRONG_KEY_PREFIX = "strong_";
    private static final String[] NUMERALS = {"I", "II", "III", "IV", "V"};

    private static final Map<Material, String> POTION_FORMS = Map.of(
            Material.POTION, "Pot",
            Material.SPLASH_POTION, "Spl",
            Material.LINGERING_POTION, "Lin",
            Material.TIPPED_ARROW, "Arr");

    /** Base potion key (without the {@code long_}/{@code strong_} prefix) to its sign name. */
    private static final Map<String, String> POTION_NAMES = Map.ofEntries(
            Map.entry("water", "Water"),
            Map.entry("mundane", "Mundane"),
            Map.entry("thick", "Thick"),
            Map.entry("awkward", "Awkward"),
            Map.entry("night_vision", "NightVis"),
            Map.entry("invisibility", "Invis"),
            Map.entry("leaping", "Leaping"),
            Map.entry("fire_resistance", "FireRes"),
            Map.entry("swiftness", "Swiftness"),
            Map.entry("slowness", "Slowness"),
            Map.entry("turtle_master", "Turtle"),
            Map.entry("water_breathing", "WaterBr"),
            Map.entry("healing", "Healing"),
            Map.entry("harming", "Harming"),
            Map.entry("poison", "Poison"),
            Map.entry("regeneration", "Regen"),
            Map.entry("strength", "Strength"),
            Map.entry("weakness", "Weakness"),
            Map.entry("luck", "Luck"),
            Map.entry("slow_falling", "SlowFall"),
            Map.entry("wind_charged", "WindChg"),
            Map.entry("weaving", "Weaving"),
            Map.entry("oozing", "Oozing"),
            Map.entry("infested", "Infested"));

    /** Enchantment key to its sign name. */
    private static final Map<String, String> ENCHANTMENT_NAMES = Map.ofEntries(
            Map.entry("aqua_affinity", "AquaAffinity"),
            Map.entry("bane_of_arthropods", "Bane"),
            Map.entry("binding_curse", "Binding"),
            Map.entry("blast_protection", "BlastProt"),
            Map.entry("breach", "Breach"),
            Map.entry("channeling", "Channeling"),
            Map.entry("density", "Density"),
            Map.entry("depth_strider", "DepthStr"),
            Map.entry("efficiency", "Efficiency"),
            Map.entry("feather_falling", "FeatherFall"),
            Map.entry("fire_aspect", "FireAspect"),
            Map.entry("fire_protection", "FireProt"),
            Map.entry("flame", "Flame"),
            Map.entry("fortune", "Fortune"),
            Map.entry("frost_walker", "FrostWalker"),
            Map.entry("impaling", "Impaling"),
            Map.entry("infinity", "Infinity"),
            Map.entry("knockback", "Knockback"),
            Map.entry("looting", "Looting"),
            Map.entry("loyalty", "Loyalty"),
            Map.entry("luck_of_the_sea", "LuckOfSea"),
            Map.entry("lunge", "Lunge"),
            Map.entry("lure", "Lure"),
            Map.entry("mending", "Mending"),
            Map.entry("multishot", "Multishot"),
            Map.entry("piercing", "Piercing"),
            Map.entry("power", "Power"),
            Map.entry("projectile_protection", "ProjProt"),
            Map.entry("protection", "Protection"),
            Map.entry("punch", "Punch"),
            Map.entry("quick_charge", "QuickChg"),
            Map.entry("respiration", "Respiration"),
            Map.entry("riptide", "Riptide"),
            Map.entry("sharpness", "Sharpness"),
            Map.entry("silk_touch", "SilkTouch"),
            Map.entry("smite", "Smite"),
            Map.entry("soul_speed", "SoulSpeed"),
            Map.entry("sweeping_edge", "Sweeping"),
            Map.entry("swift_sneak", "SwiftSneak"),
            Map.entry("thorns", "Thorns"),
            Map.entry("unbreaking", "Unbreaking"),
            Map.entry("vanishing_curse", "Vanishing"),
            Map.entry("wind_burst", "WindBurst"));

    private final MaterialService materials;

    /** Lower-cased shorthand to the plain item it stands for. */
    private final Map<String, ItemStack> plainItems = new HashMap<>();

    @Inject
    public ShorthandItemCodec(MaterialService materials) {
        this.materials = materials;
        for (Material form : POTION_FORMS.keySet()) {
            for (PotionType type : PotionType.values()) {
                register(potionName(form, type), plainPotion(form, type));
            }
        }
        for (Enchantment enchantment : RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT)) {
            for (int level = 1; level <= enchantment.getMaxLevel(); level++) {
                register(bookName(enchantment, level), plainBook(enchantment, level));
            }
        }
    }

    /** The shorthand for {@code item}, or {@code null} if it is not a plain potion or single-enchant book. */
    public String encode(ItemStack item) {
        if (item == null) {
            return null;
        }
        String name = nameOf(item);
        if (name == null) {
            return null;
        }
        return materials.equals(item, plainItems.get(name.toLowerCase(Locale.ROOT))) ? name : null;
    }

    /** The plain item a shorthand stands for (matched case-insensitively), or {@code null}. */
    public ItemStack decode(String name) {
        if (name == null) {
            return null;
        }
        ItemStack plain = plainItems.get(name.toLowerCase(Locale.ROOT));
        return plain == null ? null : plain.clone();
    }

    private void register(String name, ItemStack plain) {
        if (name != null) {
            plainItems.put(name.toLowerCase(Locale.ROOT), plain);
        }
    }

    /** The shorthand {@code item}'s potion type or lone stored enchantment would have, ignoring its other data. */
    private static String nameOf(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof PotionMeta potion) {
            return potionName(item.getType(), potion.getBasePotionType());
        }
        if (meta instanceof EnchantmentStorageMeta book
                && book.getStoredEnchants().size() == 1) {
            Map.Entry<Enchantment, Integer> stored =
                    book.getStoredEnchants().entrySet().iterator().next();
            return bookName(stored.getKey(), stored.getValue());
        }
        return null;
    }

    private static String potionName(Material form, PotionType type) {
        if (type == null) {
            return null;
        }
        String key = type.getKey().getKey();
        String tier = "";
        if (key.startsWith(LONG_KEY_PREFIX)) {
            key = key.substring(LONG_KEY_PREFIX.length());
            tier = " " + EXTENDED;
        } else if (key.startsWith(STRONG_KEY_PREFIX)) {
            key = key.substring(STRONG_KEY_PREFIX.length());
            tier = " " + STRONG;
        }
        String name = POTION_NAMES.get(key);
        return name == null ? null : POTION_FORMS.get(form) + " " + name + tier;
    }

    private static String bookName(Enchantment enchantment, int level) {
        int maxLevel = enchantment.getMaxLevel();
        String name = ENCHANTMENT_NAMES.get(enchantment.getKey().getKey());
        if (name == null
                || !NamespacedKey.MINECRAFT.equals(enchantment.getKey().getNamespace())
                || level < 1
                || level > maxLevel
                || level > NUMERALS.length) {
            return null;
        }
        return maxLevel == 1 ? BOOK_PREFIX + " " + name : BOOK_PREFIX + " " + name + " " + NUMERALS[level - 1];
    }

    private static ItemStack plainPotion(Material form, PotionType type) {
        ItemStack item = new ItemStack(form);
        PotionMeta meta = (PotionMeta) item.getItemMeta();
        meta.setBasePotionType(type);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack plainBook(Enchantment enchantment, int level) {
        ItemStack item = new ItemStack(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta meta = (EnchantmentStorageMeta) item.getItemMeta();
        meta.addStoredEnchant(enchantment, level, true);
        item.setItemMeta(meta);
        return item;
    }
}
