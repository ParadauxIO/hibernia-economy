package io.paradaux.business.listeners;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.business.services.FirmSalesNotificationService;
import io.paradaux.treasury.event.ChestShopSaleEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/**
 * Buffers Treasury's {@link ChestShopSaleEvent} into the firm sale-notification
 * digest (PAR-179). Fires on the main thread; recording is in-memory and fast,
 * so it never blocks the trade that produced the event.
 */
@Singleton
public class ChestShopSaleListener implements Listener {

    private final FirmSalesNotificationService salesNotifications;

    @Inject
    public ChestShopSaleListener(FirmSalesNotificationService salesNotifications) {
        this.salesNotifications = salesNotifications;
    }

    @EventHandler
    public void onSale(ChestShopSaleEvent event) {
        salesNotifications.record(event.getSale());
    }
}
