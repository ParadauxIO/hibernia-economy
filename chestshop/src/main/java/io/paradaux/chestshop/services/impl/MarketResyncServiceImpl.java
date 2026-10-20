package io.paradaux.chestshop.services.impl;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.chestshop.services.MarketResyncService;
import io.paradaux.chestshop.services.MarketService;
import io.paradaux.chestshop.services.MarketSyncService;
import io.paradaux.chestshop.services.PreviewService;
import io.paradaux.chestshop.services.SignService;
import io.paradaux.hibernia.framework.i18n.Message;
import io.paradaux.treasury.api.market.ShopLocation;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Rebuilds the live shop registry by scanning the world: for each scanned chunk
 * it upserts every valid ChestShop sign (discovery + stock/capacity refresh) and
 * deactivates any previously-registered shop in that chunk whose sign is gone.
 * The {@code /find resync} maintenance command drives it (PAR-169/174).
 *
 * <p>Pragmatic for the single-host envelope: it scans only currently-loaded
 * chunks (no forced chunk loading) plus the chunks of known registry shops that
 * happen to be loaded, rate-limited to {@code chunksPerTick}. Chunk access and the
 * registry writes run on the main thread; the only off-thread step is the initial
 * enumeration of known shop locations. Vanished shops in unloaded chunks are left
 * untouched until a later scan reaches them.
 */
@Singleton
@Slf4j
public class MarketResyncServiceImpl implements MarketResyncService {

    /** Upper bound on chunks scanned per tick: the scan and its registry writes run on the main thread. */
    static final int MAX_CHUNKS_PER_TICK = 64;

    private final JavaPlugin plugin;
    private final MarketService marketService;
    private final SignService signService;
    private final MarketSyncService marketSync;
    private final PreviewService previews;
    private final Message message;

    private record ChunkKey(UUID worldUuid, int x, int z) {}

    // Main-thread queue; one registry lookup/scan in flight, with a tick between chunks.
    private final Map<ChunkKey, Chunk> discovery = new LinkedHashMap<>();
    private boolean discoveryInFlight;
    private boolean discoveryTimerRunning;

    private record DiscoveryScan(ChunkKey key, Chunk chunk, String worldName, Map<String, Set<String>> known) {}

    private volatile DiscoveryScan completedDiscovery;

    private final AtomicBoolean running = new AtomicBoolean(false);

    @Inject
    public MarketResyncServiceImpl(
            JavaPlugin plugin,
            MarketService marketService,
            SignService signService,
            MarketSyncService marketSync,
            PreviewService previews,
            Message message) {
        this.plugin = plugin;
        this.marketService = marketService;
        this.signService = signService;
        this.marketSync = marketSync;
        this.previews = previews;
        this.message = message;
    }

