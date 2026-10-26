package io.paradaux.chestshop.services;

import static io.paradaux.chestshop.utils.InventoryUtil.getHolder;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.Sign;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Main-thread, coalesced stock refresh. Only coordinates cross the event/task boundary:
 * inventory holders and signs are resolved afresh after the transfer has completed.
 *
 * <p>The lazy index is the pending container map, not a cached assertion that a block is
 * (or is not) a shop. This also handles signs pasted by editors without stale membership
 * caches. Ordinary sorter containers pay for discovery once per batch, not per item.
 * Protection deliberately remains a separate, immediate live-world check.
 *
 * <p>Each refresh also hands the shop's measured stock to {@link ShopStockPublisher}, so
 * {@code /find} follows hopper transfers too (PAR-544). Work dropped when a chunk unloads is kept
 * and re-queued when that chunk loads; chunk loads alone queue nothing.
 */
@Singleton
public final class HopperStockRefreshService {
    static final int MAX_PENDING = 4096;
    static final int MAX_PER_TICK = 32;
    private static final long PHASE_BUDGET_NANOS = 500_000;

    record ChunkKey(UUID world, int x, int z) {}

    record Position(UUID world, int x, int y, int z) {
        ChunkKey chunk() {
            return new ChunkKey(world, x >> 4, z >> 4);
        }
    }

    private final JavaPlugin plugin;
    private final ChestShopConfiguration config;
    private final ShopBlockService blocks;
    private final SignService signs;
    private final StockCounterService counters;
    private final LongSupplier nanoTime;
    // Store first-dirty ticks, so changing the interval on reload preserves FIFO order.
    private final Map<Position, Long> pending = new LinkedHashMap<>();
    // Overflow is represented by loaded chunk coordinates, never by more item events.
    private final Map<ChunkKey, Long> reconciliation = new LinkedHashMap<>();
    private final ArrayDeque<Position> recovery = new ArrayDeque<>();
    // Work dropped at chunk unload, restored on that chunk's next load. Positions are capped at
    // MAX_PENDING; past that, or for overflow reconciliation, the whole chunk is recounted instead.
    private final Map<ChunkKey, Set<Position>> deferred = new HashMap<>();
    private final Set<ChunkKey> deferredChunks = new LinkedHashSet<>();
    private int deferredPositions;
    private final ShopStockPublisher stock;
    private BukkitTask task;
    private long tick;

    @Inject
    public HopperStockRefreshService(
            JavaPlugin plugin,
            ChestShopConfiguration config,
            ShopBlockService blocks,
            SignService signs,
            StockCounterService counters,
            ShopStockPublisher stock) {
        this(plugin, config, blocks, signs, counters, stock, System::nanoTime);
    }

    HopperStockRefreshService(
            JavaPlugin plugin,
            ChestShopConfiguration config,
            ShopBlockService blocks,
            SignService signs,
            StockCounterService counters,
            ShopStockPublisher stock,
            LongSupplier nanoTime) {
        this.plugin = plugin;
        this.stock = stock;
        this.config = config;
        this.blocks = blocks;
        this.signs = signs;
        this.counters = counters;
        this.nanoTime = nanoTime;
    }

