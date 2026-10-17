package io.paradaux.chestshop.services;

import io.paradaux.chestshop.model.PendingTransaction;

/**
 * Resizes a partial fill: when the buyer/shop can't afford, stock, or hold the full amount, scale
 * the stock + price down to what actually fits (cancelling if nothing does). Extracted from
 * TransactionServiceImpl (PAR-317).
 */
public interface PartialFillCalculator {

    /** Scale a BUY down to what the buyer can afford / the shop can stock / the buyer can hold. */
    void adjustBuy(PendingTransaction ctx);

    /** Scale a SELL down to what the shop can afford / the buyer can stock / the shop can hold. */
    void adjustSell(PendingTransaction ctx);
}
