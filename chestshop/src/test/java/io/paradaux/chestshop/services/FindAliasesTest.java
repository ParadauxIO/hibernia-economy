package io.paradaux.chestshop.services;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FindAliasesTest {
    @TempDir
    Path root;

    @Test
    void importsLegacySearchAliasesOnceWithoutChangingItemOrSignIdentity() throws Exception {
        Path legacy = root.resolve("ChestShopDatabase/item-code-groupings.yml");
        Files.createDirectories(legacy.getParent());
        String yaml = "groupings:\n  - item-code: DIAMOND\n    aliases: [gem, precious]\n";
        Files.writeString(legacy, yaml);
        var aliases = new FindAliases(root.resolve("ChestShop").toFile());
        assertThat(aliases.normalize("GeM")).isEqualTo("DIAMOND");
        assertThat(aliases.normalize("unrelated#42")).isEqualTo("unrelated#42");
        assertThat(aliases.names()).containsExactlyInAnyOrder("gem", "precious");
        assertThat(Files.readString(legacy)).isEqualTo(yaml);
        Files.writeString(
                root.resolve("ChestShop/find-aliases.yml"), "groupings: [{item-code: EMERALD, aliases: [gem]}]\n");
        aliases.reload();
        assertThat(aliases.normalize("gem")).isEqualTo("EMERALD");
        assertThat(aliases.names()).containsExactly("gem");
    }

    @Test
    void missingLegacyFileCreatesAnEmptyNativeConfiguration() {
        var aliases = new FindAliases(root.resolve("ChestShop").toFile());
        assertThat(aliases.names()).isEmpty();
        assertThat(root.resolve("ChestShop/find-aliases.yml")).exists();
    }

    @Test
    void failedReloadKeepsTheLastWorkingMappingAndPreservesTheFile() throws Exception {
        Path file = root.resolve("ChestShop/find-aliases.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "groupings: [{item-code: DIAMOND, aliases: [gem]}]\n");
        var aliases = new FindAliases(file.getParent().toFile());
        String invalid = "groupings: [unterminated";
        Files.writeString(file, invalid);
        assertThatThrownBy(aliases::reload).isInstanceOf(IllegalStateException.class);
        assertThat(aliases.normalize("gem")).isEqualTo("DIAMOND");
        assertThat(Files.readString(file)).isEqualTo(invalid);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "groupings: wrong",
                "groupings: [wrong]",
                "groupings: [{item-code: '', aliases: [gem]}]",
                "groupings: [{item-code: DIAMOND, aliases: wrong}]",
                "groupings: [{item-code: DIAMOND, aliases: [1]}]",
                "groupings: [{item-code: DIAMOND, aliases: ['']}]",
                "groupings: [{item-code: DIAMOND, aliases: [gem]}, {item-code: EMERALD, aliases: [GEM]}]"
            })
    void rejectsAmbiguousOrMalformedImportsWithoutWritingTheNativeFile(String contents) throws Exception {
        Path file = root.resolve("ChestShopDatabase/item-code-groupings.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, contents);
        assertThatThrownBy(() -> new FindAliases(root.resolve("ChestShop").toFile()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(root.resolve("ChestShop/find-aliases.yml")).doesNotExist();
        assertThat(Files.readString(file)).isEqualTo(contents);
    }

    @Test
    void repeatedAliasesForTheSameCodeAreHarmless() throws Exception {
        Path file = root.resolve("ChestShopDatabase/item-code-groupings.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "groupings: [{item-code: DIAMOND, aliases: [gem, GEM]}]");
        assertThat(new FindAliases(root.resolve("ChestShop").toFile()).normalize("gem"))
                .isEqualTo("DIAMOND");
    }
}
