package io.paradaux.chestshop.services;

import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import io.paradaux.chestshop.utils.InventoryUtil;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
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
public interface InventoryService {

    /**
     * Returns the amount of the item inside the inventory
     *
     * @param item      Item to check
     * @param inventory inventory
     * @return amount of the item
     */
    int getAmount(ItemStack item, Inventory inventory);

    /**
     * Remaining space for {@code item} in the inventory — how many more units of
     * the shop item would still fit. Per slot: an empty slot contributes a full
     * stack ({@code min(itemMax, invMax)}), a slot already holding a similar item
     * contributes its headroom, a slot holding anything else contributes nothing.
     * Honours the STACK_TO_64 config via {@link #getMaxStackSize}. Used to track a
     * sell-shop's "room to sell into" for {@code /find} (REMAINING_CAPACITY sort,
     * hide-full filter).
     *
     * @param item      the shop item
     * @param inventory the shop container inventory
     * @return remaining capacity in units (0 if the inventory is null)
     */
    int getRemainingCapacity(ItemStack item, Inventory inventory);

    /**
     * Checks if the inventory has stock of this type
     *
     * @param items     items
     * @param inventory inventory
     * @return Does the inventory contain stock of this type?
     */
    boolean hasItems(ItemStack[] items, Inventory inventory);

    /**
     * Checks if items fit in the inventory
     *
     * @param items     Items to check
     * @param inventory inventory
     * @return Do the items fit inside the inventory?
     */
    boolean fits(ItemStack[] items, Inventory inventory);

    /**
     * Checks if the item fits the inventory
     *
     * @param item      Item to check
     * @param inventory inventory
     * @return Does item fit inside inventory?
     */
    boolean fits(ItemStack item, Inventory inventory);

    /**
     * Checks if the item fits the inventory
     *
     * @param item      Item to check
     * @param amount    Amount of the item to check
     * @param inventory inventory
     * @return Does item fit inside inventory?
     */
    boolean fits(ItemStack item, int amount, Inventory inventory);

    /**
     * Transfers an item from one inventory to another one
     *
     * @param item              Item to transfer
     * @param sourceInventory   Inventory to transfer the item from
     * @param targetInventory   Inventory to transfer the item to
     * @return Number of leftover items
     */
    int transfer(ItemStack item, Inventory sourceInventory, Inventory targetInventory);

    /**
     * Transfers an item from one inventory to another one
     *
     * @param item              Item to transfer
     * @param sourceInventory   Inventory to transfer the item from
     * @param targetInventory   Inventory to transfer the item to
     * @param maxStackSize      Maximum item's stack size
     * @return Number of leftover items
     */
    int transfer(ItemStack item, Inventory sourceInventory, Inventory targetInventory, int maxStackSize);

    /**
     * Adds an item to the inventory with given maximum stack size
     *
     * @param item         Item to add
     * @param inventory    Inventory
     * @param maxStackSize Maximum item's stack size
     * @return Number of leftover items
     */
    int add(ItemStack item, Inventory inventory, int maxStackSize);

    /**
     * Adds an item to the inventor
     *
     * @param item      Item to add
     * @param inventory Inventory
     * @return Number of leftover items
     */
    int add(ItemStack item, Inventory inventory);

    /**
     * Removes an item from the inventory
     *
     * @param item      Item to remove
     * @param inventory Inventory
     * @return Number of items that couldn't be removed
     */
    int remove(ItemStack item, Inventory inventory);

    /**
     * If items in arguments are similar, this function counts them
     *
     * @param items Items to count
     * @return The map of items and their amounts. The keys are clones of the original items with their amounts set to 1.
     */
    Map<ItemStack, Integer> getItemCounts(ItemStack... items);

    /**
     * Get the max size an item stack is allowed to stack to while respecting the STACK_TO_64 config property
     *
     * @param item The item to get the max stacksize of
     * @return The max stacksize of the item stack's type or 64 if STACK_TO_64 is enabled
     */
    int getMaxStackSize(ItemStack item);

    /**
     * Get an array of different item stacks that are properly stacked to their max stack size
     *
     * @param items The items to stack
     * @return An array of item stacks which's amount is a maximum of the allowed stack size
     */
    ItemStack[] getItemsStacked(ItemStack... items);

    /**
     * Get an array of different item stacks that are properly stacked to their max stack size
     *
     * @param item      The item to stack
     * @param amount    The amount of the item to stack
     * @return An array of item stacks which's amount is a maximum of the allowed stack size
     */
    ItemStack[] getItemStacked(ItemStack item, int amount);
}
