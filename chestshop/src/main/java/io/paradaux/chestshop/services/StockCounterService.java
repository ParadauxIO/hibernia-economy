package io.paradaux.chestshop.services;

import io.paradaux.chestshop.model.ShopCreation;
import io.paradaux.chestshop.model.Transaction;
import org.bukkit.block.Sign;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * The shop-sign stock-counter business logic, extracted from {@code StockCounterListener}
 * (chestshop/structure/0002): it keeps the {@code Q n : C m} counter on a shop sign's quantity
 * line in sync with the container's stock. {@link #onPreShopCreation} and {@link #onTransaction}
 * are invoked directly by {@code ShopService}/{@code TransactionService}; the remaining methods are
 * shared with the {@code ItemMoveListener}/{@code StockCounterListener} Bukkit entrypoints. Was the
 * static {@code StockCounter} (PAR-282).
 */
public interface StockCounterService {

    /** Normalise the quantity line and seed the stock counter for a freshly-created shop. */
    void onPreShopCreation(ShopCreation event);

    /** Refresh the stock counter on every shop sign connected to a completed trade's container. */
    void onTransaction(Transaction event);

    /** Recompute and (if changed) rewrite the counter on {@code sign} for {@code chestShopInventory}. */
    void updateCounterOnQuantityLine(Sign sign, Inventory chestShopInventory, ItemStack... extraItems);

    /** Refresh the counter for the sign connected to the hopper destination taking {@code toAdd}. */
    void updateCounterOnItemMoveEvent(ItemStack toAdd, InventoryHolder destinationHolder);

    /** Strip the {@code : C m} counter suffix back to a bare quantity on {@code sign}. */
    void removeCounterFromQuantityLine(Sign sign);

    /** Build the {@code Q amount : C stock} counter line for {@code itemTransacted}. */
    String getQuantityLineWithCounter(int amount, ItemStack itemTransacted, Inventory chestShopInventory);

    /** The item a shop trades, parsed from its sign, or {@code null} if unparseable. */
    ItemStack determineItemTradedByShop(Sign sign);

    /** The item a shop trades, parsed from a material string, or {@code null} if unparseable. */
    ItemStack determineItemTradedByShop(String material);
}
