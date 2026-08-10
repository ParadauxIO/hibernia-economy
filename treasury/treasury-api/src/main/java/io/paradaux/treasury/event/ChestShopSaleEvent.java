package io.paradaux.treasury.event;

import io.paradaux.treasury.api.market.ChestShopSaleRecord;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired by Treasury immediately after a completed ChestShop trade is recorded
 * ({@code MarketApi.recordSale}). Consuming plugins (e.g. Business) listen for it
 * to drive real-time sale notifications without polling the DB.
 *
 * <p><b>Thread safety:</b> synchronous — fired on the main server thread from the
 * trade handler, so handlers must be fast (buffer, don't block). Best-effort: a
 * handler throwing never breaks sale recording (the caller is fail-soft).
 */
public class ChestShopSaleEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final ChestShopSaleRecord sale;

    public ChestShopSaleEvent(ChestShopSaleRecord sale) {
        super(false); // synchronous
        this.sale = sale;
    }

    /** The completed sale (shop owner classification, item, quantity, price, …). */
    public ChestShopSaleRecord getSale() {
        return sale;
    }

    // ---- Bukkit event boilerplate ----

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
