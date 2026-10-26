package io.paradaux.chestshop.services.impl;

import io.paradaux.chestshop.services.MaterialService;
import io.paradaux.chestshop.services.InventoryService;
import com.google.common.collect.ImmutableMap;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import io.paradaux.chestshop.utils.InventoryUtil;
import io.paradaux.chestshop.utils.MaterialUtil;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * The stateful inventory operations split out of {@code InventoryUtil} (PAR-282): amount/
 * capacity queries, fit checks, add/remove/transfer, and STACK_TO_64-aware stacking. These
 * depend on {@link ChestShopConfiguration} (STACK_TO_64) and item equality
 * ({@link MaterialService#equals}); the pure, stateless slot/count helpers stay static on
 * {@link InventoryUtil}, which this service composes.
 *
 * @author Acrobot
 */
@Singleton
public class InventoryServiceImpl implements InventoryService {

    private final ChestShopConfiguration config;
    private final MaterialService materialService;

    @Inject
    public InventoryServiceImpl(ChestShopConfiguration config, MaterialService materialService) {
        this.config = config;
        this.materialService = materialService;
    }

    @Override
    public int getAmount(ItemStack item, Inventory inventory) {
        if (!inventory.contains(item.getType())) {
            return 0;
        }

        if (inventory.getType() == null) {
            return Integer.MAX_VALUE;
        }

        HashMap<Integer, ? extends ItemStack> items = inventory.all(item.getType());
        int itemAmount = 0;

        for (ItemStack iStack : items.values()) {
            if (!materialService.equals(iStack, item)) {
                continue;
            }

            itemAmount += iStack.getAmount();
        }

        return itemAmount;
    }

    @Override
    public int getRemainingCapacity(ItemStack item, Inventory inventory) {
        if (inventory == null) {
            return 0;
        }
        int perStack = Math.min(getMaxStackSize(item), inventory.getMaxStackSize());
        int capacity = 0;
        for (ItemStack slot : InventoryUtil.getStorageContents(inventory)) {
            if (MaterialUtil.isEmpty(slot)) {
                capacity += perStack;
            } else if (materialService.equals(slot, item)) {
                capacity += Math.max(0, perStack - slot.getAmount());
            }
        }
        return capacity;
    }

    @Override
    public boolean hasItems(ItemStack[] items, Inventory inventory) {
        Map<ItemStack, Integer> itemCounts = getItemCounts(items);
        for (Map.Entry<ItemStack, Integer> entry : itemCounts.entrySet()) {
            if (getAmount(entry.getKey(), inventory) < entry.getValue()) {
                return false;
            }
        }

        return true;
    }

    @Override
    public boolean fits(ItemStack[] items, Inventory inventory) {
        Map<ItemStack, Integer> itemCounts = getItemCounts(items);
        for (Map.Entry<ItemStack, Integer> entry : itemCounts.entrySet()) {
            if (!fits(entry.getKey(), entry.getValue(), inventory)) {
                return false;
            }
        }

        return true;
    }

    @Override
    public boolean fits(ItemStack item, Inventory inventory) {
        return fits(item, item.getAmount(), inventory);
    }

    @Override
    public boolean fits(ItemStack item, int amount, Inventory inventory) {
        int left = amount;
        if (inventory.getSize() == Integer.MAX_VALUE) {
            return true;
        }

        for (ItemStack iStack : InventoryUtil.getStorageContents(inventory)) {
            if (left <= 0) {
                return true;
            }

            if (MaterialUtil.isEmpty(iStack)) {
                left -= getMaxStackSize(item);
                continue;
            }

            if (!materialService.equals(iStack, item)) {
                continue;
            }

            left -= (getMaxStackSize(iStack) - iStack.getAmount());
        }

        return left <= 0;
    }

    @Override
    public int transfer(ItemStack item, Inventory sourceInventory, Inventory targetInventory) {
        return transfer(item, sourceInventory, targetInventory, item.getMaxStackSize());
    }

    @Override
    public int transfer(ItemStack item, Inventory sourceInventory, Inventory targetInventory, int maxStackSize) {
        if (item.getAmount() < 1) {
            return 0;
        }

        int amount = item.getAmount();
        for (ItemStack currentItem : sourceInventory) {
            if (materialService.equals(currentItem, item)) {
                ItemStack clone = currentItem.clone();
                if (currentItem.getAmount() >= amount) {
                    clone.setAmount(amount);
                    amount = 0;
                } else {
                    clone.setAmount(currentItem.getAmount());
                    amount -= clone.getAmount();
                }
                int leftOver = add(clone, targetInventory, maxStackSize);
                if (leftOver > 0) {
                    currentItem.setAmount(currentItem.getAmount() - clone.getAmount() + leftOver);
                    if (amount > 0) {
                        amount += leftOver;
                    } else {
                        return leftOver;
                    }
                } else {
                    currentItem.setAmount(currentItem.getAmount() - clone.getAmount());
                }
            }
            if (amount <= 0) {
                break;
            }
        }
        return amount;
    }

    @Override
    public int add(ItemStack item, Inventory inventory, int maxStackSize) {
        if (item.getAmount() < 1) {
            return 0;
        }

        if (maxStackSize == item.getMaxStackSize()) {
            return add(item, inventory);
        }

        return addManually(item, inventory, maxStackSize);
    }

    private int addManually(ItemStack item, Inventory inventory, int maxStackSize) {
        int amountLeft = item.getAmount();

        for (int currentSlot = 0; currentSlot < InventoryUtil.effectiveSize(inventory) && amountLeft > 0; currentSlot++) {
            ItemStack currentItem = inventory.getItem(currentSlot);

            if (MaterialUtil.isEmpty(currentItem)) {
                currentItem = new ItemStack(item);
                currentItem.setAmount(Math.min(amountLeft, maxStackSize));
                inventory.setItem(currentSlot, currentItem);

                amountLeft -= currentItem.getAmount();
            } else if (currentItem.getAmount() < maxStackSize && materialService.equals(currentItem, item)) {
                int neededToAdd = Math.min(maxStackSize - currentItem.getAmount(), amountLeft);

                currentItem.setAmount(currentItem.getAmount() + neededToAdd);

                amountLeft -= neededToAdd;
            }
        }
        return amountLeft;
    }

    @Override
    public int add(ItemStack item, Inventory inventory) {
        Map<Integer, ItemStack> leftovers = inventory.addItem(item.clone()); // item needs to be cloned as cb changes the amount of the stack size

        if (!leftovers.isEmpty()) {
            for (Iterator<ItemStack> iterator = leftovers.values().iterator(); iterator.hasNext(); ) {
                ItemStack left = iterator.next();
                int amountLeft = addManually(left, inventory, left.getMaxStackSize());
                if (amountLeft == 0) {
                    iterator.remove();
                } else {
                    left.setAmount(amountLeft);
                }
            }
        }

        return InventoryUtil.countItems(leftovers);
    }

    @Override
    public int remove(ItemStack item, Inventory inventory) {
        Map<Integer, ItemStack> leftovers = inventory.removeItem(item);

        if (!leftovers.isEmpty()) {
            leftovers.values().removeIf(left -> removeManually(left, inventory) == 0);
        }

        return InventoryUtil.countItems(leftovers);
    }

    private int removeManually(ItemStack item, Inventory inventory) {
        int amountLeft = item.getAmount();

        for (int currentSlot = 0; currentSlot < InventoryUtil.effectiveSize(inventory) && amountLeft > 0; currentSlot++) {
            ItemStack currentItem = inventory.getItem(currentSlot);

            if (currentItem != null && materialService.equals(currentItem, item)) {
                int neededToRemove = Math.min(currentItem.getAmount(), amountLeft);

                currentItem.setAmount(currentItem.getAmount() - neededToRemove);
                inventory.setItem(currentSlot, currentItem);

                amountLeft -= neededToRemove;
            }
        }
        return amountLeft;
    }

    @Override
    public Map<ItemStack, Integer> getItemCounts(ItemStack... items) {
        if (items == null || items.length == 0) {
            return Collections.emptyMap();
        }
        if (items.length == 1) {
            ItemStack itemClone = items[0].clone();
            itemClone.setAmount(1);
            return ImmutableMap.of(itemClone, items[0].getAmount());
        }

        Map<ItemStack, Integer> counts = new LinkedHashMap<>();

        Iterating:
        for (ItemStack item : items) {
            for (Map.Entry<ItemStack, Integer> entry : counts.entrySet()) {
                if (materialService.equals(item, entry.getKey())) {
                    entry.setValue(entry.getValue() + item.getAmount());
                    continue Iterating;
                }
            }

            ItemStack itemClone = item.clone();
            itemClone.setAmount(1);
            counts.put(itemClone, item.getAmount());
        }

        return counts;
    }

    @Override
    public int getMaxStackSize(ItemStack item) {
        return config.isStackTo64() ? 64 : item.getMaxStackSize();
    }

    @Override
    public ItemStack[] getItemsStacked(ItemStack... items) {
        List<ItemStack> stackedItems = new LinkedList<>();
        for (ItemStack item : items) {
            stackItems(stackedItems, item, item.getAmount());
        }
        return stackedItems.toArray(new ItemStack[0]);
    }

    @Override
    public ItemStack[] getItemStacked(ItemStack item, int amount) {
        return stackItems(new LinkedList<>(), item, amount).toArray(new ItemStack[0]);
    }

    /**
     * Add properly stacked items to a collection
     *
     * @param stackedItems The collection to add the items to
     * @param item      The item to stack
     * @param amount    The amount of the item to stack
     * @return The input collection
     */
    private Collection<ItemStack> stackItems(Collection<ItemStack> stackedItems, ItemStack item, int amount) {
        int maxStackSize = getMaxStackSize(item);
        if (maxStackSize == 0) {
            return stackedItems;
        }

        ItemStack itemClone = item.clone();

        for (ItemStack stackedItem : stackedItems) {
            if (materialService.equals(stackedItem, itemClone) && stackedItem.getAmount() < getMaxStackSize(stackedItem)) {
                int amountToAdd = Math.min(getMaxStackSize(stackedItem) - stackedItem.getAmount(), amount);
                stackedItem.setAmount(stackedItem.getAmount() + amountToAdd);
                amount = amount - amountToAdd;
                if (amount <= 0) {
                    break;
                }

            }
        }
        if (amount > maxStackSize || amount <= 0) {
            for (int i = 0; i < Math.floor((double) amount / maxStackSize); i++) {
                ItemStack itemAddClone = itemClone.clone();
                itemAddClone.setAmount(maxStackSize);
                stackedItems.add(itemAddClone);
            }
            amount = amount % maxStackSize;
        }
        if (amount > 0) {
            itemClone.setAmount(amount);
            stackedItems.add(itemClone);
        }
        return stackedItems;
    }
}
