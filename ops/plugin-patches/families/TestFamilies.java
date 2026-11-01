import java.util.*;
import java.lang.reflect.*;
public class TestFamilies {
    public static void main(String[] args) throws Exception {
        boolean patched = args[0].equals("patched");
        if (patched) Class.forName("com.minecraftcitiesnetwork.families.patch.SafeBackground").getMethod("configure", List.class, String.class).invoke(null, List.of("moss_block", "oak_planks", "red_wool", "pink_wool", "diamond_block", "gold_block", "custom_texture"), "moss_block");
        Method method = Class.forName("com.minecraftcitiesnetwork.families.common.handler.familytree.TreeRendering").getMethod("backgroundId", String.class);
        String[][] cases = {{"ExamplePlayer", "block/moss_block"}, {"OtherPlayer_", "block/moss_block"}, {"exampleplayer", "block/moss_block"}, {"oak_planks", "block/oak_planks"}, {"custom_texture", "block/custom_texture"}, {"minecraft:textures/block/red_wool.png", "block/red_wool"}, {"block/custom_pack_texture", "block/custom_pack_texture"}, {"STONE", "gui/advancements/backgrounds/stone"}, {"invalid:texture", "block/moss_block"}, {"block/Bad", "block/moss_block"}, {"../bad", "block/moss_block"}, {null, "block/moss_block"}, {"  ", "block/moss_block"}};
        int failures = 0;
        for (String[] test : cases) {
            String actual = (String) method.invoke(null, test[0]);
            if (!test[1].equals(actual)) { failures++; System.out.println("FAIL " + test[0] + " => " + actual + "; expected " + test[1]); }
        }
        Constructor<?> resource = Class.forName("com.github.retrooper.packetevents.resources.ResourceLocation").getConstructor(String.class, String.class);
        int packetFailures = 0;
        for (String name : List.of("ExamplePlayer", "OtherPlayer_")) {
            String id = (String) method.invoke(null, name);
            try { resource.newInstance("minecraft", id); }
            catch (InvocationTargetException failure) { packetFailures++; System.out.println("ResourceLocation rejects " + id + ": " + failure.getCause()); }
        }
        if (patched ? packetFailures != 0 : packetFailures != 2) throw new AssertionError("Unexpected packet failures " + packetFailures);
        if (patched && failures != 0) throw new AssertionError(failures + " failures");
        if (!patched && failures == 0) throw new AssertionError("Original did not reproduce bug");
        System.out.println(args[0] + ": " + cases.length + " cases, " + failures + " failing");
    }
}
