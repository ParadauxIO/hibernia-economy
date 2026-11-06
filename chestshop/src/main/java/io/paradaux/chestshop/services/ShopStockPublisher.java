package io.paradaux.chestshop.services;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.Location;
import org.bukkit.block.Sign;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Coalesces stock observations until the next tick, then submits them to the same ordered
 * registry writer as trades and discovery. All bookkeeping is main-thread-only. In particular,
 * a delayed hopper task cannot submit an old reading after a newer trade or deletion.
 */
@Singleton
@Slf4j
public final class ShopStockPublisher {
    private record ChunkKey(String world, int x, int z) {}

    private record Key(String world, int x, int y, int z) {
        ChunkKey chunk() {
            return new ChunkKey(world, x >> 4, z >> 4);
        }
    }

    private record Stock(Integer stock, Integer capacity) {}

    private final JavaPlugin plugin;
    private final MarketService market;
    private final ItemService items;
    private final Map<ChunkKey, Map<Key, Stock>> published = new HashMap<>();
    private final Map<Key, Stock> queued = new LinkedHashMap<>();
    private boolean scheduled;

    @Inject
    public ShopStockPublisher(JavaPlugin plugin, MarketService market, ItemService items) {
        this.plugin = plugin;
        this.market = market;
        this.items = items;
    }

    public boolean enabled() {
        return market.enabled();
    }

    public void publish(Sign sign, Inventory inventory) {
        if (!enabled()) return;
        String code = SignService.getItem(sign);
        if (WildcardItems.isPattern(code)) return;
        ItemStack item = items.parse(code);
        if (item == null) return;
        Key key = key(sign.getLocation());
        if (key == null) return;
        Stock value = new Stock(market.stockOf(item, inventory), market.capacityOf(item, inventory));
        Map<Key, Stock> chunk = published.computeIfAbsent(key.chunk(), ignored -> new HashMap<>());
        if (value.equals(chunk.put(key, value))) return;
        queued.put(key, value);
        if (!scheduled) {
            scheduled = true;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                scheduled = false;
                drain();
            });
        }
    }

    /** A newer authoritative observation has already been submitted to the ordered writer. */
    public void recorded(Location sign, Integer stock, Integer capacity) {
        Key key = key(sign);
        if (key == null) return;
        queued.remove(key);
        published.computeIfAbsent(key.chunk(), ignored -> new HashMap<>()).put(key, new Stock(stock, capacity));
    }

    public void forget(Location sign) {
        Key key = key(sign);
        if (key != null) forget(key);
    }

    public void forget(String world, int x, int y, int z) {
        forget(new Key(world, x, y, z));
    }

    private void forget(Key key) {
        queued.remove(key);
        Map<Key, Stock> chunk = published.get(key.chunk());
        if (chunk != null) {
            chunk.remove(key);
            if (chunk.isEmpty()) published.remove(key.chunk());
        }
    }

    /** O(1): dropping a chunk no longer visits unrelated shops. Queued observations remain valid. */
    public void unloadChunk(String world, int chunkX, int chunkZ) {
        published.remove(new ChunkKey(world, chunkX, chunkZ));
    }

    public void unloadWorld(String world) {
        published.keySet().removeIf(chunk -> chunk.world().equals(world));
    }

    /** Submit all pending observations before MarketService.close drains the writer at shutdown. */
    public void flush() {
        drain();
        published.clear();
    }

    private void drain() {
        var api = market.market();
        if (api != null) {
            queued.forEach((key, value) -> {
                try {
                    api.updateShopStock(key.world(), key.x(), key.y(), key.z(), value.stock(), value.capacity());
                } catch (RuntimeException e) {
                    log.debug("hopper stock enqueue failed (non-fatal)", e);
                    Map<Key, Stock> chunk = published.get(key.chunk());
                    if (chunk != null) chunk.remove(key);
                }
            });
        }
        queued.clear();
    }

    private static Key key(Location location) {
        if (location.getWorld() == null) return null;
        return new Key(location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }
}
