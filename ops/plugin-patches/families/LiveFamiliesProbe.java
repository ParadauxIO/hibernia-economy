import org.bukkit.plugin.Plugin;
import java.lang.reflect.*;
import java.util.*;

public final class LiveFamiliesProbe {
    public static String run(Plugin plugin) throws Exception {
        ClassLoader loader = plugin.getClass().getClassLoader();
        Field configField = plugin.getClass().getDeclaredField("config");
        configField.setAccessible(true);
        Object config = configField.get(plugin);
        Object settings = config.getClass().getMethod("settings").invoke(config);
        String fallback = (String) settings.getClass().getMethod("defaultBackground").invoke(settings);
        List<?> choices = (List<?>) settings.getClass().getMethod("backgrounds").invoke(settings);
        Method render = Class.forName("com.minecraftcitiesnetwork.families.common.handler.familytree.TreeRendering", true, loader).getMethod("backgroundId", String.class);
        String expectedFallback = legacy(fallback);
        int count = 0;
        for (Object choice : choices) {
            check(render, (String) choice, legacy((String) choice)); count++;
        }
        for (String invalid : List.of("ExamplePlayer", "OtherPlayer_", "exampleplayer", "block/Bad", "bad:texture")) {
            check(render, invalid, expectedFallback); count++;
        }
        check(render, null, expectedFallback); count++;
        check(render, "minecraft:textures/block/red_wool.png", "block/red_wool"); count++;
        check(render, "block/custom_texture", "block/custom_texture"); count++;
        check(render, "STONE", "gui/advancements/backgrounds/stone"); count++;
        return "Families actual plugin: " + count + " cases PASS, default=" + expectedFallback;
    }
    static void check(Method method, String input, String expected) throws Exception {
        Object actual = method.invoke(null, input);
        if (!expected.equals(actual)) throw new AssertionError(input + " => " + actual + "; expected " + expected);
    }
    static String legacy(String value) {
        if (value == null || value.isBlank()) return "block/moss_block";
        value = value.trim();
        if (value.startsWith("minecraft:")) value = value.substring(10);
        if (value.startsWith("textures/")) value = value.substring(9);
        if (value.endsWith(".png")) value = value.substring(0, value.length() - 4);
        if (value.contains("/")) return value;
        String lower = value.toLowerCase(Locale.ROOT);
        return Set.of("stone", "nether", "end", "adventure", "husbandry").contains(lower) ? "gui/advancements/backgrounds/" + lower : "block/" + value;
    }
}
