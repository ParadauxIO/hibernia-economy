package io.paradaux.chestshop.services.impl;
import io.paradaux.chestshop.utils.StringUtil;

import io.paradaux.chestshop.services.MaterialService;
import io.paradaux.chestshop.services.ItemCodeService;
import io.paradaux.chestshop.services.InventoryService;
import io.paradaux.chestshop.services.CustomItemResolver;
import io.paradaux.chestshop.services.ItemService;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.chestshop.utils.MaterialUtil;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.paradaux.chestshop.utils.MaterialUtil.MAXIMUM_SIGN_WIDTH;
import static io.paradaux.chestshop.utils.StringUtil.getMinecraftStringWidth;

/**
 * Resolves the item/sign strings ChestShop puts on signs. Replaces the
 * {@code ItemParseEvent} / {@code ItemStringQueryEvent} / {@code MaterialParseEvent} /
 * {@code SignValidationEvent} carriers and their resolver "listeners" with direct,
 * ordered service methods (PAR-282). The resolution order is unchanged:
 * <ul>
 *   <li>{@link #parse}: custom-item resolver (if registered) → configured alias → vanilla
 *       material (the alias step overrides a custom-item match, as before);</li>
 *   <li>{@link #queryString}: custom-item key (if registered) → vanilla name → alias override;</li>
 *   <li>{@link #parseMaterial}: a single resolver.</li>
 * </ul>
 *
 * <p>Sign-format validation is pure and lives on {@code SignService.validateSign}.</p>
 *
 * <p>The optional custom-item integration (a softdepend such as Nexo) registers a
 * {@link CustomItemResolver} when it hooks — this service never references the integration's
 * classes directly, keeping them off the call path when the plugin is absent (PAR-314).
 */
@Singleton
@Slf4j
public class ItemServiceImpl implements ItemService {

    private final ItemCodeService itemCodes;
    private final MaterialService materialService;
    private final InventoryService inventoryService;
    private volatile CustomItemResolver customItems;
    private final java.io.File dataFolder;

    // Configurable item-code ↔ alias mapping (itemAliases.yml), absorbed from the former
    // ItemAliasModule (PAR-316) — it was ItemService's own config with no other consumer.
    private BiMap<String, String> aliases;

    @Inject
    public ItemServiceImpl(ItemCodeService itemCodes, MaterialService materialService, InventoryService inventoryService, @com.google.inject.name.Named("dataFolder") java.io.File dataFolder) {
        this.itemCodes = itemCodes;
        this.materialService = materialService;
        this.inventoryService = inventoryService;
        this.dataFolder = dataFolder;
        loadAliases();
    }

    /** Register a custom-item resolver (a soft-dependency integration hooks itself in here). */
    @Override
    public void registerCustomItemResolver(CustomItemResolver resolver) {
        this.customItems = resolver;
    }

    /** Reload the configurable item aliases (and the custom-item resolver's config, if any). */
    @Override
    public void reloadAliases() {
        loadAliases();
        CustomItemResolver resolver = customItems;
        if (resolver != null) {
            resolver.reload();
        }
    }

    /** Parse a sign item string into an {@link ItemStack} (custom / alias / vanilla material), or {@code null}. */
    @Override
    public ItemStack parse(String itemString) {
        CustomItemResolver resolver = customItems;
        ItemStack item = resolver != null ? resolver.parseItem(itemString) : null;
        ItemStack alias = resolveAlias(itemString); // a configured alias overrides a custom-item match
        if (alias != null) {
            item = alias;
        }
        if (item == null) {
            item = itemCodes.decode(itemString); // vanilla fallback
        }
        return item;
    }

    /** The sign string for an item (custom / vanilla name / configured alias), within {@code maxWidth}. */
    @Override
    public String queryString(ItemStack item, int maxWidth) {
        CustomItemResolver resolver = customItems;
        String result = resolver != null ? resolver.queryString(item, maxWidth) : null;
        if (result == null) {
            result = itemCodes.encode(item, maxWidth); // vanilla name
        }
        return applyAlias(result, maxWidth); // configured alias override
    }

    /** Resolve the material part of an item code (vanilla material lookup), or {@code null}. */
    @Override
    public Material parseMaterial(String materialString, short data) {
        return materialService.getMaterial(materialString); // the legacy data value is ignored on modern materials
    }

    // ---- item display names (were the static ItemUtil helpers; PAR-282) ---------

    /** A comma-joined "count name" list for a set of stacks (used in trade/give messages). */
    @Override
    public String getItemList(ItemStack[] items) {
        Map<ItemStack, Integer> itemCounts = inventoryService.getItemCounts(items);

        List<String> itemText = new ArrayList<>();
        for (Map.Entry<ItemStack, Integer> entry : itemCounts.entrySet()) {
            itemText.add(entry.getValue() + " " + getName(entry.getKey()));
        }

        return String.join(", ", itemText);
    }

    /** The item's full (untruncated) ChestShop name/code. */
    @Override
    public String getName(ItemStack itemStack) {
        return getName(itemStack, 0);
    }

