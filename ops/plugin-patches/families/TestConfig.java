import java.nio.file.*;
import java.util.logging.*;
public class TestConfig {
 public static void main(String[] args) throws Exception {
  Class<?> config = Class.forName("com.minecraftcitiesnetwork.families.config.FamiliesConfig");
  Object instance = config.getConstructor(Path.class, Logger.class).newInstance(Path.of(args[0]), Logger.getLogger("FamiliesTest"));
  config.getMethod("load").invoke(instance);
  Class<?> rendering = Class.forName("com.minecraftcitiesnetwork.families.common.handler.familytree.TreeRendering");
  for (String[] pair : new String[][]{{"oak_planks","block/oak_planks"},{"red_wool","block/red_wool"},{"exampleplayer","block/moss_block"}}) {
   Object value = rendering.getMethod("backgroundId", String.class).invoke(null, pair[0]);
   if (!pair[1].equals(value)) throw new AssertionError(pair[0] + " => " + value);
  }
  System.out.println("Actual FamiliesConfig.load hook + 3 rendering checks PASS");
 }
}
