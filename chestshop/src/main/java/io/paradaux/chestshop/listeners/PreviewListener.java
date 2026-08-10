package io.paradaux.chestshop.listeners;

import io.paradaux.chestshop.services.PreviewService;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.chestshop.services.MarketService;
import io.paradaux.treasury.api.market.ShopResult;
import org.bukkit.Chunk;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.UUID;

/**
 * Loads and unloads shop hologram previews with their chunk, and applies a
 * joining player's stored preview preference. Reads the registry through the
 * in-process the market service; inert if Treasury isn't present.
 */
@Singleton
public final class PreviewListener implements Listener {

    /** Default: players see previews unless they've turned them off. */
    private static final boolean DEFAULT_PREVIEW_VISIBLE = true;

    private final JavaPlugin plugin;
    private final PreviewService previews;

    private final io.paradaux.chestshop.services.MarketService marketService;

    @Inject
    public PreviewListener(JavaPlugin plugin, PreviewService previews, MarketService marketService) {
        this.marketService = marketService;
        this.plugin = plugin;
        this.previews = previews;
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!marketService.searchEnabled()) {
            return;
        }
        Chunk chunk = event.getChunk();
        String world = chunk.getWorld().getName();
        int cx = chunk.getX();
        int cz = chunk.getZ();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            List<ShopResult> shops;
            try {
                shops = marketService.shopQuery().shopsInChunk(world, cx, cz);
            } catch (RuntimeException e) {
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!chunk.isLoaded()) {
                    return; // unloaded again before we got back
                }
                for (ShopResult shop : shops) {
                    previews.render(shop);
                }
            });
        });
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
                visible = marketService.shopQuery().previewVisible(id, DEFAULT_PREVIEW_VISIBLE);
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
