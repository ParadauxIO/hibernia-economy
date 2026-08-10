package io.paradaux.chestshop.integration;
import io.paradaux.chestshop.utils.StringUtil;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.nexomc.nexo.api.NexoItems;
import com.nexomc.nexo.items.ItemBuilder;
import io.paradaux.chestshop.ChestShop;
import io.paradaux.chestshop.services.CustomItemResolver;
import io.paradaux.chestshop.services.ItemCodeService;
import io.paradaux.chestshop.services.ItemService;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Color;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import static io.paradaux.chestshop.utils.StringUtil.getMinecraftStringWidth;

/**
 * Nexo (and ItemsAdder) soft-dependency: registers itself as a {@link CustomItemResolver} on
 * {@link ItemService} so shop signs can parse/name Nexo custom items — the service consults the
 * resolver and never references Nexo directly (PAR-314).
 *
 * <p>The Nexo-API code lives in the private nested {@link Nexo} class so the {@code com.nexomc.nexo}
 * types stay class-loading-isolated: this integration is instantiated at enable to build the
 * {@code Set<Integration>} even when Nexo is absent, and the nested class (a separate {@code .class})
 * is only loaded once {@link #hook} / the resolver methods touch it — i.e. only when Nexo is present.
 */
@Singleton
@Slf4j
public class NexoIntegration implements Integration, CustomItemResolver {

    private final ItemService items;
    private final ItemCodeService itemCodes;

    @Inject
    public NexoIntegration(ItemService items, ItemCodeService itemCodes) {
        this.items = items;
        this.itemCodes = itemCodes;
    }

    @Override
    public String pluginName() {
        return "Nexo";
    }

    @Override
    public boolean hook(Plugin plugin) {
        Nexo.init(itemCodes);
        items.registerCustomItemResolver(this);
        return true;
    }

    @Override
    public ItemStack parseItem(String raw) {
        return Nexo.parseItem(raw);
    }

    @Override
    public String queryString(ItemStack stack, int maxWidth) {
        return Nexo.queryString(stack, maxWidth);
    }

    @Override
    public void reload() {
        Nexo.reload();
    }

    private static final class Nexo {

        private Nexo() {
        }

        private static final NamespacedKey NEXO_KEY = NamespacedKey.fromString("nexo:id");
        private static final String NEXO_PREFIX = "nexo:";
        private static final String ITEMSADDER_NAMESPACE = "itemsadder";
        private static final String ITEMSADDER_ID_KEY = "id";
        private static final Pattern HEX_TAG = Pattern.compile("(?i)^(?:#|0x|hex[-_])?([0-9a-f]{6})$");

        private static final Map<Integer, String> DYE_TAG_BY_RGB;
        private static final Map<String, Color> COLOR_BY_TAG;

        static {
            Map<Integer, String> byRgb = new ConcurrentHashMap<>();
            Map<String, Color> byTag = new ConcurrentHashMap<>();
            for (DyeColor dyeColor : DyeColor.values()) {
                Color color = dyeColor.getColor();
                String tag = dyeColor.name().toLowerCase(Locale.ROOT);
                byRgb.put(color.asRGB(), tag);
                byTag.put(tag, color);
            }
            DYE_TAG_BY_RGB = Collections.unmodifiableMap(byRgb);
            COLOR_BY_TAG = Collections.unmodifiableMap(byTag);
        }

        // --- state, populated by init()/reload() ------------------------------------
        private static ItemCodeService itemCodes;
        private static final Map<String, ItemStack> baseItemCache = new ConcurrentHashMap<>();

        // aliases (base id) and full-code (variant) aliases, plus display prefs
        private static volatile Map<String, String> aliasToId = Map.of();
        private static volatile Map<String, String> idToAlias = Map.of();
        private static volatile Map<String, String> aliasToFullCode = Map.of();
        private static volatile Map<String, String> fullCodeToAlias = Map.of();
        private static volatile boolean preferAlias = true;
        // Defaults match DemocracyCraft's NexoUtilities production config: the sign shows the
        // BARE nexo id (no "nexo:" prefix) because ChestShop's item-line pattern rejects colons,
        // and a bare id on a sign is accepted as a nexo id.
        private static volatile String fallbackFormat = "%s";
        private static volatile int signMaxChars = 15;
        private static volatile boolean acceptBareIds = true;
        private static volatile boolean supportItemsAdder = true;