    @Override
    public void discoverLoadedChunks() {
        if (!marketService.enabled() || !marketService.searchEnabled()) return;
        for (World world : plugin.getServer().getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) discoverChunk(chunk);
        }
    }

    @Override
    public void discoverChunk(Chunk chunk) {
        if (!marketService.enabled() || !marketService.searchEnabled()) return;
        discovery.putIfAbsent(new ChunkKey(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ()), chunk);
        if (!discoveryTimerRunning) {
            discoveryTimerRunning = true;
            new BukkitRunnable() {
                @Override
                public void run() {
                    DiscoveryScan completed = completedDiscovery;
                    if (completed != null) {
                        completedDiscovery = null;
                        try {
                            World current = plugin.getServer().getWorld(completed.worldName());
                            if (completed.known() != null
                                    && current != null
                                    && completed.key().worldUuid().equals(current.getUID())
                                    && completed.chunk().isLoaded()) scanChunk(completed.chunk(), completed.known());
                        } finally {
                            discovery.remove(completed.key());
                            discoveryInFlight = false;
                        }
                        // Leave a full tick between background jobs. Do not chain one-shot
                        // continuations that make unrelated command barriers permanently busy.
                    } else if (!discoveryInFlight && !discovery.isEmpty()) {
                        pumpDiscovery();
                    }
                    if (!discoveryInFlight && discovery.isEmpty()) {
                        discoveryTimerRunning = false;
                        cancel();
                    }
                }
            }.runTaskTimer(plugin, 1L, 1L);
        }
    }

    private void pumpDiscovery() {
        if (discovery.isEmpty()) return;
        discoveryInFlight = true;
        var entry = discovery.entrySet().iterator().next();
        ChunkKey key = entry.getKey();
        Chunk chunk = entry.getValue();
        // Keep the in-flight key queued so duplicate load events cannot enqueue it again.
        String worldName = chunk.getWorld().getName();
        var scheduler = plugin.getServer().getScheduler();
        scheduler.runTaskAsynchronously(plugin, () -> {
            Map<String, Set<String>> known = null;
            try {
                known = groupKnownByChunk(marketService.shopQuery().shopsInChunk(worldName, key.x(), key.z()).stream()
                        .map(s -> new ShopLocation(s.world(), s.worldUuid(), s.signX(), s.signY(), s.signZ()))
                        .toList());
            } catch (RuntimeException e) {
                log.warn("Could not reconcile loaded shop chunk {} ({}, {})", worldName, key.x(), key.z(), e);
            }
            completedDiscovery = new DiscoveryScan(key, chunk, worldName, known);
        });
    }

    @Override
    public void resync(CommandSender initiator, int chunksPerTick) {
        if (!marketService.enabled() || !marketService.searchEnabled()) {
            message.send(initiator, "find.no-search");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            message.send(initiator, "chestshop.resync.busy");
            return;
        }
        int perTick = Math.min(MAX_CHUNKS_PER_TICK, Math.max(1, chunksPerTick));
        message.send(initiator, "chestshop.resync.queued");
        // The only off-main step: enumerate known shop locations, then hand back to the main thread.
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Map<String, Set<String>> knownByChunk;
            try {
                knownByChunk = groupKnownByChunk(marketService.shopQuery().activeShopLocations(null));
            } catch (RuntimeException e) {
                running.set(false);
                plugin.getServer()
                        .getScheduler()
                        .runTask(plugin, () -> message.send(initiator, "chestshop.resync.failed"));
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> startScan(initiator, perTick, knownByChunk));
        });
    }

    private void startScan(CommandSender initiator, int perTick, Map<String, Set<String>> knownByChunk) {
        Deque<Chunk> queue = new ArrayDeque<>();
        for (World world : plugin.getServer().getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                queue.add(chunk);
            }
        }
        int total = queue.size();
        message.send(initiator, "chestshop.resync.started", "chunks", total);

        new BukkitRunnable() {
            int processed = 0;
            int upserted = 0;
            int deactivated = 0;

            @Override
            public void run() {
                for (int i = 0; i < perTick && !queue.isEmpty(); i++) {
                    Chunk chunk = queue.poll();
                    if (chunk.isLoaded()) {
                        int[] counts = scanChunk(chunk, knownByChunk);
                        upserted += counts[0];
                        deactivated += counts[1];
                    }
                    processed++;
                }
                if (queue.isEmpty()) {
                    cancel();
                    running.set(false);
                    message.send(
                            initiator, "chestshop.resync.complete", "upserted", upserted, "deactivated", deactivated);
                } else if (total > 0 && processed % (perTick * 200) == 0) {
                    message.send(initiator, "chestshop.resync.progress", "done", processed, "total", total);
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /** Scan one loaded chunk; returns {upserts, deactivations}. */
    private int[] scanChunk(Chunk chunk, Map<String, Set<String>> knownByChunk) {
        String world = chunk.getWorld().getName();
        Set<String> seen = new HashSet<>();
        int upserted = 0;
        for (BlockState state : chunk.getTileEntities(false)) {
            if (state instanceof Sign sign && signService.isValid(sign)) {
                // The sign is physically there, so its row is never a deactivation candidate —
                // even when the row can't be rebuilt right now (item/owner not resolvable).
                seen.add(posKey(sign.getX(), sign.getY(), sign.getZ()));
                if (marketSync.registerShop(sign)) {
                    upserted++;
                }
            }
        }
        int deactivated = 0;
        Set<String> known = knownByChunk.get(chunkKey(world, chunk.getX(), chunk.getZ()));
        if (known != null) {
            for (String pos : known) {
                if (!seen.contains(pos)) {
                    int[] xyz = parsePos(pos);
                    marketService.market().deactivateShop(world, xyz[0], xyz[1], xyz[2]);
                    previews.destroy(world, xyz[0], xyz[1], xyz[2]);
                    deactivated++;
                }
            }
        }
        if (upserted > 0) {
            previews.loadChunk(world, chunk.getX(), chunk.getZ()); // previews for newly discovered shops
        }
        return new int[] {upserted, deactivated};
    }

    private static Map<String, Set<String>> groupKnownByChunk(List<ShopLocation> locations) {
        Map<String, Set<String>> byChunk = new HashMap<>();
        for (ShopLocation l : locations) {
            String key = chunkKey(l.world(), l.signX() >> 4, l.signZ() >> 4);
            byChunk.computeIfAbsent(key, k -> new HashSet<>()).add(posKey(l.signX(), l.signY(), l.signZ()));
        }
        return byChunk;
    }

    private static String chunkKey(String world, int chunkX, int chunkZ) {
        return world + ':' + chunkX + ':' + chunkZ;
    }

    private static String posKey(int x, int y, int z) {
        return x + ":" + y + ":" + z;
    }

    private static int[] parsePos(String pos) {
        String[] parts = pos.split(":");
        return new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
    }
}
