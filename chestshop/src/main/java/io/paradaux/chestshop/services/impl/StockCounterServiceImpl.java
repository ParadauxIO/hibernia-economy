package io.paradaux.chestshop.services.impl;

import static io.paradaux.chestshop.services.SignService.QUANTITY_LINE;
import static io.paradaux.chestshop.utils.InventoryUtil.getHolder;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.chestshop.model.ShopCreation;
import io.paradaux.chestshop.model.Transaction;
import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import io.paradaux.chestshop.services.InventoryService;
import io.paradaux.chestshop.services.ItemService;
import io.paradaux.chestshop.services.MaterialService;
import io.paradaux.chestshop.services.ShopBlockService;
import io.paradaux.chestshop.services.SignService;
import io.paradaux.chestshop.services.StockCounterService;
import io.paradaux.chestshop.services.WildcardItems;
import io.paradaux.chestshop.utils.QuantityUtil;
import io.paradaux.chestshop.utils.SignText;
import java.util.IllegalFormatException;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.block.Container;
import org.bukkit.block.Sign;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

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
    public StockCounterServiceImpl(
            ItemService items,
            ChestShopConfiguration config,
            SignService signService,
            ShopBlockService shopBlockService,
            InventoryService inventoryService,
            MaterialService materialService) {
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

        String pattern = SignService.getItem(event.getSignLines());
        if (WildcardItems.isPattern(pattern)) {
            Container container = shopBlockService.findConnectedContainer(event.getSign());
            if (container != null)
                event.setSignLine(
                        QUANTITY_LINE,
                        String.format(
                                PRICE_LINE_WITH_COUNT, quantity, wildcardCount(pattern, container.getInventory())));
            return;
        }
        ItemStack itemTradedByShop = determineItemTradedByShop(pattern);
        if (itemTradedByShop != null) {
            Container container = shopBlockService.findConnectedContainer(event.getSign());
            if (container != null) {
                event.setSignLine(
                        QUANTITY_LINE,
                        getQuantityLineWithCounter(quantity, itemTradedByShop, container.getInventory()));
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

        // A chestless admin shop has no container to count (and no holder to find signs through).
        if (event.isUnlimitedOwner() || event.getOwnerInventory() == null) {
            return;
        }

        for (Sign shopSign : shopBlockService.findConnectedShopSigns(getHolder(event.getOwnerInventory(), false))) {
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
        String pattern = SignService.getItem(sign);
        boolean wildcard = WildcardItems.isPattern(pattern);
        ItemStack itemTradedByShop = wildcard ? null : determineItemTradedByShop(sign);
        if (!wildcard && itemTradedByShop == null) {
            return;
        }

        int quantity;
        try {
            quantity = SignService.getQuantity(sign);
        } catch (IllegalFormatException invalidQuantity) {
            return;
        }

        int numTradedItemsInChest = wildcard
                ? wildcardCount(pattern, chestShopInventory)
                : inventoryService.getAmount(itemTradedByShop, chestShopInventory);

        for (ItemStack extraStack : extraItems) {
            if (wildcard
                    ? !new WildcardItems(items).matches(pattern, extraStack)
                    : !materialService.equals(extraStack, itemTradedByShop)) {
                continue;
            }

            numTradedItemsInChest += extraStack.getAmount();
        }

        // Skip the forced block update when the counter text is unchanged. This
        // also applies to batched hopper refreshes: an attempted transfer need
        // not have changed stock, and unrelated item movements should not write signs.
        String counterLine = String.format(PRICE_LINE_WITH_COUNT, quantity, numTradedItemsInChest);
        if (counterLine.equals(SignText.getLine(sign, QUANTITY_LINE))) {
            return;
        }

        SignText.setLine(sign, QUANTITY_LINE, counterLine);
        sign.update(true);
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

    private int wildcardCount(String pattern, Inventory inventory) {
        return new WildcardItems(items).count(pattern, inventory);
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
