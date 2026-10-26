package io.paradaux.chestshop.services;

import java.io.File;
import java.io.IOException;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** Additive, idempotent upgrade of Utilities' sign aliases. Original files remain untouched. */
@lombok.extern.slf4j.Slf4j
public final class LegacyConfigurationMigration {
    private LegacyConfigurationMigration() {}

    public static int mergeAliases(File folder) {
        File source = new File(folder.getParentFile(), "Utilities/config.yml");
        if (!source.isFile()) return 0;
        File destination = new File(folder, "nexo.yml");
        YamlConfiguration legacy = load(source);
        YamlConfiguration nativeConfig = load(destination);
        ConfigurationSection aliases = legacy.getConfigurationSection("aliases");
        if (aliases == null) return 0;
        File itemAliasFile = new File(folder, "itemAliases.yml");
        YamlConfiguration itemAliases = load(itemAliasFile);
        boolean metadataAdded = false;
        int added = 0;
        for (String alias : aliases.getKeys(false)) {
            Object value = aliases.get(alias);
            String path = "aliases." + alias;
            if (value instanceof String id
                    && !id.isBlank()
                    && nativeConfig.contains(path)
                    && !id.equals(nativeConfig.getString(path))) {
                log.warn("Keeping native ChestShop alias '{}' instead of conflicting Utilities mapping", alias);
            }
            if (value instanceof String id && !id.isBlank() && !nativeConfig.contains(path)) {
                nativeConfig.set(path, id);
                added++;
                if (id.contains("#")
                        && !itemAliases.contains(id)
                        && !itemAliases.getValues(false).containsValue(alias)) {
                    itemAliases.set(id, alias);
                    metadataAdded = true;
                }
            }
        }
        for (String path : new String[] {
            "input.acceptBareIds",
            "input.supportItemsAdder",
            "display.preferAlias",
            "display.fallbackFormat",
            "display.signMaxChars"
        }) {
            if (!nativeConfig.contains(path) && legacy.contains(path)) nativeConfig.set(path, legacy.get(path));
        }
        if (added > 0 || !nativeConfig.saveToString().equals(load(destination).saveToString())) {
            try {
                nativeConfig.save(destination);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot migrate legacy custom-item aliases", e);
            }
        }
        if (metadataAdded) {
            try {
                itemAliases.save(itemAliasFile);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot migrate metadata aliases", e);
            }
        }
        return added;
    }

    private static YamlConfiguration load(File file) {
        YamlConfiguration config = new YamlConfiguration();
        if (!file.exists()) return config;
        try {
            config.load(file);
        } catch (IOException | org.bukkit.configuration.InvalidConfigurationException e) {
            throw new IllegalStateException("Cannot read " + file.getName() + "; original file preserved", e);
        }
        return config;
    }
}
