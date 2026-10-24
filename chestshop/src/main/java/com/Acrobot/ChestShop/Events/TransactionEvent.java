package com.Acrobot.ChestShop.Events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Compatibility event for the installed ChestShop 3/4 add-ons. */
public class TransactionEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    public enum TransactionType {
        BUY,
        SELL
    }

    private final io.paradaux.chestshop.model.Transaction transaction;

    public TransactionEvent(io.paradaux.chestshop.model.Transaction transaction) {
        this.transaction = transaction;
    }

    public org.bukkit.block.Sign getSign() {
        return transaction.getSign();
    }

    public org.bukkit.inventory.ItemStack[] getStock() {
        return transaction.getStock();
    }

    public org.bukkit.entity.Player getClient() {
        return transaction.getClient();
    }

    public TransactionType getTransactionType() {
        return TransactionType.valueOf(transaction.getTransactionType().name());
    }

    public double getPrice() {
        return transaction.getExactPrice().doubleValue();
    }
}
