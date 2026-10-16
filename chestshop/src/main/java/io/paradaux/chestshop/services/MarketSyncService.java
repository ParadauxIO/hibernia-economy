package io.paradaux.chestshop.services;

import io.paradaux.chestshop.model.CreatedShop;
import io.paradaux.chestshop.model.DestroyedShop;
import io.paradaux.chestshop.model.Transaction;
import org.bukkit.block.Sign;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Keeps the market sales tracker + live shop registry in sync with ChestShop activity, extracted
 * from {@code MarketListener} (chestshop/structure/0002): record a trade and upsert the shop
 * ({@link #onTransaction}), register/refresh a new shop ({@link #onShopCreated}), deactivate a
 * removed one ({@link #onShopDestroyed}), and recount stock on a manual restock
 * ({@link #onInventoryClose}). The first three are invoked directly by
 * {@code TransactionService}/{@code ShopService} at the MONITOR point of their pipelines;
 * {@link #onInventoryClose} backs the listener's only remaining Bukkit handler. All are fully
 * guarded — analytics must never disrupt a trade.
 */
public interface MarketSyncService {

    /** Record a completed trade and refresh the traded shop's stock in the market registry. */
    void onTransaction(Transaction event);

    /** Register (or refresh) a freshly-created shop in the market registry. */
    void onShopCreated(CreatedShop event);

    /** Mark a removed shop inactive in the market registry. */
    void onShopDestroyed(DestroyedShop event);

    /** Recount stock for every shop sign connected to a closed container. */
    void onInventoryClose(InventoryHolder holder, Inventory inventory);

    /**
     * Build a registry row from a live shop sign and upsert it (resync discovery, and making
     * sure a row exists before an owner toggle is written to it).
     *
     * @return {@code false} if the sign's item or owner can't be resolved, or the market is off
     */
    boolean registerShop(Sign sign);

    /**
     * Reconcile one sign position against the world after a block change that bypassed the
     * Bukkit events (WorldEdit): a valid shop sign standing there is (re-)registered, anything
     * else deactivates the row. Unloaded worlds/chunks are left untouched until resync can
     * inspect them; absence from memory is not evidence of deletion.
     */
    void onBlockReplaced(String world, int x, int y, int z);
}
