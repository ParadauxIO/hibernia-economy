package io.paradaux.chestshop.services;

import io.paradaux.chestshop.model.Transaction;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * The goods leg of a trade, moved atomically: both inventories are snapshotted first and restored
 * on any shortfall, so the money leg only runs once the goods moved in full. Extracted from
 * TransactionServiceImpl (PAR-317).
 */
public interface GoodsTransfer {

    /**
     * Move every stack from {@code source} to {@code target}. On any shortfall both are restored
     * and {@code false} is returned so the caller can cancel before money moves.
     */
    boolean transfer(Inventory source, Inventory target, ItemStack[] items);

    /**
     * The client-side half of an unlimited admin-shop trade: the owner side is infinite (no
     * container), so a buy ({@code add}) spawns the stock into the client and a sell removes it.
     * Same snapshot/restore atomicity as {@link #transfer}.
     */
    boolean moveUnlimited(Inventory client, ItemStack[] items, boolean add);

    /** Reverse a completed goods move after a failed money leg, keeping the trade atomic. */
    void reverse(Transaction event);
}