        /** Wire the item database and load {@code nexo.yml} (called from {@code NexoIntegration} when Nexo hooks). */
        public static void init(ItemCodeService codes) {
            itemCodes = codes;
            reload();
        }

        /** Reload {@code nexo.yml} (aliases + display/input settings), writing a defaulted file if absent. */
        public static void reload() {
            File file = new File(ChestShop.getFolder(), "nexo.yml");
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
            if (!file.exists()) {
                cfg.options().header("Nexo / ItemsAdder custom-item support for ChestShop signs.\n"
                        + "aliases: short_name -> nexo_id, or  ItemName#metadataCode -> short_alias\n"
                        + "display.preferAlias / fallbackFormat / signMaxChars control the sign text.\n"
                        + "input.acceptBareIds lets players type an id without the nexo: prefix.");
                cfg.addDefault("aliases.example_alias", "example_nexo_id");
                cfg.addDefault("input.acceptBareIds", true);
                cfg.addDefault("input.supportItemsAdder", true);
                cfg.addDefault("display.preferAlias", true);
                cfg.addDefault("display.fallbackFormat", "%s");
                cfg.addDefault("display.signMaxChars", 15);
                cfg.options().copyDefaults(true);
                try {
                    cfg.save(file);
                } catch (IOException e) {
                    log.error("Error while saving nexo.yml", e);
                }
            }

            Map<String, String> newAliasToId = new HashMap<>();
            Map<String, String> newIdToAlias = new HashMap<>();
            Map<String, String> newAliasToFullCode = new HashMap<>();
            Map<String, String> newFullCodeToAlias = new HashMap<>();

            ConfigurationSection sec = cfg.getConfigurationSection("aliases");
            if (sec != null) {
                for (String key : sec.getKeys(false)) {
                    String value = sec.getString(key, "").trim();
                    if (key.isBlank() || value.isBlank()) {
                        continue;
                    }
                    if (key.indexOf('#') > -1) {
                        // ItemName#metadataCode -> short_alias
                        newAliasToFullCode.put(value.toLowerCase(Locale.ROOT), key);
                        newFullCodeToAlias.put(key, value);
                    } else {
                        // short_name -> nexo_id
                        newAliasToId.put(key.toLowerCase(Locale.ROOT), value);
                        newIdToAlias.putIfAbsent(value, key);
                    }
                }
            }
            aliasToId = Collections.unmodifiableMap(newAliasToId);
            idToAlias = Collections.unmodifiableMap(newIdToAlias);
            aliasToFullCode = Collections.unmodifiableMap(newAliasToFullCode);
            fullCodeToAlias = Collections.unmodifiableMap(newFullCodeToAlias);

            preferAlias = cfg.getBoolean("display.preferAlias", true);
            fallbackFormat = cfg.getString("display.fallbackFormat", "%s");
            signMaxChars = Math.max(1, cfg.getInt("display.signMaxChars", 15));
            acceptBareIds = cfg.getBoolean("input.acceptBareIds", true);
            supportItemsAdder = cfg.getBoolean("input.supportItemsAdder", true);
            baseItemCache.clear();
        }

        // === parse: sign string -> ItemStack (was ChestShopItemListener.onItemParse) ==========

