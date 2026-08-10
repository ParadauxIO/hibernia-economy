package io.paradaux.chestshop.services.impl;

import io.paradaux.chestshop.services.MaterialService;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import io.paradaux.chestshop.utils.MaterialUtil;
import io.paradaux.chestshop.utils.SimpleCache;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConstructor;
import org.bukkit.configuration.file.YamlRepresenter;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.Tag;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The config/state-backed material logic split out of {@code MaterialUtil} (PAR-282): item
 * equality that honours the {@code EXCLUDED_ITEM_ATTRIBUTES} config, and the config-sized
 * material-name cache. The pure, stateless helpers stay static on
 * {@link MaterialUtil}; this service composes {@link MaterialUtil#resolveMaterial}.
 *
 * @author Acrobot
 */
@Singleton
public class MaterialServiceImpl implements MaterialService {

    private static final Yaml YAML = new Yaml(new YamlBukkitConstructor(), new YamlRepresenter(), new DumperOptions());

    private final ChestShopConfiguration config;
    private final SimpleCache<String, Material> materialCache;

    @Inject
    public MaterialServiceImpl(ChestShopConfiguration config) {
        this.config = config;
        this.materialCache = new SimpleCache<>(config.getCacheSize());
    }

    private static class YamlBukkitConstructor extends YamlConstructor {
        public YamlBukkitConstructor() {
            this.yamlConstructors.put(new Tag(Tag.PREFIX + "org.bukkit.inventory.ItemStack"), yamlConstructors.get(Tag.MAP));
        }
    }

    @Override
    public boolean equals(ItemStack one, ItemStack two) {
        if (one == null || two == null) {
            return one == two;
        }
        if (one.isSimilar(two)) {
            return true;
        }

        // Additional checks as serialisation and de-serialisation might lead to different item meta
        // This would only be done if the items share the same item meta type so it shouldn't be too inefficient
        // Special check for books as their pages might change when serialising (See SPIGOT-3206 and ChestShop#250)
        // Special check for explorer maps/every item with a localised name (See SPIGOT-4672)
        // Special check for legacy spawn eggs (See ChestShop#264)
        if (one.getType() != two.getType()
                || durability(one) != durability(two)
                || (one.hasItemMeta() && two.hasItemMeta() && one.getItemMeta().getClass() != two.getItemMeta().getClass())) {
            return false;
        }
        if (!one.hasItemMeta() && !two.hasItemMeta()) {
            return true;
        }
        ItemMeta oneMeta = one.getItemMeta();
        ItemMeta twoMeta = two.getItemMeta();
        // return true if both are null or same, false if only one is null
        if (oneMeta == twoMeta || oneMeta == null || twoMeta == null) {
            return oneMeta == twoMeta;
        }

        Map<String, Object> oneSerMeta = new HashMap<>(oneMeta.serialize());
        Map<String, Object> twoSerMeta = new HashMap<>(twoMeta.serialize());

        removeExcludedKeys(oneSerMeta);
        removeExcludedKeys(twoSerMeta);

        if (oneSerMeta.equals(twoSerMeta)) {
            return true;
        }

        // Try to use same parsing as the YAML dumper in the ItemDatabase when generating the code as the last resort
        ItemStack oneCloned = one.clone();
        oneCloned.setAmount(1);

        ItemStack twoCloned = two.clone();
        twoCloned.setAmount(1);

        ItemStack oneDumped = YAML.loadAs(YAML.dump(oneCloned), ItemStack.class);
        if (oneDumped.isSimilar(twoCloned)) {
            return true;
        }

        ItemMeta oneDumpedMeta = oneDumped.getItemMeta();
        if (oneDumpedMeta != null && oneDumpedMeta.serialize().equals(twoSerMeta)) {
            return true;
        }

        ItemStack twoDumped = YAML.loadAs(YAML.dump(twoCloned), ItemStack.class);
        if (oneDumped.isSimilar(twoDumped)) {
            return true;
        }

        ItemMeta twoDumpedMeta = twoDumped.getItemMeta();
        if (oneDumpedMeta != null && twoDumpedMeta != null) {
            Map<String, Object> oneSerDumpedMeta = new HashMap<>(oneDumpedMeta.serialize());
            Map<String, Object> twoSerDumpedMeta = new HashMap<>(twoDumpedMeta.serialize());

            removeExcludedKeys(oneSerDumpedMeta);
            removeExcludedKeys(twoSerDumpedMeta);

            if (oneSerDumpedMeta.equals(twoSerDumpedMeta)) {
                return true;
            }
        }

        // return true if both are null or same, false otherwise
        return oneDumpedMeta == twoDumpedMeta;
    }

    /**
     * Remove all keys included in the {@code EXCLUDED_ITEM_ATTRIBUTES} config option from a serialized
     * meta map
     * @param map The serialized item data to modify
     */
    private void removeExcludedKeys(Map<String, Object> map) {
        map.keySet().removeAll(config.getExcludedItemAttributes());
    }

    @Override
    public Material getMaterial(String name) {
        String formatted = name.replaceAll("(?<!^)(?>\\s?)([A-Z1-9])", "_$1").replace(' ', '_').toUpperCase(Locale.ROOT);

        Material cached = materialCache.get(formatted);
        if (cached != null) {
            return cached;
        }

        Material material = MaterialUtil.resolveMaterial(name);
        if (material != null) {
            materialCache.put(formatted, material);
        }
        return material;
    }

    /** The item's damage (was the deprecated {@code ItemStack.getDurability()}); 0 for non-damageable items. */
    private static int durability(ItemStack item) {
        return item.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable damageable ? damageable.getDamage() : 0;
    }
}
