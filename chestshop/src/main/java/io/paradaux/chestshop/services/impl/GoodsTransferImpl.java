package io.paradaux.chestshop.services.impl;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.chestshop.model.Transaction;
import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import io.paradaux.chestshop.services.GoodsTransfer;
import io.paradaux.chestshop.services.InventoryService;
import io.paradaux.chestshop.utils.InventoryUtil;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.block.BlockState;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import static io.paradaux.chestshop.model.Transaction.TransactionType.BUY;

/**
 * The goods leg of a trade, moved atomically: both inventories are snapshotted first and restored
 * on any shortfall, so the money leg only runs once the goods moved in full. Extracted from
 * TransactionServiceImpl (PAR-317).
 */
@Singleton
@Slf4j
public class GoodsTransferImpl implements GoodsTransfer {

    private final InventoryService inventoryService;
    private final ChestShopConfiguration config;

    @Inject
    GoodsTransferImpl(InventoryService inventoryService, ChestShopConfiguration config) {
        this.inventoryService = inventoryService;
        this.config = config;
    }

    /**
     * Move every stack from {@code source} to {@code target}. On any shortfall both are restored
     * and {@code false} is returned so the caller can cancel before money moves.
     */
    @Override
    public boolean transfer(Inventory source, Inventory target, ItemStack[] items) {
        ItemStack[] sourceSnapshot = cloneContents(source);
        ItemStack[] targetSnapshot = cloneContents(target);

        int leftOver = 0;
        for (ItemStack item : items) {
            leftOver += config.isStackTo64()
                    ? inventoryService.transfer(item, source, target, 64)
                    : inventoryService.transfer(item, source, target);
        }

        if (leftOver > 0) {
            source.setContents(sourceSnapshot);
            target.setContents(targetSnapshot);
            return false;
        }

        update(source.getHolder());
        update(target.getHolder());
        return true;
    }

    /**
     * The client-side half of an unlimited admin-shop trade: the owner side is infinite (no
     * container), so a buy ({@code add}) spawns the stock into the client and a sell removes it.
     * Same snapshot/restore atomicity as {@link #transfer}.
     */
    @Override
    public boolean moveUnlimited(Inventory client, ItemStack[] items, boolean add) {
        ItemStack[] snapshot = cloneContents(client);

        int leftOver = 0;
        for (ItemStack item : items) {
            leftOver += add
                    ? (config.isStackTo64() ? inventoryService.add(item, client, 64) : inventoryService.add(item, client))
                    : inventoryService.remove(item, client);
        }

        if (leftOver > 0) {
            client.setContents(snapshot);
            return false;
        }

        update(client.getHolder());
        return true;
    }

    /** Reverse a completed goods move after a failed money leg, keeping the trade atomic. */
    @Override
    public void reverse(Transaction event) {
        boolean buy = event.getTransactionType() == BUY;
        boolean reversed = event.isUnlimitedOwner()
                ? moveUnlimited(event.getClientInventory(), event.getStock(), !buy)
                : (buy
                        ? transfer(event.getClientInventory(), event.getOwnerInventory(), event.getStock())
                        : transfer(event.getOwnerInventory(), event.getClientInventory(), event.getStock()));

        if (!reversed) {
            log.error("Failed to reverse the goods of a ChestShop transaction at "
                    + (event.getSign() != null ? event.getSign().getLocation() : "<unknown location>")
                    + " after the money leg failed. The trade may be in an inconsistent state.");
        }
    }

    private static ItemStack[] cloneContents(Inventory inventory) {
        ItemStack[] contents = inventory.getContents();
        ItemStack[] copy = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) {
            copy[i] = contents[i] == null ? null : contents[i].clone();
        }
        return copy;
    }

    private static void update(InventoryHolder holder) {
        if (holder instanceof Player player) {
            player.updateInventory();
        } else if (holder instanceof BlockState blockState) {
            blockState.update();
        } else if (holder instanceof DoubleChest doubleChest) {
            update(InventoryUtil.getLeftSide(doubleChest, false));
            update(InventoryUtil.getRightSide(doubleChest, false));
        }
    }
}
