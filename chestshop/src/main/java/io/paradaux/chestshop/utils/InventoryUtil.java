package io.paradaux.chestshop.utils;

import org.bukkit.block.DoubleChest;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

/**
 * Pure, stateless inventory helpers: reading an inventory's storage contents (with the
 * legacy-API fallback), emptiness/empty-slot counting, effective size, and item counting.
 * The stateful, config/equality-backed operations (getAmount, fits, add/remove/transfer,
 * stacking, STACK_TO_64 sizing) split out to
 * {@link io.paradaux.chestshop.services.InventoryService} (PAR-282).
 *
 * @author Acrobot
 */
public final class InventoryUtil {

    private InventoryUtil() {
    }

    private static Boolean legacyContents = null;

    /**
     * The inventory's storage contents (never the armor/extra slots), with a one-time
     * fallback to {@link Inventory#getContents()} on servers lacking the storage API.
     */
    public static ItemStack[] getStorageContents(Inventory inventory) {
        if (legacyContents == null) {
            try {
                inventory.getStorageContents();
                legacyContents = false;
            } catch (NoSuchMethodError e) {
                legacyContents = true;
            }
        }

        return legacyContents ? inventory.getContents() : inventory.getStorageContents();
    }

    /**
     * Tells if the inventory is empty
     *
     * @param inventory inventory
     * @return Is the inventory empty?
     */
    public static boolean isEmpty(Inventory inventory) {
        for (ItemStack stack : getStorageContents(inventory)) {
            if (!MaterialUtil.isEmpty(stack)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Count amount of empty slots in an inventory
     *
     * @param inventory the inventory
     * @return The amount of empty slots
     */
    public static int countEmpty(Inventory inventory) {
        int emptyAmount = 0;
        for (ItemStack stack : getStorageContents(inventory)) {
            if (MaterialUtil.isEmpty(stack)) {
                emptyAmount++;
            }
        }

        return emptyAmount;
    }

    // Don't use the armor slots or extra slots
    public static int effectiveSize(Inventory inventory) {
        return getStorageContents(inventory).length;
    }

    /**
     * Counts the amount of items in ItemStacks
     *
     * @param items ItemStacks of items to count
     * @return How many items are there?
     */
    public static int countItems(ItemStack... items) {
        int count = 0;

        for (ItemStack item : items) {
            count += item.getAmount();
        }

        return count;
    }

    /**
     * Counts leftovers from a map
     *
     * @param items Leftovers
     * @return Number of leftovers
     */
    public static int countItems(Map<Integer, ItemStack> items) {
        int totalLeft = 0;

        for (ItemStack left : items.values()) {
            totalLeft += left.getAmount();
        }

        return totalLeft;
    }

    /**
     * The inventory's holder, snapshot or live.
     *
     * @param inventory   the inventory
     * @param useSnapshot {@code false} to get the live holder (not a snapshot copy)
     * @return the inventory's holder
     */
    public static InventoryHolder getHolder(Inventory inventory, boolean useSnapshot) {
        return inventory.getHolder(useSnapshot);
    }

    /**
     * A double chest's left-side holder, snapshot or live.
     *
     * @param doubleChest the double chest
     * @param useSnapshot {@code false} to get the live holder (not a snapshot copy)
     * @return the left side's holder
     */
    public static InventoryHolder getLeftSide(DoubleChest doubleChest, boolean useSnapshot) {
        return doubleChest.getLeftSide(useSnapshot);
    }

    /**
     * A double chest's right-side holder, snapshot or live.
     *
     * @param doubleChest the double chest
     * @param useSnapshot {@code false} to get the live holder (not a snapshot copy)
     * @return the right side's holder
     */
    public static InventoryHolder getRightSide(DoubleChest doubleChest, boolean useSnapshot) {
        return doubleChest.getRightSide(useSnapshot);
    }
}
