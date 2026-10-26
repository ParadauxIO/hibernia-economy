package io.paradaux.chestshop.services;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.Location;
import org.bukkit.block.Sign;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Keeps {@code /find} stock current for changes no trade or chest close records: the hopper
 * refresh worker measures a shop on the main thread and this writes it to the registry
 * (PAR-544). Unchanged readings write nothing. Changed ones are coalesced per sign and written in
 * order by a single in-flight async batch, so the database never runs on the server thread.
 *
 * <p>Synchronous registry writes elsewhere ({@link MarketSyncService}) report through
 * {@link #recorded}/{@link #forget}. That keeps the "already written" baseline honest and stops
 * an in-flight batch from overwriting a newer trade or chest-close reading with an older one.
 * Main-thread only, except the async writer, which reads just {@link #stamps}.
 */
@Singleton
@Slf4j
public final class ShopStockPublisher {

    record Key(String world, int x, int y, int z) {
        boolean inChunk(String world, int chunkX, int chunkZ) {
            return this.world.equals(world) && x >> 4 == chunkX && z >> 4 == chunkZ;
        }
    }

    record Stock(Integer stock, Integer capacity) {}

    private record Write(Stock value, long stamp) {}

    private final JavaPlugin plugin;
    private final MarketService market;
    private final ItemService items;
    // Last value known to be in the registry, from any writer; dropped with its chunk.
    private final Map<Key, Stock> published = new HashMap<>();
    private final Map<Key, Write> queued = new LinkedHashMap<>();
    // The newest stamp per sign with a queued or in-flight write; the async writer skips stale ones.
    private final Map<Key, Long> stamps = new ConcurrentHashMap<>();
    private long nextStamp;
    private boolean inFlight;

    @Inject
    public ShopStockPublisher(JavaPlugin plugin, MarketService market, ItemService items) {
        this.plugin = plugin;
        this.market = market;
        this.items = items;
    }

    public boolean enabled() {
        return market.enabled();
    }

    /** Measure {@code sign}'s stock in {@code inventory} and queue it if the registry differs. */
    public void publish(Sign sign, Inventory inventory) {
        if (!enabled()) return;
        String code = SignService.getItem(sign);
        // Wildcard shops are kept out of the registry, so there is no row to update.
        if (WildcardItems.isPattern(code)) return;
        ItemStack item = items.parse(code);
        if (item == null) return;
        Key key = key(sign.getLocation());
        if (key == null) return;
        Stock value = new Stock(market.stockOf(item, inventory), market.capacityOf(item, inventory));
        if (value.equals(published.get(key))) return;
        published.put(key, value);
        long stamp = ++nextStamp;
        stamps.put(key, stamp);
        queued.put(key, new Write(value, stamp));
        pump();
    }

    /** Another writer just stored this shop's stock synchronously; it supersedes queued work. */
    public void recorded(Location sign, Integer stock, Integer capacity) {
        Key key = key(sign);
        if (key == null) return;
        queued.remove(key);
        if (stamps.containsKey(key)) stamps.put(key, ++nextStamp);
        published.put(key, new Stock(stock, capacity));
    }

    /** The shop's row was deactivated; write nothing more for it. */
    public void forget(Location sign) {
        Key key = key(sign);
        if (key != null) forget(key);
    }

    public void forget(String world, int x, int y, int z) {
        forget(new Key(world, x, y, z));
    }

    private void forget(Key key) {
        queued.remove(key);
        stamps.remove(key);
        published.remove(key);
    }

    /** Only the baseline is dropped: queued writes stay valid while the chunk is unloaded. */
    public void unloadChunk(String world, int chunkX, int chunkZ) {
        published.keySet().removeIf(key -> key.inChunk(world, chunkX, chunkZ));
    }

    public void unloadWorld(String world) {
        published.keySet().removeIf(key -> key.world().equals(world));
    }

    /**
     * Write whatever is still queued on the calling thread (plugin disable). Stamps are kept, so a
     * batch still in flight skips any reading this flush has already superseded.
     */
    public void flush() {
        Map<Key, Write> batch = new LinkedHashMap<>(queued);
        queued.clear();
        write(batch);
        published.clear();
    }

    private void pump() {
        if (inFlight || queued.isEmpty()) return;
        Map<Key, Write> batch = new LinkedHashMap<>(queued);
        queued.clear();
        inFlight = true;
        var scheduler = plugin.getServer().getScheduler();
        scheduler.runTaskAsynchronously(plugin, () -> {
            try {
                write(batch);
            } finally {
                scheduler.runTask(plugin, () -> finish(batch));
            }
        });
    }

    private void finish(Map<Key, Write> batch) {
        inFlight = false;
        // A sign queued again meanwhile keeps its newer stamp; otherwise nothing is outstanding.
        for (Key key : batch.keySet()) {
            if (!queued.containsKey(key)) stamps.remove(key);
        }
        pump();
    }

    private void write(Map<Key, Write> batch) {
        var api = market.market();
        if (api == null) return;
        batch.forEach((key, write) -> {
            Long current = stamps.get(key);
            if (current == null || current != write.stamp()) return; // superseded or forgotten
            try {
                api.updateShopStock(
                        key.world(),
                        key.x(),
                        key.y(),
                        key.z(),
                        write.value().stock(),
                        write.value().capacity());
            } catch (RuntimeException e) {
                log.debug("hopper stock write failed (non-fatal)", e);
            }
        });
    }

    private static Key key(Location location) {
        // A sign whose world has been unloaded has no registry identity left to update.
        if (location.getWorld() == null) return null;
        return new Key(location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }
}
