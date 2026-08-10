package io.paradaux.chestshop.services;

import io.paradaux.chestshop.model.CreatedShop;
import io.paradaux.chestshop.model.DestroyedShop;
import io.paradaux.chestshop.model.Transaction;
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
}
