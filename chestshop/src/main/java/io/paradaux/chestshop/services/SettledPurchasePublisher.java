package io.paradaux.chestshop.services;

import io.paradaux.chestshop.api.events.ChestShopPurchaseEvent;
import io.paradaux.chestshop.api.events.PurchaseReceipt;
import io.paradaux.chestshop.model.Transaction;
import java.util.Arrays;
import org.bukkit.Bukkit;

/** Dedicated observer boundary; the financial core has no quest-engine dependency. */
public class SettledPurchasePublisher {
    public void publish(Transaction trade) {
        if (trade.isCancelled() || trade.getTransactionType() != Transaction.TransactionType.BUY) return;
        var items = Arrays.stream(trade.getStock())
                .map(item -> new PurchaseReceipt.PurchasedItem(item.getType(), item.getAmount()))
                .toList();
        var receipt =
                new PurchaseReceipt(trade.getTradeId(), trade.getClient().getUniqueId(), trade.getExactPrice(), items);
        Bukkit.getPluginManager().callEvent(new ChestShopPurchaseEvent(trade.getClient(), receipt));
    }
}
