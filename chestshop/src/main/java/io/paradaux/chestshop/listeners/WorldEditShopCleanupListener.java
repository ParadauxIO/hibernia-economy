package io.paradaux.chestshop.listeners;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.extent.AbstractDelegateExtent;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.util.eventbus.Subscribe;
import com.sk89q.worldedit.world.block.BlockCategories;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import io.paradaux.chestshop.services.MarketService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Keeps the shop registry clean when WorldEdit/FastAsyncWorldEdit bulk-removes
 * shops: WE bypasses Bukkit block events, so we wrap the edit extent and capture
 * positions where a sign is being overwritten, then deactivate those shops. A
 * port of the legacy chestshop-database WorldEdit adapter, narrowed to sign
 * removals so a large edit doesn't enqueue millions of no-op deactivations.
 *
 * <p>This class references WorldEdit types, so it is only loaded/constructed when
 * WorldEdit is present (guarded in the plugin bootstrap). Deactivation is a no-op
 * for any captured position that wasn't actually a shop.
 */
public final class WorldEditShopCleanupListener implements Listener {

    private record Pos(String world, int x, int y, int z) {}

    private final JavaPlugin plugin;
    private final io.paradaux.chestshop.services.MarketService marketService;
    private final Queue<Pos> queue = new ConcurrentLinkedDeque<>();

    public WorldEditShopCleanupListener(JavaPlugin plugin, MarketService marketService) {
        this.marketService = marketService;
        this.plugin = plugin;
    }

    /** Hook WorldEdit's event bus + the Bukkit disable event + the drain task. */
    public void register() {
        WorldEdit.getInstance().getEventBus().register(this);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::drain, 20L, 20L);
        plugin.getLogger().info("WorldEdit shop-cleanup adapter enabled.");
    }

    private void drain() {
        if (!marketService.enabled() || queue.isEmpty()) {
            return;
        }
        Set<Pos> batch = new HashSet<>();
        Pos pos;
        while ((pos = queue.poll()) != null) {
            batch.add(pos);
        }
        for (Pos p : batch) {
            marketService.market().deactivateShop(p.world(), p.x(), p.y(), p.z());
        }
    }

    @Subscribe
    public void onEditSession(EditSessionEvent event) {
        if (event.getWorld() == null || event.getActor() == null
                || event.getStage() != EditSession.Stage.BEFORE_HISTORY) {
            return;
        }
        event.setExtent(new SignRemovalLogger(queue, event.getExtent(), event.getWorld().getName()));
    }

    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        if (event.getPlugin().equals(plugin)) {
            WorldEdit.getInstance().getEventBus().unregister(this);
        }
    }

    /** Captures positions where a sign is being overwritten by an edit. */
    private static final class SignRemovalLogger extends AbstractDelegateExtent {

        private final Queue<Pos> queue;
        private final String world;

        private SignRemovalLogger(Queue<Pos> queue, Extent extent, String world) {
            super(extent);
            this.queue = queue;
            this.world = world;
        }

        @Override
        public <T extends BlockStateHolder<T>> boolean setBlock(BlockVector3 position, T block)
                throws WorldEditException {
            BlockState existing = getExtent().getBlock(position);
            if (existing != null && BlockCategories.SIGNS.contains(existing.getBlockType())) {
                queue.add(new Pos(world, position.x(), position.y(), position.z()));
            }
            return super.setBlock(position, block);
        }
    }
}
