package io.paradaux.chestshop.listeners;

import static io.paradaux.chestshop.utils.InventoryUtil.getHolder;

import com.google.inject.Inject;
import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import io.paradaux.chestshop.services.HopperStockRefreshService;
import io.paradaux.chestshop.services.ShopBlockService;
import org.bukkit.block.BlockState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.InventoryHolder;

/**
 * @author Acrobot
 */
public class ItemMoveListener implements Listener {

    private final HopperStockRefreshService stockRefresh;
    private final ChestShopConfiguration config;
    private final ShopBlockService shopBlockService;

    @Inject
    public ItemMoveListener(
            HopperStockRefreshService stockRefresh, ChestShopConfiguration config, ShopBlockService shopBlockService) {
        this.stockRefresh = stockRefresh;
        this.config = config;
        this.shopBlockService = shopBlockService;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onItemMove(InventoryMoveItemEvent event) {
        if (config.isTurnOffHopperProtection()) return;
        InventoryHolder destinationHolder = getHolder(event.getDestination(), false);

        if (!config.isTurnOffHopperProtection() && !(destinationHolder instanceof BlockState)) {
            InventoryHolder sourceHolder = getHolder(event.getSource(), false);
            if (shopBlockService.isShopBlock(sourceHolder)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onStockMove(InventoryMoveItemEvent event) {
        stockRefresh.markDirty(event.getSource());
        stockRefresh.markDirty(event.getDestination());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        stockRefresh.reconcileChunk(
                event.getWorld().getUID(),
                event.getChunk().getX(),
                event.getChunk().getZ());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        stockRefresh.unloadChunk(
                event.getWorld().getUID(),
                event.getChunk().getX(),
                event.getChunk().getZ());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onWorldUnload(WorldUnloadEvent event) {
        stockRefresh.unloadWorld(event.getWorld().getUID());
    }
}