        /** Resolve a sign string to a Nexo {@link ItemStack}, or {@code null} if it isn't a Nexo item. */
        public static ItemStack parseItem(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            // A full-code alias (ItemName#meta -> short) expands to its underlying code first.
            String fullCode = aliasToFullCode.get(raw.toLowerCase(Locale.ROOT));
            if (fullCode != null) {
                raw = fullCode;
            }

            String dyeTag = null;
            int hashIndex = raw.indexOf('#');
            if (hashIndex > -1) {
                dyeTag = raw.substring(hashIndex + 1).trim();
                raw = raw.substring(0, hashIndex);
            }
            if (raw.isBlank()) {
                return null;
            }

            // A vanilla item is not ours — let ChestShop's own logic name it.
            if (Material.matchMaterial(raw) != null) {
                return null;
            }

            String id = resolveToNexoId(raw);
            if (id == null) {
                return null;
            }

            ItemStack stack = getNexoItem(id);
            if (stack == null) {
                return null;
            }
            // Autographed items carry a personalised lore — let ChestShop handle them verbatim.
            if (isAutographedItem(stack)) {
                return null;
            }

            if (dyeTag != null && !dyeTag.isEmpty()) {
                if (!applyDyeTag(stack, dyeTag)) {
                    ItemStack metadataItem = getItemFromMetadataCode(dyeTag);
                    if (metadataItem == null) {
                        log.warn("Unknown dye tag or metadata code '" + dyeTag + "' for Nexo item " + id);
                        return null;
                    }
                    String metadataId = getNexoIdFromItem(metadataItem);
                    if (metadataId == null || !metadataId.equals(id)) {
                        log.warn("Metadata code '" + dyeTag + "' does not belong to Nexo item " + id);
                        return null;
                    }
                    stack = metadataItem;
                }
            }
            return stack;
        }

        // === encode: ItemStack -> sign string (was ChestShopItemListener.onItemStringQuery) ====

        /** The Nexo sign string for an item, or {@code null} if it isn't a Nexo item or won't fit {@code maxWidth}. */
        public static String queryString(ItemStack stack, int maxWidth) {
            if (stack == null || !stack.hasItemMeta()) {
                return null;
            }
            if (isAutographedItem(stack)) {
                return null;
            }
            String id = getNexoIdFromItem(stack);
            if (id == null || id.isBlank()) {
                return null;
            }

            String displayString = getDisplayString(id);
            String code;
            if (isBaseNexoItem(id, stack)) {
                code = displayString;
            } else {
                String metadataCode = getMetadataCode(stack);
                if (metadataCode == null || metadataCode.isBlank()) {
                    return null;
                }
                String fullCode = displayString + "#" + metadataCode;
                String alias = fullCodeToAlias.get(fullCode);
                code = alias != null ? alias : fullCode;
            }

            if (maxWidth > 0 && getMinecraftStringWidth(code) > maxWidth) {
                ChestShop.logDebug("Can't use Nexo code " + code + " — wider than the allowed max width of " + maxWidth);
                return null;
            }
            return code;
        }

        // === alias resolution (was AliasManager) ==============================================

        private static String resolveToNexoId(String raw) {
            String lowerId = aliasToId.get(raw.toLowerCase(Locale.ROOT));
            if (lowerId != null) {
                return lowerId;
            }
            if (raw.startsWith(NEXO_PREFIX)) {
                return raw.substring(NEXO_PREFIX.length());
            }
            return acceptBareIds ? raw : null;
        }

        private static String getDisplayString(String id) {
            if (id == null || id.isBlank()) {
                return "";
            }
            String alias = idToAlias.get(id);
            if (preferAlias && alias != null && alias.length() <= signMaxChars) {
                return alias;
            }
            return String.format(fallbackFormat, id);
        }

        // === Nexo / ItemsAdder item logic (was ItemManager) ===================================

        private static boolean isAutographedItem(ItemStack item) {
            if (item == null || !item.hasItemMeta()) {
                return false;
            }
            var meta = item.getItemMeta();
            if (!meta.hasLore()) {
                return false;
            }
            var lore = meta.lore();
            if (lore == null || lore.isEmpty()) {
                return false;
            }
            return lore.stream().anyMatch(line ->
                    LegacyComponentSerializer.legacySection().serialize(line).toLowerCase(Locale.ROOT).contains("autograph"));
        }

        private static String getItemsAdderId(ItemStack item) {
            if (item == null || !item.hasItemMeta()) {
                return null;
            }
            var container = item.getItemMeta().getPersistentDataContainer();
            for (NamespacedKey key : container.getKeys()) {
                if (key.getNamespace().equals(ITEMSADDER_NAMESPACE) && key.getKey().equals(ITEMSADDER_ID_KEY)) {
                    String id = container.get(key, PersistentDataType.STRING);
                    if (id != null && !id.isBlank()) {
                        return id;
                    }
                }
            }
            try {
                var nbt = item.serialize();
                if (nbt.get("components") instanceof Map<?, ?> components
                        && components.get("minecraft:custom_data") instanceof Map<?, ?> customData
                        && customData.get(ITEMSADDER_NAMESPACE) instanceof Map<?, ?> itemsAdder
                        && itemsAdder.get(ITEMSADDER_ID_KEY) != null) {
                    String id = itemsAdder.get(ITEMSADDER_ID_KEY).toString();
                    if (!id.isBlank()) {
                        return id;
                    }
                }
            } catch (Exception e) {
                log.warn("Error checking ItemsAdder NBT data: " + e.getMessage());
            }
            return null;
        }

