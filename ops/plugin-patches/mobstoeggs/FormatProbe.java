import fyi.sugar.mobstoeggs.utility.TextFormatManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

/** Runs the plugin's actual formatter against the selected server libraries. */
public class FormatProbe {
    private static void check(String input, Component expected) {
        Component actual = TextFormatManager.INSTANCE.parseFormat(input);
        if (!actual.equals(expected)) {
            throw new AssertionError(input + " expected=" + expected + " actual=" + actual);
        }
    }

    public static void main(String[] args) {
        check("Capture failed", Component.text("Capture failed").decoration(TextDecoration.ITALIC, false));
        check("&cNo permission", Component.text("No permission", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
        check("&#12ab34Caught", Component.text("Caught", TextColor.color(0x12ab34)).decoration(TextDecoration.ITALIC, false));
        check("&x&1&2&a&b&3&4Caught", Component.text("Caught", TextColor.color(0x12ab34)).decoration(TextDecoration.ITALIC, false));
        check("<green>Caught</green>", Component.text("Caught", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
        System.out.println("PASS: plain, permission denial, hex, repeated hex, MiniMessage");
    }
}
