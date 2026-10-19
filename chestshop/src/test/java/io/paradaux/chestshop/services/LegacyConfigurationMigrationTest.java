package io.paradaux.chestshop.services;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegacyConfigurationMigrationTest {
    @TempDir
    Path root;

    @Test
    void importsOnlyUsableMissingAliasesWithoutReplacingNativeChoicesAndCanBeRerun() throws Exception {
        Files.createDirectories(root.resolve("Utilities"));
        Files.createDirectories(root.resolve("ChestShop"));
        Files.writeString(
                root.resolve("Utilities/config.yml"),
                "aliases:\n  short: full_custom_id\n  conflict: old\n  unused: null\n  blank: ''\ndisplay:\n  fallbackFormat: '%s'\n");
        Files.writeString(root.resolve("ChestShop/nexo.yml"), "aliases:\n  conflict: native\n");
        assertThat(LegacyConfigurationMigration.mergeAliases(
                        root.resolve("ChestShop").toFile()))
                .isEqualTo(1);
        var config = YamlConfiguration.loadConfiguration(
                root.resolve("ChestShop/nexo.yml").toFile());
        assertThat(config.getString("aliases.short")).isEqualTo("full_custom_id");
        assertThat(config.getString("aliases.conflict")).isEqualTo("native");
        assertThat(config.contains("aliases.unused")).isFalse();
        assertThat(config.contains("aliases.blank")).isFalse();
        assertThat(LegacyConfigurationMigration.mergeAliases(
                        root.resolve("ChestShop").toFile()))
                .isZero();
    }

    @Test
    void noLegacyConfigDoesNotGenerateEmptyNativeFiles() {
        assertThat(LegacyConfigurationMigration.mergeAliases(
                        root.resolve("ChestShop").toFile()))
                .isZero();
        assertThat(root.resolve("ChestShop/nexo.yml")).doesNotExist();
    }

    @Test
    void invalidNativeYamlIsNeverOverwritten() throws Exception {
        Files.createDirectories(root.resolve("Utilities"));
        Files.createDirectories(root.resolve("ChestShop"));
        Files.writeString(root.resolve("Utilities/config.yml"), "aliases: {short: full_id}\n");
        Path nativeFile = root.resolve("ChestShop/nexo.yml");
        Files.writeString(nativeFile, "aliases: [broken\n");
        assertThatThrownBy(() -> LegacyConfigurationMigration.mergeAliases(
                        root.resolve("ChestShop").toFile()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(Files.readString(nativeFile)).isEqualTo("aliases: [broken\n");
    }

    @Test
    void metadataAliasAlsoReachesTheNativeVanillaCodeResolver() throws Exception {
        Files.createDirectories(root.resolve("Utilities"));
        Files.createDirectories(root.resolve("ChestShop"));
        Files.writeString(root.resolve("Utilities/config.yml"), "aliases:\n  coat: 'Leather Chestplate:80#7Nj'\n");
        LegacyConfigurationMigration.mergeAliases(root.resolve("ChestShop").toFile());
        var aliases = YamlConfiguration.loadConfiguration(
                root.resolve("ChestShop/itemAliases.yml").toFile());
        assertThat(aliases.getString("Leather Chestplate:80#7Nj")).isEqualTo("coat");
    }
}
