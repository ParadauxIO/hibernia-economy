package io.paradaux.chestshop.listeners;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.entity.BaseEntity;
import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.OperationQueue;
import com.sk89q.worldedit.function.operation.RunContext;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldedit.util.eventbus.Subscribe;
import com.sk89q.worldedit.world.biome.BiomeType;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import io.paradaux.chestshop.services.MarketService;
import io.paradaux.chestshop.services.MarketSyncService;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Keeps the shop registry in step when WorldEdit/FastAsyncWorldEdit bulk-edits
 * shops: WE bypasses Bukkit block events, so we wrap the edit extent and capture
 * positions where a sign is being overwritten or placed. A port of the legacy
 * chestshop-database WorldEdit adapter, narrowed to signs so a large edit doesn't
 * enqueue millions of no-op positions.
 *
 * <p>WorldEdit positions are published after its commit operation completes; FAWE
 * uses its completed-chunk postprocessor because queued edits bypass setBlock.
 * The bounded main-thread drain reconciles live world state, including undo/paste.
 *
 * <p>This class references WorldEdit types, so it is only loaded/constructed when
 * WorldEdit is present (guarded in the plugin bootstrap).
 */
public final class WorldEditShopCleanupListener implements Listener {

    record Pos(String world, int x, int y, int z) {}

    private final JavaPlugin plugin;
    private final boolean fawe;
    private final MarketService marketService;
    private final MarketSyncService marketSync;
    private final Set<Pos> deferred = new HashSet<>();
    private final Queue<Pos> queue = new ConcurrentLinkedDeque<>();

    public WorldEditShopCleanupListener(JavaPlugin plugin, MarketService marketService, MarketSyncService marketSync) {
        this.marketService = marketService;
        this.marketSync = marketSync;
        this.plugin = plugin;
        this.fawe = plugin.getServer().getPluginManager().isPluginEnabled("FastAsyncWorldEdit");
    }

    /** Hook WorldEdit's event bus + the Bukkit disable event + the drain task. */
    public void register() {
        WorldEdit.getInstance().getEventBus().register(this);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::drain, 20L, 20L);
        plugin.getLogger().info("WorldEdit shop-cleanup adapter enabled.");
    }

    void drain() {
        if (!marketService.enabled() || queue.isEmpty()) {
            return;
        }
        Set<Pos> batch = new HashSet<>();
        Pos pos;
        for (int i = 0; i < 256 && (pos = queue.poll()) != null; i++) {
            batch.add(pos);
        }
        for (Pos p : batch) {
            World world = plugin.getServer().getWorld(p.world());
            if (world == null || !world.isChunkLoaded(p.x() >> 4, p.z() >> 4)) {
                deferred.add(p);
            } else {
                marketSync.onBlockReplaced(p.world(), p.x(), p.y(), p.z());
            }
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        deferred.removeIf(pos -> {
            if (pos.world().equals(event.getWorld().getName())
                    && (pos.x() >> 4) == event.getChunk().getX()
                    && (pos.z() >> 4) == event.getChunk().getZ()) {
                queue.add(pos);
                return true;
            }
            return false;
        });
    }

    @Subscribe
    public void onEditSession(EditSessionEvent event) {
        if (!marketService.enabled()
                || event.getWorld() == null
                || event.getStage() != EditSession.Stage.BEFORE_CHANGE) {
            return;
        }
        if (fawe) {
            FaweShopCleanup.install(event, queue);
        } else {
            event.setExtent(new SignChangeLogger(
                    queue, event.getExtent(), event.getWorld().getName()));
        }
    }

    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        if (event.getPlugin().equals(plugin)) {
            WorldEdit.getInstance().getEventBus().unregister(this);
            queue.clear();
            deferred.clear();
        }
    }

    /** Captures positions where an edit overwrites a sign or places one (a paste, or an undo). */
    static final class SignChangeLogger implements Extent {

        private final Queue<Pos> queue;
        private final String world;
        private final Extent extent;
        private final Set<Pos> changed = ConcurrentHashMap.newKeySet();

        SignChangeLogger(Queue<Pos> queue, Extent extent, String world) {
            this.extent = extent;
            this.queue = queue;
            this.world = world;
        }

        @Override
        public <T extends BlockStateHolder<T>> boolean setBlock(BlockVector3 position, T block)
                throws WorldEditException {
            BlockState existing = extent.getBlock(position);
            boolean sign = (existing != null && isSign(existing)) || isSign(block);
            boolean accepted = extent.setBlock(position, block);
            if (accepted && sign) {
                changed.add(new Pos(world, position.x(), position.y(), position.z()));
            }
            return accepted;
        }

        private static boolean isSign(BlockStateHolder<?> block) {
            return block.getBlockType().id().endsWith("_sign");
        }

        // Composition is intentional: ordinary WorldEdit makes AbstractDelegateExtent.commit final.
        @Override
        public BlockVector3 getMinimumPoint() {
            return extent.getMinimumPoint();
        }

        @Override
        public BlockVector3 getMaximumPoint() {
            return extent.getMaximumPoint();
        }

        @Override
        public List<? extends Entity> getEntities(Region region) {
            return extent.getEntities(region);
        }

        @Override
        public List<? extends Entity> getEntities() {
            return extent.getEntities();
        }

        @Override
        public Entity createEntity(Location location, BaseEntity entity) {
            return extent.createEntity(location, entity);
        }

        @Override
        public BlockState getBlock(BlockVector3 position) {
            return extent.getBlock(position);
        }

        @Override
        public BaseBlock getFullBlock(BlockVector3 position) {
            return extent.getFullBlock(position);
        }

        @Override
        public BiomeType getBiome(BlockVector3 position) {
            return extent.getBiome(position);
        }

        @Override
        public boolean setBiome(BlockVector3 position, BiomeType biome) {
            return extent.setBiome(position, biome);
        }

        @Override
        public boolean fullySupports3DBiomes() {
            return extent.fullySupports3DBiomes();
        }

        @Override
        public Operation commit() {
            Operation delegate = extent.commit();
            Operation publish = new Operation() {
                @Override
                public Operation resume(RunContext context) {
                    queue.addAll(changed);
                    changed.clear();
                    return null;
                }

                @Override
                public void cancel() {
                    changed.clear();
                }
            };
            return delegate == null ? publish : new OperationQueue(delegate, publish);
        }
    }
}
