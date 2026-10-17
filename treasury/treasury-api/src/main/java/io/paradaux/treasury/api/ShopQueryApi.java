package io.paradaux.treasury.api;

import io.paradaux.treasury.api.market.ShopLocation;
import io.paradaux.treasury.api.market.ShopResult;
import io.paradaux.treasury.api.market.ShopSearchQuery;

import java.util.List;
import java.util.UUID;

/**
 * Read access over the live shop registry ({@code chestshop_shop}) for the
 * in-ChestShop {@code /find} search and hologram loader (PAR-166 epic).
 * Complements the write-only {@link MarketApi}: ChestShop has no economy-DB
 * connection of its own, so it reads shops through this, registered on the
 * Bukkit {@code ServicesManager} exactly like {@link MarketApi}/{@link TreasuryApi}.
 * Pure reads — no money mutation. Public market data (shop listings are already
 * exposed via the REST API), so no owner-scope gating.
 */
public interface ShopQueryApi {

    /**
     * Active, owner-visible shops matching the query's item (exact or fuzzy) and
     * optional world. Returns lean rows for the caller to filter/sort/distance
     * client-side; capped at {@link ShopSearchQuery#getLimit()}.
     */
    List<ShopResult> searchShops(ShopSearchQuery query);

    /**
     * Active shops whose sign sits in the given chunk (x/z &gt;&gt; 4), for the
     * hologram loader. Includes hidden shops — hologram visibility is a separate
     * per-shop flag the caller honours, independent of search visibility.
     */
    List<ShopResult> shopsInChunk(String world, int chunkX, int chunkZ);

    /**
     * Locations of every active shop sign, optionally scoped to one world
     * (null = all). Used by resync to enumerate known shops and reconcile against
     * what's actually placed in the world (PAR-169/174).
     */
    List<ShopLocation> activeShopLocations(String world);

    /**
     * Distinct item keys of active shops whose key contains {@code substring}
     * (case-insensitive), for {@code /find} tab-completion. Capped at {@code limit}.
     */
    List<String> matchingItemKeys(String substring, int limit);

    /**
     * Whether this player sees shop holograms ({@code chestshop_preview_preference}).
     * Returns {@code defaultVisible} when the player has no stored preference.
     */
    boolean previewVisible(UUID playerUuid, boolean defaultVisible);
}
