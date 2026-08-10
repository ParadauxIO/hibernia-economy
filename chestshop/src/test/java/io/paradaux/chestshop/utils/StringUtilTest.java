package io.paradaux.chestshop.utils;

import org.bukkit.ChatColor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StringUtilTest {

    // ── capitalizeFirstLetter ─────────────────────────────────────────────────

    @ParameterizedTest
    @CsvSource({
        "'hello world',   'Hello World'",
        "'HELLO WORLD',   'Hello World'",
        "'foo',           'Foo'",
        "'one two three', 'One Two Three'",
    })
    void capitalizeFirstLetter_capitalizesEachWordWithDefaultSeparator(String in, String expected) {
        assertThat(StringUtil.capitalizeFirstLetter(in)).isEqualTo(expected);
    }

    @Test
    void capitalizeFirstLetter_supportsCustomSeparator() {
        // Note: implementation always joins with a space, regardless of input separator.
        assertThat(StringUtil.capitalizeFirstLetter("foo_bar_baz", '_'))
                .isEqualTo("Foo Bar Baz");
    }

    @Test
    void capitalizeFirstLetter_returnsInputForNullOrEmpty() {
        assertThat(StringUtil.capitalizeFirstLetter(null)).isNull();
        assertThat(StringUtil.capitalizeFirstLetter("")).isEqualTo("");
    }

    // ── stripColourCodes ──────────────────────────────────────────────────────

    @Test
    void stripColourCodes_removesSingleStringColors() {
        String coloured = ChatColor.RED + "Red" + ChatColor.RESET + " text";
        assertThat(StringUtil.stripColourCodes(coloured)).isEqualTo("Red text");
    }

    @Test
    void stripColourCodes_processesArrays() {
        String[] in  = { ChatColor.GREEN + "ok", ChatColor.RED + "bad" };
        String[] out = StringUtil.stripColourCodes(in);

        assertThat(out).containsExactly("ok", "bad");
        // Doesn't mutate the input array.
        assertThat(in[0]).startsWith("§");
    }

    // ── getMinecraftCharWidth / getMinecraftStringWidth ───────────────────────

    @ParameterizedTest
    @CsvSource({
        "' ', 4",     // space
        "'a', 6",     // alphabetic
        "'I', 4",
        "'i', 2",
        "'.', 2",
    })
    void getMinecraftCharWidth_returnsHardcodedWidth(char c, int expected) {
        assertThat(StringUtil.getMinecraftCharWidth(c)).isEqualTo(expected);
    }

    @Test
    void getMinecraftCharWidth_returnsZeroForColourMarker() {
        assertThat(StringUtil.getMinecraftCharWidth(ChatColor.COLOR_CHAR)).isEqualTo(0);
    }

    @Test
    void getMinecraftCharWidth_returnsTenForUnknownChar() {
        // 中 is not in the hardcoded character table → fallback width of 10.
        assertThat(StringUtil.getMinecraftCharWidth('中')).isEqualTo(10);
    }

    @Test
    void getMinecraftStringWidth_sumsCharWidths() {
        // "ab" → 6 + 6 = 12
        assertThat(StringUtil.getMinecraftStringWidth("ab")).isEqualTo(12);
        // empty string → 0
        assertThat(StringUtil.getMinecraftStringWidth("")).isEqualTo(0);
    }

    // ── strip ─────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @CsvSource({
        "'  hello  ',          'hello'",
        "'hello',              'hello'",
        "'  ',                 ''",
        "'',                   ''",
        "'  hello world  ',    'hello world'",
    })
    void strip_removesLeadingAndTrailingWhitespace(String in, String expected) {
        assertThat(StringUtil.strip(in)).isEqualTo(expected);
    }

    @Test
    void strip_returnsNullForNullInput() {
        assertThat(StringUtil.strip(null)).isNull();
    }

    @Test
    void strip_preservesInternalWhitespace() {
        assertThat(StringUtil.strip("  hello  world  ")).isEqualTo("hello  world");
    }

    // ── map ───────────────────────────────────────────────────────────────────

    @Test
    void map_buildsLinkedHashMapFromVarargs() {
        Map<String, String> result = StringUtil.map("a", "1", "b", "2");
        assertThat(result).containsExactly(
                Map.entry("a", "1"),
                Map.entry("b", "2"));
    }

    @Test
    void map_dropsTrailingOddArgument() {
        // Implementation iterates by `i + 1 < values.length`, so a dangling key is dropped.
        Map<String, String> result = StringUtil.map("a", "1", "lonely");
        assertThat(result).containsExactly(Map.entry("a", "1"));
    }

    @Test
    void map_returnsEmptyMapForNoArgs() {
        assertThat(StringUtil.map()).isEmpty();
    }
}
