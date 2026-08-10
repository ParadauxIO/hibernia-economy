package io.paradaux.chestshop.services;

import io.paradaux.chestshop.model.Transaction;

/**
 * Rolling buy/sell transaction + item counters surfaced by the {@code /csmetrics} command.
 * Formerly a {@code @MONITOR} {@link Transaction} listener; {@link #onTransaction} is now
 * invoked directly by {@link TransactionService#process}. Was the static {@code MetricsModule}
 * (PAR-316).
 */
public interface MetricsService {

    /** Record a completed trade into the rolling counters. */
    void onTransaction(Transaction event);

    int getBuyTransactions();

    int getSellTransactions();

    int getTotalTransactions();

    int getBoughtItemsCount();

    int getSoldItemsCount();

    int getTotalItemsCount();
}
