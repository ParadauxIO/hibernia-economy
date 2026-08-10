package io.paradaux.chestshop.listeners;

import io.paradaux.chestshop.services.ShopBlockService;
import io.paradaux.chestshop.services.StockCounterService;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.chestshop.utils.QuantityUtil;
import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import io.paradaux.chestshop.services.SignService;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.block.Sign;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.InventoryHolder;

import static io.paradaux.chestshop.utils.InventoryUtil.getHolder;

/**
 * Bukkit entrypoint that refreshes shop-sign stock counters when a container is closed. The counter
 * maths lives in {@link StockCounterService} (chestshop/structure/0002); this class stays a thin
 * listener.
 *
 * @author bricefrisco
 */
@Singleton
@Slf4j
public class StockCounterListener implements Listener {

    private final ChestShopConfiguration config;
    private final SignService signService;
    private final ShopBlockService shopBlockService;
    private final StockCounterService stockCounter;

    @Inject
    public StockCounterListener(ChestShopConfiguration config, SignService signService,
                                ShopBlockService shopBlockService, StockCounterService stockCounter) {
        this.config = config;
        this.signService = signService;
        this.shopBlockService = shopBlockService;
        this.stockCounter = stockCounter;
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getInventory().getType() == InventoryType.ENDER_CHEST || event.getInventory().getLocation() == null) {
            return;
        }

        InventoryHolder holder = getHolder(event.getInventory(), false);
        if (!shopBlockService.couldBeShopContainer(holder)) {
            return;
        }

        for (Sign shopSign : shopBlockService.findConnectedShopSigns(holder)) {
            if (!config.isUseStockCounter()
                    || (config.isForceUnlimitedAdminShop() && signService.isAdminShop(shopSign))) {
                if (QuantityUtil.quantityLineContainsCounter(SignService.getQuantityLine(shopSign))) {
                    stockCounter.removeCounterFromQuantityLine(shopSign);
                }
                continue;
            }

            if (config.getMaxShopAmount() > 99999) {
                log.warn("Stock counter cannot be used if MAX_SHOP_AMOUNT is over 5 digits");
                if (QuantityUtil.quantityLineContainsCounter(SignService.getQuantityLine(shopSign))) {
                    stockCounter.removeCounterFromQuantityLine(shopSign);
                }
                return;
            }

            stockCounter.updateCounterOnQuantityLine(shopSign, event.getInventory());
        }
    }
}
