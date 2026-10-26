package com.Acrobot.ChestShop.Events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Compatibility event for the installed ChestShop 3/4 add-ons. */
public class PreTransactionEvent extends Event implements org.bukkit.event.Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    public enum TransactionOutcome {
        TRANSACTION_SUCCESSFUL,
        CLIENT_DOES_NOT_HAVE_PERMISSION
    }

    private final io.paradaux.chestshop.model.PendingTransaction transaction;

    public PreTransactionEvent(io.paradaux.chestshop.model.PendingTransaction transaction) {
        this.transaction = transaction;
    }

    public org.bukkit.entity.Player getClient() {
        return transaction.getClient();
    }

    public org.bukkit.block.Sign getSign() {
        return transaction.getSign();
    }

    public org.bukkit.inventory.ItemStack[] getStock() {
        return transaction.getStock();
    }

    public org.bukkit.inventory.Inventory getClientInventory() {
        return transaction.getClientInventory();
    }

    public org.bukkit.inventory.Inventory getOwnerInventory() {
        return transaction.getOwnerInventory();
    }

    public TransactionEvent.TransactionType getTransactionType() {
        return TransactionEvent.TransactionType.valueOf(
                transaction.getTransactionType().name());
    }

    @Override
    public boolean isCancelled() {
        return transaction.isCancelled();
    }

    @Override
    public void setCancelled(boolean cancelled) {
        if (cancelled)
            transaction.setCancelled(
                    io.paradaux.chestshop.model.PendingTransaction.TransactionOutcome.CLIENT_DOES_NOT_HAVE_PERMISSION);
    }

    public void setCancelled(TransactionOutcome outcome) {
        setCancelled(outcome != TransactionOutcome.TRANSACTION_SUCCESSFUL);
    }
}
