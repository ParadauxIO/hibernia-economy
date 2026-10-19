package io.paradaux.chestshop.services;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;

/** Search-only aliases, independent of sign/item identity and the Nexo alias store. */
@Singleton
public final class FindAliases {
    private final File folder;
    private volatile Map<String, String> aliases = Map.of();

    @Inject
    public FindAliases(@Named("dataFolder") File folder) {
        this.folder = folder;
        reload();
    }

    public void reload() {
        File target = new File(folder, "find-aliases.yml");
        File source = target.exists()
                ? target
                : new File(folder.getParentFile(), "ChestShopDatabase/item-code-groupings.yml");
        YamlConfiguration config = new YamlConfiguration();
        Map<String, String> loaded = new LinkedHashMap<>();
        try {
            if (source.exists()) config.load(source);
            Object groups = config.get("groupings");
            if (groups != null && !(groups instanceof List<?>))
                throw new IllegalArgumentException("groupings must be a list");
            if (groups instanceof List<?> list) {
                for (Object entry : list) {
                    if (!(entry instanceof Map<?, ?> group)
                            || !(group.get("item-code") instanceof String code)
                            || code.isBlank()
                            || !(group.get("aliases") instanceof List<?> names))
                        throw new IllegalArgumentException("Each search grouping requires item-code and aliases");
                    for (Object name : names) {
                        if (!(name instanceof String alias) || alias.isBlank())
                            throw new IllegalArgumentException("Search aliases must be nonblank strings");
                        String key = alias.toLowerCase(Locale.ROOT);
                        String previous = loaded.putIfAbsent(key, code);
                        if (previous != null && !previous.equals(code))
                            throw new IllegalArgumentException("Conflicting search alias: " + alias);
                    }
                }
            }
            if (!target.exists()) {
                Files.createDirectories(folder.toPath());
                if (!config.isSet("groupings")) config.set("groupings", List.of());
                config.save(target);
            }
        } catch (IOException | org.bukkit.configuration.InvalidConfigurationException | IllegalArgumentException e) {
            throw new IllegalStateException("Cannot load finder aliases; original files preserved", e);
        }
        aliases = Map.copyOf(loaded);
    }

    public String normalize(String code) {
        return aliases.getOrDefault(code.toLowerCase(Locale.ROOT), code);
    }

    public java.util.Set<String> names() {
        return aliases.keySet();
    }
}
