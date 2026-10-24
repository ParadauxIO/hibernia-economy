package io.paradaux.chestshop.services;

import io.paradaux.chestshop.model.Transaction;

/**
 * The all-or-nothing settlement of a validated trade: move the goods, then settle the single
 * buyer→seller money leg — reversing the goods if the (pre-validated, so exceptional) money leg
 * fails, so the trade is atomic. Extracted from TransactionServiceImpl (chestshop/structure/0001);
 * the goods mechanics live in {@link GoodsTransfer}.
 */
public interface TradeSettlement {

    /** Move the goods then settle the money leg, reversing the goods (and cancelling) if it fails. */
    void execute(Transaction event);
}
