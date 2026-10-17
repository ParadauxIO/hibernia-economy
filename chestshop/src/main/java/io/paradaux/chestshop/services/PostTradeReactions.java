package io.paradaux.chestshop.services;

import io.paradaux.chestshop.model.Transaction;

/**
 * The ChestShop-internal MONITOR reactions to a settled trade — remove an emptied shop, write the
 * shop log (off-thread), and notify the buyer + owner — extracted from TransactionServiceImpl
 * (chestshop/structure/0001). The genuine cross-cutting reactions (stock counter, market sync,
 * metrics, legacy-sign migration) remain wired by the orchestrator.
 */
public interface PostTradeReactions {

    /** Remove a shop whose container ran dry after a buy (config-gated, never admin shops). */
    void deleteEmptyShop(Transaction event);

    /** Write a completed trade to the shop log. */
    void logTransaction(Transaction event);

    /** Notify the buyer and (unless they muted) the owner that a trade settled. */
    void sendTransactionMessages(Transaction event);
}
