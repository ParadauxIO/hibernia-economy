package io.paradaux.chestshop.services.impl;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.chestshop.model.Transaction;
import io.paradaux.chestshop.services.EconomyService;
import io.paradaux.chestshop.services.GoodsTransfer;
import io.paradaux.chestshop.services.TradeSettlement;
import lombok.extern.slf4j.Slf4j;

import static io.paradaux.chestshop.model.Transaction.TransactionType.BUY;

/**
 * The all-or-nothing settlement of a validated trade: move the goods, then settle the single
 * buyer→seller money leg — reversing the goods if the (pre-validated, so exceptional) money leg
 * fails, so the trade is atomic. Extracted from TransactionServiceImpl (chestshop/structure/0001);
 * the goods mechanics live in {@link GoodsTransfer}.
 */
@Singleton
@Slf4j
public class TradeSettlementImpl implements TradeSettlement {

    private final EconomyService economy;
    private final GoodsTransfer goodsTransfer;

    @Inject
    TradeSettlementImpl(EconomyService economy, GoodsTransfer goodsTransfer) {
        this.economy = economy;
        this.goodsTransfer = goodsTransfer;
    }

    @Override
    public void execute(Transaction event) {
        boolean buy = event.getTransactionType() == BUY;

        // An unlimited admin shop has no owner container: a buy spawns the stock into the
        // client, a sell vanishes it from the client. Otherwise move between the two real
        // inventories (owner chest <-> client).
        boolean moved = event.isUnlimitedOwner()
                ? goodsTransfer.moveUnlimited(event.getClientInventory(), event.getStock(), buy)
                : goodsTransfer.transfer(
                        buy ? event.getOwnerInventory() : event.getClientInventory(),
                        buy ? event.getClientInventory() : event.getOwnerInventory(),
                        event.getStock());
        if (!moved) {
            cancelOnShortfall(event);
            return;
        }

        if (!economy.settle(event.getExactPrice(), event.getClient(),
                event.getOwnerAccount().getUuid(), event)) {
            // The goods already moved but the money didn't settle — put the goods
            // back and cancel, keeping the trade atomic.
            goodsTransfer.reverse(event);
            event.setCancelled(true);
        }
    }

    private static void cancelOnShortfall(Transaction event) {
        event.setCancelled(true);
        log.error(
                "Aborted a ChestShop transaction at "
                + (event.getSign() != null ? event.getSign().getLocation() : "<unknown location>")
                + ": the goods could not be transferred in full, so no money was moved and both "
                + "inventories were left untouched. This should not normally happen — the "
                + "PreTransaction checks validate stock and space beforehand.");
    }
}
