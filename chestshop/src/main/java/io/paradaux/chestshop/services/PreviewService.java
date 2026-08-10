package io.paradaux.chestshop.services;

import io.paradaux.treasury.api.market.ShopResult;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Floating item previews above shop signs — a port of the legacy chestshop-database
 * holograms. One non-persistent {@link ItemDisplay} per shop, a block above the
 * sign, indexed by chunk so the {@code PreviewListener} can load/unload them with
 * the chunk. Per-player visibility is honoured with {@code hideEntity}: a player
 * who turns previews off has every display hidden for them only.
 */
public interface PreviewService {

    /** Render the hologram for a registry shop (no-op if its hologram flag is off). */
    void render(ShopResult shop);

    /** Render (or replace) the hologram for a shop at a sign location. Main thread. */
    void render(String worldName, int x, int y, int z, ItemStack item);

    /** Remove the hologram at a sign location, if any. */
    void destroy(String worldName, int x, int y, int z);

    /** Remove every hologram in a chunk (chunk unload). */
    void destroyChunk(String worldName, int chunkX, int chunkZ);

    /** Set whether a player sees previews, updating live displays (does not persist). */
    void applyPreference(Player player, boolean visible);
}
