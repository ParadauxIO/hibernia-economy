package io.paradaux.chestshop.services;

import io.paradaux.chestshop.model.PendingTransaction;

import java.util.UUID;

/**
 * Runs a shop interaction through the ordered pre-trade validation steps, mutating the
 * {@link PendingTransaction} (cancelling it, or adjusting its stock/price) as needed, then messages
 * the client (and, for full/out-of-stock, the owner) why a trade was cancelled. Extracted from
 * TransactionServiceImpl (chestshop/structure/0001). Partial fills are resized by
 * {@link PartialFillCalculator}.
 */
public interface TradeValidator {

    /** Run the ordered pre-trade validation steps, mutating and (on failure) messaging {@code ctx}. */
    void validate(PendingTransaction ctx);

    /** Drop a player's notification-cooldown rows (called from PlayerConnectListener on quit). */
    void clearNotificationCooldowns(UUID playerUuid);
}
