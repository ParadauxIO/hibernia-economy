package io.paradaux.chestshop.listeners;

import com.google.inject.Inject;
import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import io.paradaux.chestshop.services.ShopBlockService;
import io.paradaux.chestshop.services.StockCounterService;
import org.bukkit.block.BlockState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.InventoryHolder;

import static io.paradaux.chestshop.utils.InventoryUtil.getHolder;

/**
 * @author Acrobot
 */
public class ItemMoveListener implements Listener {

    private final StockCounterService stockCounter;
    private final ChestShopConfiguration config;
    private final ShopBlockService shopBlockService;

    @Inject
    public ItemMoveListener(StockCounterService stockCounter, ChestShopConfiguration config, ShopBlockService shopBlockService) {
        this.stockCounter = stockCounter;
        this.config = config;
        this.shopBlockService = shopBlockService;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onItemMove(InventoryMoveItemEvent event) {
        InventoryHolder destinationHolder = getHolder(event.getDestination(), false);

        if (!config.isTurnOffHopperProtection() && !(destinationHolder instanceof BlockState)) {
            InventoryHolder sourceHolder = getHolder(event.getSource(), false);
            if (shopBlockService.isShopBlock(sourceHolder)) {
                event.setCancelled(true);
                return;
            }
        }
        if (config.isUseStockCounter() && shopBlockService.isShopBlock(destinationHolder)) {
            stockCounter.updateCounterOnItemMoveEvent(event.getItem(), destinationHolder);
        }
    }


}