    public void start() {
        if (task == null) {
            task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::drain, 1L, 1L);
        }
    }

    public void stop() {
        if (task != null) task.cancel();
        task = null;
        clear();
        stock.flush();
    }

    private void clear() {
        pending.clear();
        reconciliation.clear();
        recovery.clear();
        deferred.clear();
        deferredChunks.clear();
        deferredPositions = 0;
    }

    /** No sign search, inventory scan, database call or task allocation on the transfer path. */
    public void markDirty(Inventory inventory) {
        if (!enabled()) return;
        enqueue(getHolder(inventory, false));
    }

    private boolean enabled() {
        return countersEnabled() || stock.enabled();
    }

    private boolean countersEnabled() {
        return config.isUseStockCounter() && config.getMaxShopAmount() <= 99999;
    }

    private int refreshIntervalTicks() {
        return Math.max(1, config.getHopperStockRefreshIntervalTicks());
    }

    private void enqueue(InventoryHolder holder) {
        if (holder instanceof DoubleChest chest) {
            // Keep both coordinates: a chest may split or lose one half before the drain.
            enqueue(chest.getLeftSide(false));
            enqueue(chest.getRightSide(false));
        } else if (holder instanceof Container container) {
            enqueue(position(container));
        }
    }

    private void enqueue(Position pos) {
        if (pending.containsKey(pos)) return;
        if (pending.size() < MAX_PENDING) {
            pending.put(pos, tick);
        } else {
            reconcileChunk(pos.world(), pos.x() >> 4, pos.z() >> 4);
        }
    }

    public void reconcileChunk(UUID world, int x, int z) {
        if (enabled()) reconciliation.putIfAbsent(new ChunkKey(world, x, z), tick);
    }

    /** Re-queue only what this chunk's unload dropped, so exploring players add no work. */
    public void loadChunk(UUID world, int x, int z) {
        ChunkKey key = new ChunkKey(world, x, z);
        Set<Position> positions = deferred.remove(key);
        if (positions != null) deferredPositions -= positions.size();
        if (!enabled()) return;
        if (deferredChunks.remove(key)) reconcileChunk(world, x, z);
        if (positions != null) positions.forEach(this::enqueue);
    }

    public void unloadChunk(UUID world, int x, int z) {
        World loaded = plugin.getServer().getWorld(world);
        if (loaded != null) stock.unloadChunk(loaded.getName(), x, z);
        ChunkKey key = new ChunkKey(world, x, z);
        Set<Position> dropped = new LinkedHashSet<>();
        pending.keySet().removeIf(pos -> pos.chunk().equals(key) && dropped.add(pos));
        recovery.removeIf(pos -> pos.chunk().equals(key) && dropped.add(pos));
        boolean reconcile = reconciliation.remove(key) != null;
        if (dropped.isEmpty() && !reconcile) return;
        if (reconcile || deferredPositions + dropped.size() > MAX_PENDING) {
            // Recount the whole chunk on load. Past the cap the update is lost until the next change.
            if (deferredChunks.size() < MAX_PENDING) deferredChunks.add(key);
        } else {
            Set<Position> kept = deferred.computeIfAbsent(key, ignored -> new LinkedHashSet<>());
            int before = kept.size();
            kept.addAll(dropped);
            deferredPositions += kept.size() - before;
        }
    }

    public void unloadWorld(UUID world) {
        World loaded = plugin.getServer().getWorld(world);
        if (loaded != null) stock.unloadWorld(loaded.getName());
        pending.keySet().removeIf(pos -> pos.world().equals(world));
        reconciliation.keySet().removeIf(key -> key.world().equals(world));
        recovery.removeIf(pos -> pos.world().equals(world));
        deferred.entrySet().removeIf(entry -> {
            if (!entry.getKey().world().equals(world)) return false;
            deferredPositions -= entry.getValue().size();
            return true;
        });
        deferredChunks.removeIf(key -> key.world().equals(world));
    }

    void drain() {
        tick++;
        if (!enabled()) {
            clear();
            return;
        }
        if (pending.isEmpty() && reconciliation.isEmpty() && recovery.isEmpty()) return;
        Set<Position> refreshedSigns = new HashSet<>();
        long start = nanoTime.getAsLong();
        for (int i = 0; i < MAX_PER_TICK && !pending.isEmpty(); i++) {
            var iterator = pending.entrySet().iterator();
            var entry = iterator.next();
            if (entry.getValue() + refreshIntervalTicks() > tick) break;
            Position pos = entry.getKey();
            iterator.remove();
            refresh(pos, refreshedSigns);
            if (nanoTime.getAsLong() - start >= PHASE_BUDGET_NANOS) break;
        }
        // Reserve a separate allowance so a busy normal queue cannot starve overflow recovery.
        start = nanoTime.getAsLong();
        if (recovery.isEmpty()) beginRecovery();
        for (int i = 0; i < MAX_PER_TICK && !recovery.isEmpty(); i++) {
            refresh(recovery.removeFirst(), refreshedSigns);
            if (nanoTime.getAsLong() - start >= PHASE_BUDGET_NANOS) break;
        }
    }

    private void beginRecovery() {
        if (reconciliation.isEmpty()) return;
        var iterator = reconciliation.entrySet().iterator();
        var entry = iterator.next();
        if (entry.getValue() + refreshIntervalTicks() > tick) return;
        ChunkKey key = entry.getKey();
        iterator.remove();
        World world = plugin.getServer().getWorld(key.world());
        if (world == null || !world.isChunkLoaded(key.x(), key.z())) return;
        // One chunk enumeration per tick at most. The API call itself cannot be preempted;
        // the time budget is soft. Retain only coordinates, then pace the inventory work.
        for (BlockState state : world.getChunkAt(key.x(), key.z()).getTileEntities(false)) {
            if (state instanceof Container) recovery.add(position(state));
        }
    }

    private void refresh(Position pos, Set<Position> refreshedSigns) {
        World world = plugin.getServer().getWorld(pos.world());
        if (world == null || !world.isChunkLoaded(pos.x() >> 4, pos.z() >> 4)) return;
        var block = world.getBlockAt(pos.x(), pos.y(), pos.z());
        if (!blocks.couldBeShopContainer(block) || !(block.getState(false) instanceof Container container)) return;
        Inventory inventory = container.getInventory();
        boolean counting = countersEnabled();
        for (Sign sign : blocks.findConnectedShopSigns(getHolder(inventory, false))) {
            boolean admin = signs.isAdminShop(sign);
            if (config.isForceUnlimitedAdminShop() && admin) continue;
            if (refreshedSigns.add(position(sign))) {
                if (counting) counters.updateCounterOnQuantityLine(sign, inventory);
                // The registry records no stock for admin shops.
                if (!admin) stock.publish(sign, inventory);
            }
        }
    }

    private static Position position(BlockState state) {
        return new Position(state.getWorld().getUID(), state.getX(), state.getY(), state.getZ());
    }
}