        private static String getNexoIdFromItem(ItemStack item) {
            if (item == null || !item.hasItemMeta()) {
                return null;
            }
            String nexoId = item.getItemMeta().getPersistentDataContainer().get(NEXO_KEY, PersistentDataType.STRING);
            if (nexoId != null && !nexoId.isBlank()) {
                return nexoId;
            }
            return supportItemsAdder ? getItemsAdderId(item) : null;
        }

        private static ItemStack getNexoItem(String nexoId) {
            ItemStack cached = baseItemCache.get(nexoId);
            if (cached != null) {
                return cached.clone();
            }
            ItemBuilder builder = NexoItems.itemFromId(nexoId);
            if (builder == null) {
                return null;
            }
            ItemStack cleaned = cleanNexoItemForChestShop(builder.build(), nexoId);
            baseItemCache.put(nexoId, cleaned.clone());
            return cleaned;
        }

        /** Stamp the nexo id and drop empty lore/name so ChestShop's NBT (de)serialisation stays stable. */
        private static ItemStack cleanNexoItemForChestShop(ItemStack item, String nexoId) {
            if (item == null) {
                return null;
            }
            ItemStack clean = item.clone();
            clean.editMeta(meta -> {
                meta.getPersistentDataContainer().set(NEXO_KEY, PersistentDataType.STRING, nexoId);
                if (meta.hasLore()) {
                    var lore = meta.lore();
                    if (lore == null || lore.isEmpty()) {
                        meta.lore(null);
                    }
                }
                if (meta.displayName() != null) {
                    Component name = meta.displayName();
                    if (name == null || LegacyComponentSerializer.legacySection().serialize(name).trim().isEmpty()) {
                        meta.displayName(null);
                    }
                }
            });
            return clean;
        }

        private static boolean isBaseNexoItem(String nexoId, ItemStack item) {
            if (nexoId == null || nexoId.isBlank() || item == null) {
                return false;
            }
            ItemStack base = getNexoItem(nexoId);
            if (base == null) {
                return false;
            }
            ItemStack comparable = item.clone();
            comparable.setAmount(1);
            ItemStack baseComparable = base.clone();
            baseComparable.setAmount(1);
            return baseComparable.isSimilar(comparable);
        }

        private static boolean applyDyeTag(ItemStack item, String dyeTag) {
            if (item == null || !(item.getItemMeta() instanceof LeatherArmorMeta meta)) {
                return false;
            }
            Color color = parseColorFromTag(dyeTag);
            if (color == null) {
                return false;
            }
            meta.setColor(color);
            item.setItemMeta(meta);
            return true;
        }

        private static Color parseColorFromTag(String dyeTag) {
            if (dyeTag == null || dyeTag.isBlank()) {
                return null;
            }
            String normalized = dyeTag.toLowerCase(Locale.ROOT);
            Color named = COLOR_BY_TAG.get(normalized);
            if (named != null) {
                return named;
            }
            Matcher matcher = HEX_TAG.matcher(normalized);
            if (matcher.matches()) {
                try {
                    return Color.fromRGB(Integer.parseInt(matcher.group(1), 16));
                } catch (NumberFormatException ignored) {
                    // fall through
                }
            }
            return null;
        }

        /** ChestShop item-database code for a variant item (was {@code ChestShop.getItemDatabase().getItemCode}). */
        private static String getMetadataCode(ItemStack item) {
            return item == null ? null : itemCodes.getItemCode(item);
        }

        /** The item for a ChestShop item-database code (was {@code ChestShop.getItemDatabase().getFromCode}). */
        private static ItemStack getItemFromMetadataCode(String metadataCode) {
            if (metadataCode == null || metadataCode.isBlank()) {
                return null;
            }
            return itemCodes.getFromCode(metadataCode);
        }
    }
}