    @Override
    public String getName(ItemStack itemStack, int maxWidth) {
        String code = queryString(itemStack, maxWidth);
        if (code != null) {
            if (maxWidth > 0) {
                int codeWidth = getMinecraftStringWidth(code);
                if (codeWidth > maxWidth) {
                    int exceeding = codeWidth - maxWidth;

                    int poundIndex = code.indexOf('#');
                    int colonIndex = code.indexOf(':');
                    String material = code;
                    String rest = "";
                    if (poundIndex > 0 && (colonIndex < 0 || poundIndex < colonIndex)) {
                        material = code.substring(0, poundIndex);
                        rest = code.substring(poundIndex);
                    } else if (colonIndex > 0 && (poundIndex < 0 || colonIndex < poundIndex)) {
                        material = code.substring(0, colonIndex);
                        rest = code.substring(colonIndex);
                    }
                    code = MaterialUtil.getShortenedName(material, getMinecraftStringWidth(material) - exceeding) + rest;
                }
            }

            ItemStack codeItem = parse(code);
            if (!materialService.equals(itemStack, codeItem)) {
                throw new IllegalArgumentException("Cannot generate code for item " + itemStack
                        + " with maximum length of " + maxWidth
                        + " (code " + code + " results in item " + codeItem + ")");
            }
        }
        return code;
    }

    /** The item's name as it appears on a sign (constrained to the sign width). */
    @Override
    public String getSignName(ItemStack itemStack) {
        return getName(itemStack, MAXIMUM_SIGN_WIDTH);
    }

    // ---- configurable item aliases (was ItemAliasModule; PAR-316) ----------------

    /** (Re)load the item-code → alias map from itemAliases.yml, writing a defaulted file if absent. */
    private void loadAliases() {
        File file = new File(dataFolder, "itemAliases.yml");
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(file);
        configuration.options().header(
                "This file specified optional aliases for certain item codes. (Use the full name from /iteminfo)"
                        + "\nPlease note that these aliases should fit on a sign for it to work properly!");

        if (!file.exists()) {
            configuration.addDefault("Item String#3d", "My Cool Item");
            configuration.addDefault("Other Material#Eg", "Some other Item");
            try {
                configuration.options().copyDefaults(true);
                configuration.save(file);
            } catch (IOException e) {
                log.error("Error while saving item aliases config", e);
            }
        }

        BiMap<String, String> loaded = HashBiMap.create(configuration.getKeys(false).size());
        for (String key : configuration.getKeys(false)) {
            if (configuration.isString(key)) {
                loaded.put(key, configuration.getString(key));
            }
        }
        this.aliases = loaded;
    }

    /** Resolve a configured alias for {@code itemString} to an {@link ItemStack}, or {@code null}. */
    private ItemStack resolveAlias(String itemString) {
        String code = aliases.inverse().get(itemString);
        if (code == null) {
            String[] typeParts = itemString.replaceAll("(?<!^)([A-Z1-9])", "_$1").toUpperCase(Locale.ROOT).split("[ _\\-]");
            int length = Short.MAX_VALUE;
            for (Map.Entry<String, String> entry : aliases.entrySet()) {
                if (entry.getValue().length() < length && entry.getValue().toUpperCase(Locale.ROOT).startsWith(itemString.toUpperCase(Locale.ROOT))) {
                    length = (short) entry.getValue().length();
                    code = entry.getKey();
                } else if (typeParts.length > 1) {
                    String[] nameParts = entry.getValue().toUpperCase(Locale.ROOT).split("[ _\\-]");
                    if (typeParts.length == nameParts.length) {
                        boolean matched = true;
                        for (int i = 0; i < nameParts.length; i++) {
                            if (!nameParts[i].startsWith(typeParts[i])) {
                                matched = false;
                                break;
                            }
                        }
                        if (matched) {
                            code = entry.getKey();
                            break;
                        }
                    }
                }
            }
        }
        return code != null ? itemCodes.decode(code) : null;
    }

    /** Apply a configured alias to {@code itemString} if one fits {@code maxWidth}, else the input. */
    private String applyAlias(String itemString, int maxWidth) {
        if (itemString == null) {
            return null;
        }
        String newCode;
        if (aliases.containsKey(itemString)) {
            newCode = aliases.get(itemString);
        } else if (!itemString.contains("#")) {
            newCode = aliases.get(itemString.toLowerCase(Locale.ROOT));
        } else {
            String[] parts = itemString.split("#", 2);
            String lowercaseCode = parts[0].toLowerCase(Locale.ROOT) + "#" + parts[1];
            newCode = aliases.containsKey(lowercaseCode) ? aliases.get(lowercaseCode) : null;
        }

        if (newCode == null) {
            return itemString;
        }
        if (maxWidth > 0) {
            int width = getMinecraftStringWidth(newCode);
            if (width > maxWidth) {
                log.warn("Can't use configured alias " + newCode + " as its width (" + width + ") was wider than the allowed max width of " + maxWidth);
                return itemString;
            }
        }
        return newCode;
    }
}
