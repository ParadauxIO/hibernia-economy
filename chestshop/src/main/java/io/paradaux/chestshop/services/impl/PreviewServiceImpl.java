package io.paradaux.chestshop.services.impl;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.chestshop.model.ShopItem;
import io.paradaux.chestshop.services.MarketService;
import io.paradaux.chestshop.services.PreviewService;
import io.paradaux.chestshop.services.ShopDisplayService;
import io.paradaux.treasury.api.market.ShopResult;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;

/**
 * Floating item previews above shop signs — a port of the legacy chestshop-database
 * holograms. One non-persistent {@link ItemDisplay} per shop, a block above the
 * sign, indexed by chunk so the {@code PreviewListener} can load/unload them with
 * the chunk. Per-player visibility is honoured with {@code hideEntity}: a player
 * who turns previews off has every display hidden for them only.
 *
 * <p>Registry lookups go through Treasury's shared connection pool, so they are funnelled
 * through one in-flight async task at a time: a burst of chunk loads queues up behind it
 * (and is answered as one batch) instead of opening a connection per chunk.
 */
@Singleton
public class PreviewServiceImpl implements PreviewService {

    private final io.paradaux.chestshop.services.FindPresentation presentation;

    /** One queued registry lookup: a whole chunk, or (when {@code pos} is set) one sign in it. */
    private record Lookup(String world, UUID worldUuid, int chunkX, int chunkZ, String pos) {}

    private final JavaPlugin plugin;
    private final MarketService marketService;
    private final ShopDisplayService displays;

    // chunkKey -> (posKey -> display)
    private final Map<String, Map<String, TrackedPreview>> byChunk = new ConcurrentHashMap<>();

    // Main-thread-only round robin. At 20 TPS each preview is checked once per 25 seconds.
    // Above 50,000 previews the hard cap extends the cycle rather than stalling a tick.
    private static final int CLEANUP_CYCLE_TICKS = 500;
    private static final int MAX_CHECKS_PER_TICK = 100;
    private final LinkedHashSet<TrackedPreview> validationQueue = new LinkedHashSet<>();
    private long validationCredit;

    private record TrackedPreview(ItemDisplay display, World world, int x, int y, int z, List<String> lines) {
        boolean current() {
            if (!world.isChunkLoaded(x >> 4, z >> 4)) return false;
            return world.getBlockAt(x, y, z).getState() instanceof org.bukkit.block.Sign sign
                    && lines.equals(java.util.Arrays.asList(sign.getLines()));
        }
    }

    private org.bukkit.scheduler.BukkitTask cleanupTask;

    /** Sign editors can bypass Bukkit sign events, so reconcile tracked previews with live blocks. */
    void pruneInvalid() {
        if (validationQueue.isEmpty()) {
            validationCredit = 0;
            return;
        }
        validationCredit += validationQueue.size();
        int checks = (int) Math.min(MAX_CHECKS_PER_TICK, validationCredit / CLEANUP_CYCLE_TICKS);
        validationCredit %= CLEANUP_CYCLE_TICKS;
        for (int i = 0; i < checks; i++) {
            TrackedPreview preview = validationQueue.removeFirst();
            if (preview.current()) {
                validationQueue.add(preview);
            } else {
                destroy(preview.world().getName(), preview.x(), preview.y(), preview.z());
            }
        }
    }

    // players who have previews turned off (hidden for them only)
    private final Set<UUID> previewHidden = ConcurrentHashMap.newKeySet();
    // lookups waiting on the in-flight query; both are touched on the main thread only
    private final Deque<Lookup> pending = new ArrayDeque<>();
    private boolean lookupInFlight;

    public PreviewServiceImpl(JavaPlugin plugin, MarketService marketService, ShopDisplayService displays) {
        this(plugin, marketService, displays, new io.paradaux.chestshop.services.FindPresentation());
    }

