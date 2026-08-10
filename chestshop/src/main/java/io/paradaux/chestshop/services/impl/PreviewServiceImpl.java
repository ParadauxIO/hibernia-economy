package io.paradaux.chestshop.services.impl;

import io.paradaux.chestshop.services.PreviewService;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.treasury.api.market.ShopResult;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Floating item previews above shop signs — a port of the legacy chestshop-database
 * holograms. One non-persistent {@link ItemDisplay} per shop, a block above the
 * sign, indexed by chunk so the {@code PreviewListener} can load/unload them with
 * the chunk. Per-player visibility is honoured with {@code hideEntity}: a player
 * who turns previews off has every display hidden for them only.
 */
@Singleton
public class PreviewServiceImpl implements PreviewService {

    private static final float SCALE = 0.5f;

    private final JavaPlugin plugin;

    // chunkKey -> (posKey -> display)
    private final Map<String, Map<String, ItemDisplay>> byChunk = new ConcurrentHashMap<>();
    // players who have previews turned off (hidden for them only)
    private final Set<UUID> previewHidden = ConcurrentHashMap.newKeySet();

    @Inject
    public PreviewServiceImpl(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void render(ShopResult shop) {
        if (!shop.hologram()) {
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

        Location loc = new Location(world, x + 0.5, y + 1, z + 0.5);
        ItemDisplay display = world.spawn(loc, ItemDisplay.class, d -> {
            d.setItemStack(item);
            d.setBillboard(Display.Billboard.CENTER);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GROUND);
            Transformation tr = d.getTransformation();
            d.setTransformation(new Transformation(
                    tr.getTranslation(), tr.getLeftRotation(),
                    new Vector3f(SCALE, SCALE, SCALE), tr.getRightRotation()));
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
        byChunk.computeIfAbsent(chunkKey(worldName, x >> 4, z >> 4), k -> new ConcurrentHashMap<>())
                .put(posKey(x, y, z), display);
    }

    @Override
    public void destroy(String worldName, int x, int y, int z) {
        Map<String, ItemDisplay> chunk = byChunk.get(chunkKey(worldName, x >> 4, z >> 4));
        if (chunk == null) {
            return;
        }
        ItemDisplay display = chunk.remove(posKey(x, y, z));
        if (display != null) {
            display.remove();
        }
    }

    @Override
    public void destroyChunk(String worldName, int chunkX, int chunkZ) {
        Map<String, ItemDisplay> chunk = byChunk.remove(chunkKey(worldName, chunkX, chunkZ));
        if (chunk != null) {
            chunk.values().forEach(ItemDisplay::remove);
        }
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
        for (Map<String, ItemDisplay> chunk : byChunk.values()) {
            chunk.values().forEach(action);
        }
    }

    /** Reconstruct the display item: the custom ItemStack if stored, else the base material. */
    static ItemStack toItemStack(ShopResult shop) {
        if (shop.itemCustom() && shop.itemData() != null) {
            try {
                YamlConfiguration yc = new YamlConfiguration();
                yc.loadFromString(shop.itemData());
                ItemStack item = yc.getItemStack("item");
                if (item != null) {
                    return item;
                }
            } catch (Exception ignored) {
                // fall back to the base material below
            }
        }
        try {
            return new ItemStack(Material.valueOf(shop.material()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String chunkKey(String world, int chunkX, int chunkZ) {
        return world + ':' + chunkX + ':' + chunkZ;
    }

    private static String posKey(int x, int y, int z) {
        return x + ":" + y + ":" + z;
    }
}
