package io.paradaux.treasury.api;

import io.paradaux.treasury.api.market.ChestShopSaleRecord;
import io.paradaux.treasury.api.market.ChestShopShopRecord;

import java.util.UUID;

/**
 * Write side of the ChestShop sales tracker + live shop registry. Treasury owns
 * the economy DB, so it persists these tables ({@code chestshop_sale},
 * {@code chestshop_shop}); ChestShop-3 (which has no economy-DB connection of
 * its own) calls this via the Bukkit {@code ServicesManager}, the same way it
 * already consumes {@link TreasuryApi}/{@link TaxApi}. Reads are done directly
 * by treasury-rest-api.
 *
 * <p>All methods are best-effort/fail-soft for the caller: a logging failure
 * must never break a trade or shop edit.
 */
public interface MarketApi {

    /** Record a completed ChestShop trade (analytics; references the ledger txn). */
    void recordSale(ChestShopSaleRecord sale);

    /** Insert or update a shop in the live registry (create / restock / price change). */
    void upsertShop(ChestShopShopRecord shop);

    /** Mark a shop inactive by sign location (destroyed / removed). */
    void deactivateShop(String world, int signX, int signY, int signZ);

    /**
     * Update the live stock and remaining capacity of a shop by sign location
     * (restock/sale). Either may be null (admin/infinite or unmeasured).
     */
    void updateShopStock(String world, int signX, int signY, int signZ,
                         Integer currentStock, Integer estimatedCapacity);

    /**
     * Owner-controlled search visibility for a shop ({@code chestshop_shop.visible}).
     * Distinct from the destroyed tombstone — a hidden shop is live but excluded
     * from {@code /find}. No-op if the shop isn't in the registry. (PAR-167)
     */
    void setShopVisibility(String world, int signX, int signY, int signZ, boolean visible);

    /**
     * Per-shop hologram toggle ({@code chestshop_shop.hologram}) — whether a
     * floating item preview renders above the sign. No-op if the shop isn't in
     * the registry. (PAR-168)
     */
    void setShopHologram(String world, int signX, int signY, int signZ, boolean hologram);

    /**
     * Per-player hologram preference ({@code chestshop_preview_preference}) —
     * whether this player sees shop holograms at all. Upserted. (PAR-168)
     */
    void setPreviewPreference(UUID playerUuid, boolean visible);
}
