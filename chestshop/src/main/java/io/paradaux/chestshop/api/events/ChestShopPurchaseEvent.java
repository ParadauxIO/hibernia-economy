package io.paradaux.chestshop.api.events;

import java.util.Objects;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/**
 * Synchronous, non-cancellable notification after goods and money settlement succeeds.
 * Never emitted for SELL or cancelled/failed settlement. Consumers cannot roll back the trade.
 * A trade UUID identifies one transaction object, not a durable outbox or delivery guarantee.
 */
public final class ChestShopPurchaseEvent extends PlayerEvent {
    private static final HandlerList HANDLERS = new HandlerList();
    private final PurchaseReceipt receipt;

    public ChestShopPurchaseEvent(Player player, PurchaseReceipt receipt) {
        super(player);
        this.receipt = Objects.requireNonNull(receipt, "receipt");
        if (!player.getUniqueId().equals(receipt.buyerId())) throw new IllegalArgumentException("Buyer mismatch");
    }

    public PurchaseReceipt getReceipt() {
        return receipt;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
