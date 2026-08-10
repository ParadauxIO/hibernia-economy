package io.paradaux.chestshop.services.impl;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.chestshop.model.Account;
import io.paradaux.chestshop.model.PendingTransaction;
import io.paradaux.chestshop.model.Transaction.TransactionType;
import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import io.paradaux.chestshop.services.AccountService;
import io.paradaux.chestshop.services.EconomyService;
import io.paradaux.chestshop.services.InventoryService;
import io.paradaux.chestshop.services.ItemService;
import io.paradaux.chestshop.services.ShopBlockService;
import io.paradaux.chestshop.services.SignService;
import io.paradaux.chestshop.services.TradeContextFactory;
import io.paradaux.chestshop.utils.PriceUtil;
import io.paradaux.hibernia.framework.i18n.Message;
import org.bukkit.block.Container;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

import static org.bukkit.event.block.Action.LEFT_CLICK_BLOCK;
import static org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK;
import static io.paradaux.chestshop.model.Transaction.TransactionType.BUY;
import static io.paradaux.chestshop.model.Transaction.TransactionType.SELL;

/**
 * Builds the {@link PendingTransaction} for a shop interaction: resolve the owner account, price the
 * trade (honouring shift-sell-in-stacks / shift-sell-everything), and assemble the stacked items +
 * (virtual admin) shop inventory. Returns {@code null} — after messaging the player — when the click
 * can't become a trade. Extracted from TransactionServiceImpl (chestshop/structure/0001).
 */
@Singleton
public class TradeContextFactoryImpl implements TradeContextFactory {

    private final EconomyService economy;
    private final AccountService accounts;
    private final Message message;
    private final ItemService items;
    private final ChestShopConfiguration config;
    private final SignService signService;
    private final ShopBlockService shopBlockService;
    private final InventoryService inventoryService;

    @Inject
    TradeContextFactoryImpl(EconomyService economy, AccountService accounts, Message message, ItemService items,
                        ChestShopConfiguration config, SignService signService, ShopBlockService shopBlockService,
                        InventoryService inventoryService) {
        this.economy = economy;
        this.accounts = accounts;
        this.message = message;
        this.items = items;
        this.config = config;
        this.signService = signService;
        this.shopBlockService = shopBlockService;
        this.inventoryService = inventoryService;
    }

    @Override
    public PendingTransaction prepare(Sign sign, Player player, Action action) {
        String name = SignService.getOwner(sign);
        String prices = SignService.getPrice(sign);
        String material = SignService.getItem(sign);

        Account account = accounts.resolveAccount(name);
        if (account == null) {
            message.send(player, "chestshop.PLAYER_NOT_FOUND");
            return null;
        }

        boolean adminShop = signService.isAdminShop(sign);

        // check if player exists in economy
        if (!adminShop && !economy.hasAccount(account.getUuid())) {
            message.send(player, "chestshop.NO_ECONOMY_ACCOUNT");
            return null;
        }

        Action buy = config.isReverseButtons() ? LEFT_CLICK_BLOCK : RIGHT_CLICK_BLOCK;
        BigDecimal price = (action == buy ? PriceUtil.getExactBuyPrice(prices) : PriceUtil.getExactSellPrice(prices));

        Container shopBlock = shopBlockService.findConnectedContainer(sign);
        Inventory ownerInventory = shopBlock != null ? shopBlock.getInventory() : null;

        ItemStack item = items.parse(material);
        if (item == null) {
            message.send(player, "chestshop.INVALID_SHOP_DETECTED");
            return null;
        }

        int amount = -1;
        try {
            amount = SignService.getQuantity(sign);
        } catch (NumberFormatException ignored) {} // There is no quantity number on the sign

        if (amount < 1 || amount > config.getMaxShopAmount()) {
            message.send(player, "chestshop.INVALID_SHOP_PRICE");
            return null;
        }

        BigDecimal pricePerItem = price.divide(BigDecimal.valueOf(amount), MathContext.DECIMAL128);
        if (config.isShiftSellsInStacks() && player.isSneaking() && !price.equals(PriceUtil.NO_PRICE) && isAllowedForShift(action == buy)) {
            int newAmount = adminShop ? inventoryService.getMaxStackSize(item) : getStackAmount(item, ownerInventory, player, action);
            if (newAmount > 0) {
                price = pricePerItem.multiply(BigDecimal.valueOf(newAmount)).setScale(config.getPricePrecision(), RoundingMode.HALF_UP);
                amount = newAmount;
            }
        } else if (config.isShiftSellsEverything() && player.isSneaking() && !price.equals(PriceUtil.NO_PRICE) && isAllowedForShift(action == buy)) {
            if (action != buy) {
                int newAmount = inventoryService.getAmount(item, player.getInventory());
                if (newAmount > 0) {
                    price = pricePerItem.multiply(BigDecimal.valueOf(newAmount)).setScale(config.getPricePrecision(), RoundingMode.HALF_UP);
                    amount = newAmount;
                }
            } else if (!adminShop && ownerInventory != null) {
                int newAmount = inventoryService.getAmount(item, ownerInventory);
                if (newAmount > 0) {
                    price = pricePerItem.multiply(BigDecimal.valueOf(newAmount)).setScale(config.getPricePrecision(), RoundingMode.HALF_UP);
                    amount = newAmount;
                }
            }
        }

        item.setAmount(amount);

        ItemStack[] stackedItems = inventoryService.getItemsStacked(item);

        // An unlimited admin shop has no owner container — infinite stock and space — when it's
        // an admin shop and either there's no chest or the config forces unlimited stock. The
        // trade skips the owner side entirely (no fake inventory): buys spawn items for the
        // client, sells vanish them, money routes via the server account.
        boolean unlimitedOwner = adminShop && (ownerInventory == null || config.isForceUnlimitedAdminShop());

        TransactionType transactionType = (action == buy ? BUY : SELL);
        return new PendingTransaction(ownerInventory, player.getInventory(), stackedItems, price, player, account, sign, transactionType, unlimitedOwner);
    }

    private boolean isAllowedForShift(boolean buyTransaction) {
        String allowed = config.getShiftAllows();

        if (allowed.equalsIgnoreCase("ALL")) {
            return true;
        }

        return allowed.equalsIgnoreCase(buyTransaction ? "BUY" : "SELL");
    }

    private int getStackAmount(ItemStack item, Inventory inventory, Player player, Action action) {
        Action buy = config.isReverseButtons() ? LEFT_CLICK_BLOCK : RIGHT_CLICK_BLOCK;
        Inventory checkedInventory = (action == buy ? inventory : player.getInventory());

        if (checkedInventory.containsAtLeast(item, inventoryService.getMaxStackSize(item))) {
            return inventoryService.getMaxStackSize(item);
        } else {
            return inventoryService.getAmount(item, checkedInventory);
        }
    }
}
