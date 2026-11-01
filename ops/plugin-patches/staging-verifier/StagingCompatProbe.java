import java.nio.file.*;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Temporary, one-shot verifier. No world, player, economy, or plugin config writes. */
public final class StagingCompatProbe extends JavaPlugin {
  interface Check { String run(Plugin plugin) throws Exception; }
  @Override public void onEnable() {
    Bukkit.getScheduler().runTaskLater(this, () -> {
      var results = new ArrayList<String>();
      check(results, "CarbonMessageDelete", p -> { LiveCarbonProbe.run(p); return "signed/unsigned rendering and unsigned callbacks"; });
      check(results, "MusicBox", p -> { LiveMusicBoxProbe.run(p); return "real modern and legacy disc item reads"; });
      check(results, "Families", LiveFamiliesProbe::run);
      check(results, "BountyHunters", LiveBountyProbe::run);
      try {
        Files.createDirectories(getDataFolder().toPath());
        Files.write(getDataFolder().toPath().resolve("results.txt"), results);
      } catch (Exception e) { getLogger().log(java.util.logging.Level.SEVERE, "Could not retain verification results", e); }
      Bukkit.getPluginManager().disablePlugin(this);
    }, 100);
  }
  private void check(List<String> results, String name, Check check) {
    try {
      Plugin actual = Bukkit.getPluginManager().getPlugin(name);
      if (actual == null || !actual.isEnabled()) throw new IllegalStateException(name + " is not enabled");
      String result = "PASS " + name + ": " + check.run(actual);
      results.add(result); getLogger().info(result);
    } catch (Throwable e) {
      results.add("FAIL " + name + ": " + e);
      getLogger().log(java.util.logging.Level.SEVERE, "FAIL " + name, e);
    }
  }
}