    @Inject
    public PreviewServiceImpl(
            JavaPlugin plugin,
            MarketService marketService,
            ShopDisplayService displays,
            io.paradaux.chestshop.services.FindPresentation presentation) {
        this.presentation = presentation;
        this.displays = displays;
        this.plugin = plugin;
        this.marketService = marketService;
    }

    @Override
    public void render(ShopResult shop) {
        World world = plugin.getServer().getWorld(shop.world());
        if (!shop.hologram()
                || world == null
                || (shop.worldUuid() != null && !shop.worldUuid().equals(world.getUID()))) {
            return;
        }
        ItemStack item = toItemStack(shop);
        if (item != null) {
            render(shop.world(), shop.signX(), shop.signY(), shop.signZ(), item);
        }
    }

    @Override
    public void render(String worldName, int x, int y, int z, ItemStack item) {
        World world = plugin.getServer().getWorld(worldName);
        if (world == null) {
            return;
        }
        destroy(worldName, x, y, z); // replace any existing
        if (!world.isChunkLoaded(x >> 4, z >> 4)
                || !(world.getBlockAt(x, y, z).getState() instanceof org.bukkit.block.Sign sign)
                || io.paradaux.chestshop.services.WildcardItems.isPattern(
                        io.paradaux.chestshop.services.SignService.getItem(sign))) return;
        List<String> lines = List.copyOf(java.util.Arrays.asList(sign.getLines()));
        if (cleanupTask == null) {
            cleanupTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::pruneInvalid, 1L, 1L);
        }

