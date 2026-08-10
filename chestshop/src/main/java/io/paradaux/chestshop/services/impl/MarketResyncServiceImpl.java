package io.paradaux.chestshop.services.impl;
import lombok.extern.slf4j.Slf4j;

import io.paradaux.chestshop.services.ShopBlockService;
import io.paradaux.chestshop.services.MarketService;
import io.paradaux.chestshop.services.ItemCodeService;
import io.paradaux.chestshop.services.SignService;
import io.paradaux.chestshop.services.AccountService;
import io.paradaux.chestshop.services.MarketResyncService;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.chestshop.model.Account;
import io.paradaux.hibernia.framework.i18n.Message;
import io.paradaux.treasury.api.market.ShopLocation;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

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

    private final JavaPlugin plugin;
    private final MarketService marketService;
    private final SignService signService;
    private final ShopBlockService shopBlockService;
    private final ItemCodeService itemCodes;
    private final AccountService accounts;
    private final Message message;

    private final AtomicBoolean running = new AtomicBoolean(false);

    @Inject
    public MarketResyncServiceImpl(JavaPlugin plugin, MarketService marketService, SignService signService,
                               ShopBlockService shopBlockService, ItemCodeService itemCodes,
                               AccountService accounts, Message message) {
        this.plugin = plugin;
        this.marketService = marketService;
        this.signService = signService;
        this.shopBlockService = shopBlockService;
        this.itemCodes = itemCodes;
        this.accounts = accounts;
        this.message = message;
    }

    @Override
    public void resync(Player initiator, int chunksPerTick) {
        if (!marketService.enabled() || !marketService.searchEnabled()) {
            message.send(initiator, "find.no-search");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            message.send(initiator, "chestshop.resync.busy");
            return;
        }
        int perTick = Math.max(1, chunksPerTick);
        message.send(initiator, "chestshop.resync.queued");
        // The only off-main step: enumerate known shop locations, then hand back to the main thread.
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Map<String, Set<String>> knownByChunk;
            try {
                knownByChunk = groupKnownByChunk(marketService.shopQuery().activeShopLocations(null));
            } catch (RuntimeException e) {
                running.set(false);
                plugin.getServer().getScheduler().runTask(plugin,
                        () -> message.send(initiator, "chestshop.resync.failed"));
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin,
                    () -> startScan(initiator, perTick, knownByChunk));
        });
    }

    private void startScan(Player initiator, int perTick, Map<String, Set<String>> knownByChunk) {
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
                    message.send(initiator, "chestshop.resync.complete",
                            "upserted", upserted, "deactivated", deactivated);
                } else if (total > 0 && processed % (perTick * 200) == 0) {
                    message.send(initiator, "chestshop.resync.progress",
                            "done", processed, "total", total);
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
                if (upsertSign(sign)) {
                    upserted++;
                    seen.add(posKey(sign.getX(), sign.getY(), sign.getZ()));
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
                    deactivated++;
                }
            }
        }
        return new int[]{upserted, deactivated};
    }

    /** Build and upsert a registry row from a live sign. False if it can't be resolved. */
    private boolean upsertSign(Sign sign) {
        try {
            String itemCode = SignService.getItem(sign);
            ItemStack item = itemCode != null ? itemCodes.decode(itemCode) : null;
            if (item == null) {
                return false;
            }
            boolean admin = signService.isAdminShop(sign);
            MarketService.Owner owner;
            if (admin) {
                owner = marketService.ownerFromUuid(null, true);
            } else {
                Account account = accounts.resolveAccount(SignService.getOwner(sign));
                if (account == null || account.getUuid() == null) {
                    return false; // owner not resolvable — skip rather than write a half row
                }
                owner = marketService.ownerFromUuid(account.getUuid(), false);
            }
            Container container = admin ? null : shopBlockService.findConnectedContainer(sign);
            Integer stock = container != null ? marketService.stockOf(item, container.getInventory()) : null;
            Integer capacity = container != null ? marketService.capacityOf(item, container.getInventory()) : null;
            marketService.market().upsertShop(marketService.shop(sign, item, owner, stock, capacity));
            return true;
        } catch (RuntimeException e) {
            log.debug("resync: skipped a sign", e);
            return false;
        }
    }

    private static Map<String, Set<String>> groupKnownByChunk(List<ShopLocation> locations) {
        Map<String, Set<String>> byChunk = new HashMap<>();
        for (ShopLocation l : locations) {
            String key = chunkKey(l.world(), l.signX() >> 4, l.signZ() >> 4);
            byChunk.computeIfAbsent(key, k -> new HashSet<>())
                    .add(posKey(l.signX(), l.signY(), l.signZ()));
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
        return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
    }
}
