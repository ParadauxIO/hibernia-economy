package io.paradaux.chestshop.services;
import io.paradaux.chestshop.model.ItemInfoLines;

import org.bukkit.block.Sign;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Owns the read-only {@code /shopinfo} and {@code /iteminfo} output. Replaces the old
 * {@code ShopInfoEvent} / {@code ItemInfoEvent} carriers and the three contributor
 * "listener" classes (ShopInfoListener, ItemInfoListener, ExtendedItemInfoListener)
 * with direct, ordered service methods — the entrypoint→service shape the rest of the
 * monorepo uses (PAR-282).
 *
 * <p>{@link #collectItemInfo} runs the contributors in their exact former firing order:
 * the basic info lines first, then the richer type-specific lines; the {@code map}/
 * {@code potion} "extended" lines intentionally override the basic versions by reusing
 * the same message key (the accumulating {@link ItemInfoLines} keeps the original line
 * position).
 *
 * @author Acrobot
 */
public interface InfoService {

    /** Render the {@code /shopinfo} (or middle-click) output for a shop sign. */
    void showShopInfo(Player sender, Sign sign);

    /** Accumulate the {@code /iteminfo} lines for an item, in the former contributor order. */
    ItemInfoLines collectItemInfo(CommandSender sender, ItemStack item);

    /**
     * Send an item's name to the player, as a plain message
     * and configured, else a plain {@code messages.properties} line. Shared by the
     * {@code /iteminfo} command and the cross-bow projectile contributor.
     *
     * @return false if the name could not be generated (the caller should abort).
     */
    boolean sendItemName(CommandSender sender, ItemStack item, String messageKey);
}
