package io.paradaux.chestshop.listeners;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.chestshop.services.MarketService;
import io.paradaux.chestshop.services.PreviewService;
import java.util.UUID;
import org.bukkit.Chunk;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Loads and unloads shop hologram previews with their chunk, and applies a
 * joining player's stored preview preference. Reads the registry through the
 * in-process the market service; inert if Treasury isn't present.
 */
@Singleton
public final class PreviewListener implements Listener {

    /** Default: players see previews unless they've turned them off. */
    private final io.paradaux.chestshop.services.FindPresentation presentation;

    private final io.paradaux.chestshop.services.MarketResyncService resync;
    private final JavaPlugin plugin;
    private final PreviewService previews;

    private final io.paradaux.chestshop.services.MarketService marketService;

    @Inject
    public PreviewListener(
            JavaPlugin plugin,
            PreviewService previews,
            MarketService marketService,
            io.paradaux.chestshop.services.FindPresentation presentation,
            io.paradaux.chestshop.services.MarketResyncService resync) {
        this.resync = resync;
        this.presentation = presentation;
        this.marketService = marketService;
        this.plugin = plugin;
        this.previews = previews;
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        Chunk chunk = event.getChunk();
        previews.loadChunk(chunk.getWorld().getName(), chunk.getX(), chunk.getZ());
        resync.discoverChunk(chunk);
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        Chunk chunk = event.getChunk();
        previews.destroyChunk(chunk.getWorld().getName(), chunk.getX(), chunk.getZ());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!marketService.searchEnabled()) {
            return;
        }
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean visible;
            try {
                visible = marketService.shopQuery().previewVisible(id, presentation.defaultVisible());
            } catch (RuntimeException e) {
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    previews.applyPreference(player, visible);
                }
            });
        });
    }
}
