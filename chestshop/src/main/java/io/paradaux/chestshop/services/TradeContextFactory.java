package io.paradaux.chestshop.services;

import io.paradaux.chestshop.model.PendingTransaction;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;

/**
 * Builds the {@link PendingTransaction} for a shop interaction: resolve the owner account, price the
 * trade (honouring shift-sell-in-stacks / shift-sell-everything), and assemble the stacked items +
 * (virtual admin) shop inventory. Returns {@code null} — after messaging the player — when the click
 * can't become a trade. Extracted from TransactionServiceImpl (chestshop/structure/0001).
 */
public interface TradeContextFactory {

    /** Build the pending transaction for a click, or {@code null} (after messaging) if it can't trade. */
    PendingTransaction prepare(Sign sign, Player player, Action action);
}
