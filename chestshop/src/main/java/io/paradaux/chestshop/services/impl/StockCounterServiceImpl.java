package io.paradaux.chestshop.services.impl;

import io.paradaux.chestshop.utils.SignText;
import lombok.extern.slf4j.Slf4j;

import io.paradaux.chestshop.services.ShopBlockService;
import io.paradaux.chestshop.services.InventoryService;
import io.paradaux.chestshop.services.MaterialService;
import io.paradaux.chestshop.services.StockCounterService;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.chestshop.services.ItemService;
import io.paradaux.chestshop.utils.QuantityUtil;
import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import io.paradaux.chestshop.model.ShopCreation;
import io.paradaux.chestshop.model.Transaction;
import io.paradaux.chestshop.services.SignService;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.Sign;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.IllegalFormatException;

import static io.paradaux.chestshop.utils.InventoryUtil.getHolder;
import static io.paradaux.chestshop.services.SignService.QUANTITY_LINE;

/**
 * The shop-sign stock-counter business logic, extracted from {@code StockCounterListener}
 * (chestshop/structure/0002). The {@code onInventoryClose} Bukkit handler stays in the listener;
 * this service owns the counter maths shared with {@code ShopService}/{@code TransactionService}
 * and the {@code ItemMoveListener}.
 *
 * @author bricefrisco
 */
@Singleton
@Slf4j
public class StockCounterServiceImpl implements StockCounterService {
    private static final String PRICE_LINE_WITH_COUNT = "Q %d : C %d";

    private final ItemService items;
    private final ChestShopConfiguration config;
    private final SignService signService;
    private final ShopBlockService shopBlockService;
    private final InventoryService inventoryService;
    private final MaterialService materialService;

    @Inject
    public StockCounterServiceImpl(ItemService items, ChestShopConfiguration config, SignService signService,
                                   ShopBlockService shopBlockService, InventoryService inventoryService, MaterialService materialService) {
        this.items = items;
        this.config = config;
        this.signService = signService;
        this.shopBlockService = shopBlockService;
        this.inventoryService = inventoryService;
        this.materialService = materialService;
    }

    // Invoked directly by ShopService#create (was a @HIGH ShopCreation listener).
    @Override
    public void onPreShopCreation(ShopCreation event) {
        int quantity;
        try {
            quantity = SignService.getQuantity(event.getSignLines());
        } catch (IllegalArgumentException invalidQuantity) {
            return;
        }

        if (QuantityUtil.quantityLineContainsCounter(SignService.getQuantityLine(event.getSignLines()))) {
            event.setSignLine(QUANTITY_LINE, Integer.toString(quantity));
        }

        if (!config.isUseStockCounter()
                || (config.isForceUnlimitedAdminShop() && signService.isAdminShop(event.getSignLines()))) {
            return;
        }

        if (config.getMaxShopAmount() > 99999) {
            log.warn("Stock counter cannot be used if MAX_SHOP_AMOUNT is over 5 digits");
            return;
        }

        ItemStack itemTradedByShop = determineItemTradedByShop(SignService.getItem(event.getSignLines()));
        if (itemTradedByShop != null) {
            Container container = shopBlockService.findConnectedContainer(event.getSign());
            if (container != null) {
                event.setSignLine(QUANTITY_LINE, getQuantityLineWithCounter(quantity, itemTradedByShop, container.getInventory()));
            }
        }
    }

    // Invoked directly by TransactionService#process (was a @HIGH Transaction listener).
    @Override
    public void onTransaction(final Transaction event) {
        String quantityLine = SignService.getQuantityLine(event.getSign());
        if (!config.isUseStockCounter()) {
            if (QuantityUtil.quantityLineContainsCounter(quantityLine)) {
                removeCounterFromQuantityLine(event.getSign());
            }
            return;
        }

        if (config.getMaxShopAmount() > 99999) {
            log.warn("Stock counter cannot be used if MAX_SHOP_AMOUNT is over 5 digits");
            if (QuantityUtil.quantityLineContainsCounter(quantityLine)) {
                removeCounterFromQuantityLine(event.getSign());
            }
            return;
        }

        if (config.isForceUnlimitedAdminShop() && signService.isAdminShop(event.getSign())) {
            return;
        }

        for (Sign shopSign : shopBlockService.findConnectedShopSigns( getHolder(event.getOwnerInventory(), false))) {
            updateCounterOnQuantityLine(shopSign, event.getOwnerInventory());
        }
    }

    /**
     * Update the stock counter on the sign's quantity line
     * @param sign               The sign to update
     * @param chestShopInventory The inventory to search in
     * @param extraItems         The extra items to add in the search
     */
    @Override
    public void updateCounterOnQuantityLine(Sign sign, Inventory chestShopInventory, ItemStack... extraItems) {
        ItemStack itemTradedByShop = determineItemTradedByShop(sign);
        if (itemTradedByShop == null) {
            return;
        }

        int quantity;
        try {
            quantity = SignService.getQuantity(sign);
        } catch (IllegalFormatException invalidQuantity) {
            return;
        }

        int numTradedItemsInChest = inventoryService.getAmount(itemTradedByShop, chestShopInventory);

        for (ItemStack extraStack : extraItems) {
            if (!materialService.equals(extraStack, itemTradedByShop)) {
                continue;
            }

            numTradedItemsInChest += extraStack.getAmount();
        }

        // Skip the forced block update when the counter text is unchanged. This
        // runs per InventoryMoveItemEvent (every hopper tick into a shop), so
        // avoiding a no-op sign.update(true) keeps busy hopper setups off the
        // main-thread hot path.
        String counterLine = String.format(PRICE_LINE_WITH_COUNT, quantity, numTradedItemsInChest);
        if (counterLine.equals(SignText.getLine(sign, QUANTITY_LINE))) {
            return;
        }

        SignText.setLine(sign, QUANTITY_LINE, counterLine);
        sign.update(true);
    }

    @Override
    public void updateCounterOnItemMoveEvent(ItemStack toAdd, InventoryHolder destinationHolder) {
        Block shopBlock = signService.getShopBlock(destinationHolder);
        Sign connectedSign = shopBlockService.getConnectedSign(shopBlock);

        updateCounterOnQuantityLine(connectedSign, destinationHolder.getInventory(), toAdd);
    }

    @Override
    public void removeCounterFromQuantityLine(Sign sign) {
        int quantity;
        try {
            quantity = SignService.getQuantity(sign);
        } catch (IllegalFormatException invalidQuantity) {
            return;
        }

        SignText.setLine(sign, QUANTITY_LINE, Integer.toString(quantity));
        sign.update(true);
    }

    @Override
    public String getQuantityLineWithCounter(int amount, ItemStack itemTransacted, Inventory chestShopInventory) {
        int numTransactionItemsInChest = inventoryService.getAmount(itemTransacted, chestShopInventory);

        return String.format(PRICE_LINE_WITH_COUNT, amount, numTransactionItemsInChest);
    }

    @Override
    public ItemStack determineItemTradedByShop(Sign sign) {
        return determineItemTradedByShop(SignService.getItem(sign));
    }

    @Override
    public ItemStack determineItemTradedByShop(String material) {
        return items.parse(material);
    }
}
