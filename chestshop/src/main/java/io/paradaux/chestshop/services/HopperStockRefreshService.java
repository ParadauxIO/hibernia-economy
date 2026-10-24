package io.paradaux.chestshop.services;

import static io.paradaux.chestshop.utils.InventoryUtil.getHolder;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
    private BukkitTask task;
    private long tick;

    @Inject
    public HopperStockRefreshService(
            JavaPlugin plugin,
            ChestShopConfiguration config,
            ShopBlockService blocks,
            SignService signs,
            StockCounterService counters) {
        this(plugin, config, blocks, signs, counters, System::nanoTime);
    }

    HopperStockRefreshService(
            JavaPlugin plugin,
            ChestShopConfiguration config,
            ShopBlockService blocks,
            SignService signs,
            StockCounterService counters,
            LongSupplier nanoTime) {
        this.plugin = plugin;
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
    }

    private void clear() {
        pending.clear();
        reconciliation.clear();
        recovery.clear();
    }

    /** No sign search, inventory scan, database call or task allocation on the transfer path. */
    public void markDirty(Inventory inventory) {
        if (!enabled()) return;
        enqueue(getHolder(inventory, false));
    }

    private boolean enabled() {
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
            Position pos = position(container);
            if (pending.containsKey(pos)) return;
            if (pending.size() < MAX_PENDING) {
                pending.put(pos, tick);
            } else {
                reconcileChunk(pos.world(), pos.x() >> 4, pos.z() >> 4);
            }
        }
    }

    public void reconcileChunk(UUID world, int x, int z) {
        if (enabled()) reconciliation.putIfAbsent(new ChunkKey(world, x, z), tick);
    }

    public void unloadChunk(UUID world, int x, int z) {
        ChunkKey key = new ChunkKey(world, x, z);
        pending.keySet().removeIf(pos -> pos.chunk().equals(key));
        reconciliation.remove(key);
        recovery.removeIf(pos -> pos.chunk().equals(key));
    }

    public void unloadWorld(UUID world) {
        pending.keySet().removeIf(pos -> pos.world().equals(world));
        reconciliation.keySet().removeIf(key -> key.world().equals(world));
        recovery.removeIf(pos -> pos.world().equals(world));
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
        for (Sign sign : blocks.findConnectedShopSigns(getHolder(inventory, false))) {
            if (config.isForceUnlimitedAdminShop() && signs.isAdminShop(sign)) continue;
            if (refreshedSigns.add(position(sign))) {
                counters.updateCounterOnQuantityLine(sign, inventory);
            }
        }
    }

    private static Position position(BlockState state) {
        return new Position(state.getWorld().getUID(), state.getX(), state.getY(), state.getZ());
    }
}
