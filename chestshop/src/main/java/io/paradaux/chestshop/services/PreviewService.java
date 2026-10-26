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

    /** Remove every tracked hologram (plugin disable — the displays would otherwise outlive it). */
    void destroyAll();

    /**
     * Look a chunk's shops up in the registry and render the holograms it is missing
     * (chunk load / resync). The lookup is asynchronous. Main thread.
     */
    void loadChunk(String worldName, int chunkX, int chunkZ);

    /** {@link #loadChunk} for every chunk already loaded (plugin enable). Main thread. */
    void loadAll();

    /**
     * Re-read one shop from the registry and replace its hologram — or drop it if the shop
     * is gone or has its hologram off (shop created / re-registered). Main thread.
     */
    void refresh(String worldName, int x, int y, int z);

    /** Apply the configured scale to existing displays. Main thread. */
    void resize();

    /** Set whether a player sees previews, updating live displays (does not persist). */
    void applyPreference(Player player, boolean visible);
}
