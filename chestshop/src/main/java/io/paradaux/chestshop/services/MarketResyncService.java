package io.paradaux.chestshop.services;

import org.bukkit.command.CommandSender;

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
public interface MarketResyncService {

    /**
     * Kick off a resync, reporting progress to {@code initiator}. One at a time.
     * {@code chunksPerTick} is clamped to a sane range — the scan runs on the main thread.
     */
    void resync(CommandSender initiator, int chunksPerTick);

    /** Queue a loaded chunk for bounded discovery and reconciliation without loading any chunks. */
    void discoverChunk(org.bukkit.Chunk chunk);

    /** Queue chunks already loaded when ChestShop enables. */
    void discoverLoadedChunks();
}
