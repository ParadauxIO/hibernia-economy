package io.paradaux.chestshop.services;

import org.bukkit.inventory.ItemStack;

/**
 * The give-item business logic behind {@code /chestshop give}: resolve a ChestShop item
 * code into a ready-to-hand-over {@link ItemStack} of the requested quantity (PAR-323).
 *
 * <p>Extracted off the command handler so the code-parse + amount-set core is unit-testable
 * without a live command pipeline. The handler owns only argument typing, inventory delivery
 * and player-facing messaging; this owns "what stack does this code and quantity produce?".
 */
public interface GiveService {

    /**
     * Resolve an item code into the stack to give, with {@code quantity} applied.
     *
     * @param itemCode the ChestShop item code (Nexo / alias / vanilla material)
     * @param quantity the amount to set on the resolved stack
     * @return the ready-to-give stack, or {@code null} if the code resolves to no valid item
     */
    ItemStack resolveGift(String itemCode, int quantity);
}
