package io.paradaux.treasury.model.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.math.BigDecimal;

/** Reload is strict; startup retains the existing defaults and warning-based recovery. */
final class ReloadValidation {
    private ReloadValidation() {}

    static void section(FileConfiguration config, String path) {
        Object value = config.get(path);
        if (value != null && !(value instanceof ConfigurationSection)) {
            throw new IllegalArgumentException(path + " must be a section");
        }
    }

    static void scalar(FileConfiguration config, String path, Class<?> type) {
        Object value = config.get(path);
        if (value != null && !type.isInstance(value)) {
            throw new IllegalArgumentException(path + " must be a " + type.getSimpleName());
        }
    }

    static void decimal(Object value, String path, boolean rate) {
        if (value == null) return;
        try {
            BigDecimal number = new BigDecimal(value.toString().trim());
            if (number.signum() >= 0 && (!rate || number.compareTo(BigDecimal.ONE) <= 0)) return;
        } catch (NumberFormatException ignored) {
            // Report the key, without leaking arbitrary config values into command output.
        }
        throw new IllegalArgumentException(path + (rate ? " must be a number in [0,1]" : " must be a non-negative number"));
    }

    static void entries(FileConfiguration config, String path, boolean rate, boolean thresholds) {
        section(config, path);
        ConfigurationSection section = config.getConfigurationSection(path);
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            if (thresholds) decimal(key, path + "." + key, false);
            decimal(section.get(key), path + "." + key, rate);
        }
    }
}