        Location loc = new Location(world, x + 0.5, y + 1, z + 0.5);
        String name = displays.previewName(worldName, x, y, z);
        ItemDisplay display = world.spawn(loc, ItemDisplay.class, d -> {
            d.setItemStack(item);
            d.customName(net.kyori.adventure.text.Component.text(name));
            d.setCustomNameVisible(!name.isEmpty());
            d.setBillboard(Display.Billboard.CENTER);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GROUND);
            Transformation tr = d.getTransformation();
            d.setTransformation(new Transformation(
                    tr.getTranslation(),
                    tr.getLeftRotation(),
                    new Vector3f(presentation.previewScale()),
                    tr.getRightRotation()));
            d.setPersistent(false);
            d.setVisibleByDefault(true);
        });

        // Honour players who have previews turned off.
        for (UUID id : previewHidden) {
            Player p = plugin.getServer().getPlayer(id);
            if (p != null) {
                p.hideEntity(plugin, display);
            }
        }
        TrackedPreview preview = new TrackedPreview(display, world, x, y, z, lines);
        byChunk.computeIfAbsent(chunkKey(worldName, x >> 4, z >> 4), k -> new ConcurrentHashMap<>())
                .put(posKey(x, y, z), preview);
        validationQueue.add(preview);
    }

    @Override
    public void destroy(String worldName, int x, int y, int z) {
        remove(chunkKey(worldName, x >> 4, z >> 4), posKey(x, y, z));
    }

    private void remove(String chunkKey, String posKey) {
        Map<String, TrackedPreview> chunk = byChunk.get(chunkKey);
        if (chunk == null) {
            return;
        }
        TrackedPreview preview = chunk.remove(posKey);
        if (preview != null) {
            validationQueue.remove(preview);
            preview.display().remove();
        }
        if (chunk.isEmpty()) byChunk.remove(chunkKey);
    }

    @Override
    public void destroyChunk(String worldName, int chunkX, int chunkZ) {
        Map<String, TrackedPreview> chunk = byChunk.remove(chunkKey(worldName, chunkX, chunkZ));
        if (chunk != null) {
            chunk.values().forEach(preview -> {
                validationQueue.remove(preview);
                preview.display().remove();
            });
        }
    }

    @Override
    public void destroyAll() {
        forEachDisplay(ItemDisplay::remove);
        byChunk.clear();
        validationQueue.clear();
        validationCredit = 0;
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }
    }

    @Override
    public void loadChunk(String worldName, int chunkX, int chunkZ) {
        enqueue(worldName, chunkX, chunkZ, null);
    }

    @Override
    public void loadAll() {
        for (World world : plugin.getServer().getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                loadChunk(world.getName(), chunk.getX(), chunk.getZ());
            }
        }
    }

    @Override
    public void refresh(String worldName, int x, int y, int z) {
        enqueue(worldName, x >> 4, z >> 4, posKey(x, y, z));
    }

    private void enqueue(String worldName, int chunkX, int chunkZ, String pos) {
        World world = plugin.getServer().getWorld(worldName);
        if (!marketService.searchEnabled() || world == null) {
            return;
        }
        pending.add(new Lookup(worldName, world.getUID(), chunkX, chunkZ, pos));
        if (!lookupInFlight) {
            pump();
        }
    }

    /** Answer everything queued so far in one async task, then come back for what queued up meanwhile. */
    private void pump() {
        Set<Lookup> batch = new LinkedHashSet<>(pending);
        pending.clear();
        lookupInFlight = true;
        BukkitScheduler scheduler = plugin.getServer().getScheduler();
        scheduler.runTaskAsynchronously(plugin, () -> {
            Map<Lookup, List<ShopResult>> found = new LinkedHashMap<>();
            for (Lookup lookup : batch) {
                try {
                    found.put(
                            lookup,
                            marketService.shopQuery().shopsInChunk(lookup.world(), lookup.chunkX(), lookup.chunkZ()));
                } catch (RuntimeException e) {
                    // registry unreachable — leave this chunk's holograms as they are
                }
            }
            scheduler.runTask(plugin, () -> {
                found.forEach(this::apply);
                lookupInFlight = false;
                if (!pending.isEmpty()) {
                    pump();
                }
            });
        });
    }

    private void apply(Lookup lookup, List<ShopResult> shops) {
        World world = plugin.getServer().getWorld(lookup.world());
        if (world == null
                || !java.util.Objects.equals(lookup.worldUuid(), world.getUID())
                || !world.isChunkLoaded(lookup.chunkX(), lookup.chunkZ())) {
            return; // unloaded again before we got back
        }
        String chunkKey = chunkKey(lookup.world(), lookup.chunkX(), lookup.chunkZ());
        if (lookup.pos() != null) {
            remove(chunkKey, lookup.pos()); // re-rendered below if still registered with its hologram on
        }
        Map<String, TrackedPreview> tracked = byChunk.getOrDefault(chunkKey, Map.of());
        for (ShopResult shop : shops) {
            String pos = posKey(shop.signX(), shop.signY(), shop.signZ());
            if (lookup.pos() != null ? lookup.pos().equals(pos) : !tracked.containsKey(pos)) {
                render(shop);
            }
        }
    }

    @Override
    public void resize() {
        forEachDisplay(display -> {
            Transformation tr = display.getTransformation();
            display.setTransformation(new Transformation(
                    tr.getTranslation(),
                    tr.getLeftRotation(),
                    new Vector3f(presentation.previewScale()),
                    tr.getRightRotation()));
        });
    }

    @Override
    public void applyPreference(Player player, boolean visible) {
        if (visible) {
            previewHidden.remove(player.getUniqueId());
            forEachDisplay(d -> player.showEntity(plugin, d));
        } else {
            previewHidden.add(player.getUniqueId());
            forEachDisplay(d -> player.hideEntity(plugin, d));
        }
    }

    private void forEachDisplay(java.util.function.Consumer<ItemDisplay> action) {
        for (Map<String, TrackedPreview> chunk : byChunk.values()) {
            chunk.values().forEach(preview -> action.accept(preview.display()));
        }
    }

    /** Reconstruct the display item: the ItemStack if stored, else the base material. */
    static ItemStack toItemStack(ShopResult shop) {
        return ShopItem.decode(shop.material(), shop.itemData());
    }

    private static String chunkKey(String world, int chunkX, int chunkZ) {
        return world + ':' + chunkX + ':' + chunkZ;
    }

    private static String posKey(int x, int y, int z) {
        return x + ":" + y + ":" + z;
    }
}
