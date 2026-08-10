package io.paradaux.chestshop.listeners;

import io.paradaux.chestshop.services.MarketSyncService;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.InventoryHolder;

/**
 * Bukkit entrypoint that recounts market stock when a shop container is closed (a manual restock).
 * The market-sync business logic lives in {@link MarketSyncService} (chestshop/structure/0002); this
 * class stays a thin listener — the only remaining Bukkit handler after the trade/create/destroy
 * hooks moved to the service, which the trade and shop pipelines invoke directly.
 */
@Singleton
public class MarketListener implements Listener {

    private final MarketSyncService marketSync;

    @Inject
    public MarketListener(MarketSyncService marketSync) {
        this.marketSync = marketSync;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        marketSync.onInventoryClose(holder, event.getInventory());
    }
}
