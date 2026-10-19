package io.paradaux.chestshop.services;

import com.google.gson.JsonParser;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

/** Bounded, resumable preference backfill through Treasury's public API. */
@Singleton
public final class LegacyPreferenceImportService {
    private final JavaPlugin plugin;
    private final File folder;
    private final MarketService market;
    private final AtomicBoolean running = new AtomicBoolean();

    @Inject
    public LegacyPreferenceImportService(JavaPlugin plugin, @Named("dataFolder") File folder, MarketService market) {
        this.plugin = plugin;
        this.folder = folder;
        this.market = market;
    }

    public record Preference(UUID player, boolean visible) {
        public static Preference parse(String line) {
            var json = JsonParser.parseString(line).getAsJsonObject();
            var id = json.get("player_uuid");
            var visible = json.get("visible");
            if (id == null
                    || !id.isJsonPrimitive()
                    || !id.getAsJsonPrimitive().isString()
                    || visible == null
                    || !visible.isJsonPrimitive()
                    || !visible.getAsJsonPrimitive().isBoolean())
                throw new IllegalArgumentException("Invalid preference row");
            UUID uuid = UUID.fromString(id.getAsString());
            if (!uuid.toString().equalsIgnoreCase(id.getAsString()))
                throw new IllegalArgumentException("Noncanonical player UUID");
            return new Preference(uuid, visible.getAsBoolean());
        }
    }

    public void importPreferences(CommandSender sender) {
        if (!market.enabled()) {
            sender.sendMessage("Preference import requires Treasury MarketApi.");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            sender.sendMessage("A preference import is already running.");
            return;
        }
        try {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> read(sender));
        } catch (RuntimeException e) {
            running.set(false);
            throw e;
        }
    }

    private void read(CommandSender sender) {
        int accepted = 0;
        int line = 0;
        String result = "Finished.";
        try (var reader = Files.newBufferedReader(new File(folder, "legacy-preferences.jsonl").toPath())) {
            String text;
            while ((text = reader.readLine()) != null) {
                line++;
                Preference row = Preference.parse(text);
                var task = plugin.getServer().getScheduler().callSyncMethod(plugin, () -> {
                    market.market().importPreviewPreference(row.player(), row.visible());
                    return null;
                });
                try {
                    task.get(60, TimeUnit.SECONDS);
                } catch (Exception e) {
                    task.cancel(false);
                    throw e;
                }
                accepted++;
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            result = "Stopped at line " + line + "; correct the export or write failure and rerun.";
            plugin.getLogger().log(java.util.logging.Level.WARNING, result, e);
        } finally {
            running.set(false);
        }
        String summary = "Preference import: " + accepted + " accepted (including existing preferences). " + result;
        plugin.getLogger().info(summary);
        if (plugin.isEnabled()) plugin.getServer().getScheduler().runTask(plugin, () -> sender.sendMessage(summary));
    }
}
