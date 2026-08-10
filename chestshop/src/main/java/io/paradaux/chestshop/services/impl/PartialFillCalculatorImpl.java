package io.paradaux.chestshop.services.impl;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.chestshop.model.PendingTransaction;
import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import io.paradaux.chestshop.services.EconomyService;
import io.paradaux.chestshop.services.PartialFillCalculator;
import io.paradaux.chestshop.services.InventoryService;
import io.paradaux.chestshop.services.MaterialService;
import io.paradaux.chestshop.utils.InventoryUtil;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.paradaux.chestshop.model.PendingTransaction.TransactionOutcome.CLIENT_DOES_NOT_HAVE_ENOUGH_MONEY;
import static io.paradaux.chestshop.model.PendingTransaction.TransactionOutcome.NOT_ENOUGH_SPACE_IN_CHEST;
import static io.paradaux.chestshop.model.PendingTransaction.TransactionOutcome.NOT_ENOUGH_SPACE_IN_INVENTORY;
import static io.paradaux.chestshop.model.PendingTransaction.TransactionOutcome.NOT_ENOUGH_STOCK_IN_CHEST;
import static io.paradaux.chestshop.model.PendingTransaction.TransactionOutcome.NOT_ENOUGH_STOCK_IN_INVENTORY;
import static io.paradaux.chestshop.model.PendingTransaction.TransactionOutcome.SHOP_DOES_NOT_HAVE_ENOUGH_MONEY;
import static io.paradaux.chestshop.model.Transaction.TransactionType.BUY;
import static io.paradaux.chestshop.model.Transaction.TransactionType.SELL;

/**
 * Resizes a partial fill: when the buyer/shop can't afford, stock, or hold the full amount, scale
 * the stock + price down to what actually fits (cancelling if nothing does). The money math is the
 * exact spot a scale/rounding/off-by-one slip would leak or destroy currency, so
 * {@link #scalePrice}/{@link #roundedToZero}/{@link #getAmountOfAffordableItems} are package-private
 * and unit-tested directly (ADT-138). Extracted from TransactionServiceImpl (PAR-317).
 */
@Singleton
public class PartialFillCalculatorImpl implements PartialFillCalculator {

    private final EconomyService economy;
    private final InventoryService inventoryService;
    private final MaterialService materialService;
    private final ChestShopConfiguration config;

    @Inject
    PartialFillCalculatorImpl(EconomyService economy, InventoryService inventoryService,
                          MaterialService materialService, ChestShopConfiguration config) {
        this.economy = economy;
        this.inventoryService = inventoryService;
        this.materialService = materialService;
        this.config = config;
    }

    @Override
    public void adjustBuy(PendingTransaction ctx) {
        if (ctx.isCancelled() || ctx.getTransactionType() != BUY) {
            return;
        }
        int itemCount = InventoryUtil.countItems(ctx.getStock());
        if (itemCount <= 0) {
            return;
        }
        Player client = ctx.getClient();
        BigDecimal pricePerItem = ctx.getExactPrice().divide(BigDecimal.valueOf(itemCount), MathContext.DECIMAL128);
        BigDecimal walletMoney = economy.getBalance(client.getUniqueId());

        if (!economy.hasFunds(client.getUniqueId(), ctx.getExactPrice())) {
            int amountAffordable = getAmountOfAffordableItems(walletMoney, pricePerItem, itemCount);
            if (amountAffordable < 1) {
                ctx.setCancelled(CLIENT_DOES_NOT_HAVE_ENOUGH_MONEY);
                return;
            }
            BigDecimal scaled = scalePrice(pricePerItem, amountAffordable);
            if (roundedToZero(pricePerItem, scaled)) {
                ctx.setCancelled(CLIENT_DOES_NOT_HAVE_ENOUGH_MONEY);
                return;
            }
            ctx.setExactPrice(scaled);
            ctx.setStock(getCountedItemStack(ctx.getStock(), amountAffordable));
        }

        if (!ctx.isUnlimitedOwner() && !inventoryService.hasItems(ctx.getStock(), ctx.getOwnerInventory())) {
            ItemStack[] itemsHad = getItems(ctx.getStock(), ctx.getOwnerInventory());
            int possessed = InventoryUtil.countItems(itemsHad);
            if (possessed <= 0) {
                ctx.setCancelled(NOT_ENOUGH_STOCK_IN_CHEST);
                return;
            }
            BigDecimal scaled = scalePrice(pricePerItem, possessed);
            if (roundedToZero(pricePerItem, scaled)) {
                ctx.setCancelled(NOT_ENOUGH_STOCK_IN_CHEST);
                return;
            }
            ctx.setExactPrice(scaled);
            ctx.setStock(itemsHad);
        }

        if (!inventoryService.fits(ctx.getStock(), ctx.getClientInventory())) {
            ItemStack[] itemsFit = getItemsThatFit(ctx.getStock(), ctx.getClientInventory());
            int possessed = InventoryUtil.countItems(itemsFit);
            if (possessed <= 0) {
                ctx.setCancelled(NOT_ENOUGH_SPACE_IN_INVENTORY);
                return;
            }
            BigDecimal scaled = scalePrice(pricePerItem, possessed);
            if (roundedToZero(pricePerItem, scaled)) {
                ctx.setCancelled(NOT_ENOUGH_SPACE_IN_INVENTORY);
                return;
            }
            ctx.setExactPrice(scaled);
            ctx.setStock(itemsFit);
        }
    }

    @Override
    public void adjustSell(PendingTransaction ctx) {
        if (ctx.isCancelled() || ctx.getTransactionType() != SELL) {
            return;
        }
        int itemCount = InventoryUtil.countItems(ctx.getStock());
        if (itemCount <= 0) {
            return;
        }
        UUID owner = ctx.getOwnerAccount().getUuid();
        BigDecimal pricePerItem = ctx.getExactPrice().divide(BigDecimal.valueOf(itemCount), MathContext.DECIMAL128);

        if (economy.isOwnerEconomicallyActive(ctx.isUnlimitedOwner())
                && !economy.hasFunds(owner, ctx.getExactPrice())) {
            BigDecimal walletMoney = economy.getBalance(owner);
            int amountAffordable = getAmountOfAffordableItems(walletMoney, pricePerItem, itemCount);
            if (amountAffordable < 1) {
                ctx.setCancelled(SHOP_DOES_NOT_HAVE_ENOUGH_MONEY);
                return;
            }
            BigDecimal scaled = scalePrice(pricePerItem, amountAffordable);
            if (roundedToZero(pricePerItem, scaled)) {
                ctx.setCancelled(SHOP_DOES_NOT_HAVE_ENOUGH_MONEY);
                return;
            }
            ctx.setExactPrice(scaled);
            ctx.setStock(getCountedItemStack(ctx.getStock(), amountAffordable));
        }

        if (!inventoryService.hasItems(ctx.getStock(), ctx.getClientInventory())) {
            ItemStack[] itemsHad = getItems(ctx.getStock(), ctx.getClientInventory());
            int possessed = InventoryUtil.countItems(itemsHad);
            if (possessed <= 0) {
                ctx.setCancelled(NOT_ENOUGH_STOCK_IN_INVENTORY);
                return;
            }
            BigDecimal scaled = scalePrice(pricePerItem, possessed);
            if (roundedToZero(pricePerItem, scaled)) {
                ctx.setCancelled(NOT_ENOUGH_STOCK_IN_INVENTORY);
                return;
            }
            ctx.setExactPrice(scaled);
            ctx.setStock(itemsHad);
        }

        if (!ctx.isUnlimitedOwner() && !inventoryService.fits(ctx.getStock(), ctx.getOwnerInventory())) {
            ItemStack[] itemsFit = getItemsThatFit(ctx.getStock(), ctx.getOwnerInventory());
            int possessed = InventoryUtil.countItems(itemsFit);
            if (possessed <= 0) {
                ctx.setCancelled(NOT_ENOUGH_SPACE_IN_CHEST);
                return;
            }
            BigDecimal scaled = scalePrice(pricePerItem, possessed);
            if (roundedToZero(pricePerItem, scaled)) {
                ctx.setCancelled(NOT_ENOUGH_SPACE_IN_CHEST);
                return;
            }
            ctx.setExactPrice(scaled);
            ctx.setStock(itemsFit);
        }
    }

    BigDecimal scalePrice(BigDecimal pricePerItem, int count) {
        return pricePerItem.multiply(new BigDecimal(count)).setScale(config.getPricePrecision(), RoundingMode.HALF_UP);
    }

    /** A positive per-item price that scales to zero means the partial amount is unaffordable. */
    static boolean roundedToZero(BigDecimal pricePerItem, BigDecimal scaled) {
        return pricePerItem.compareTo(BigDecimal.ZERO) > 0 && scaled.compareTo(BigDecimal.ZERO) == 0;
    }

    static int getAmountOfAffordableItems(BigDecimal walletMoney, BigDecimal pricePerItem, int cap) {
        // The wallet can be effectively unbounded (getBalance returns Double.MAX_VALUE for an
        // unlimited server-less admin shop), so the quotient can far exceed Integer.MAX_VALUE.
        // intValueExact() would throw ArithmeticException and abort the trade on the main thread.
        // The affordable amount is only ever used to scale the fill down, so it never needs to
        // exceed the requested item count — clamp to that cap before converting to int.
        BigDecimal affordable = walletMoney.divide(pricePerItem, 0, RoundingMode.FLOOR)
                .min(BigDecimal.valueOf(cap));
        return affordable.intValue();
    }

    private ItemStack[] getItems(ItemStack[] stock, Inventory inventory) {
        List<ItemStack> toReturn = new LinkedList<>();
        for (Map.Entry<ItemStack, Integer> entry : inventoryService.getItemCounts(stock).entrySet()) {
            int amount = inventoryService.getAmount(entry.getKey(), inventory);
            Collections.addAll(toReturn, inventoryService.getItemStacked(entry.getKey(), Math.min(amount, entry.getValue())));
        }
        return toReturn.toArray(new ItemStack[0]);
    }

    private ItemStack[] getCountedItemStack(ItemStack[] stock, int numberOfItems) {
        int left = numberOfItems;
        LinkedList<ItemStack> stacks = new LinkedList<>();

        for (ItemStack stack : stock) {
            int count = stack.getAmount();
            ItemStack toAdd;
            if (left > count) {
                toAdd = stack;
                left -= count;
            } else {
                ItemStack clone = stack.clone();
                clone.setAmount(left);
                toAdd = clone;
                left = 0;
            }

            boolean added = false;
            int maxStackSize = inventoryService.getMaxStackSize(stack);
            for (ItemStack iStack : stacks) {
                if (iStack.getAmount() < maxStackSize && materialService.equals(toAdd, iStack)) {
                    int newAmount = iStack.getAmount() + toAdd.getAmount();
                    if (newAmount > maxStackSize) {
                        iStack.setAmount(maxStackSize);
                        toAdd.setAmount(newAmount - maxStackSize);
                    } else {
                        iStack.setAmount(newAmount);
                        added = true;
                    }
                    break;
                }
            }

            if (!added) {
                Collections.addAll(stacks, inventoryService.getItemsStacked(toAdd));
            }
            if (left <= 0) {
                break;
            }
        }
        return stacks.toArray(new ItemStack[0]);
    }

    private ItemStack[] getItemsThatFit(ItemStack[] stock, Inventory inventory) {
        List<ItemStack> resultStock = new LinkedList<>();
        int emptySlots = InventoryUtil.countEmpty(inventory);

        for (Map.Entry<ItemStack, Integer> entry : inventoryService.getItemCounts(stock).entrySet()) {
            ItemStack item = entry.getKey();
            int amount = entry.getValue();
            int maxStackSize = inventoryService.getMaxStackSize(item);
            int free = 0;
            for (ItemStack itemInInventory : inventory.getContents()) {
                if (materialService.equals(item, itemInInventory) && itemInInventory != null) {
                    free += (maxStackSize - itemInInventory.getAmount()) % maxStackSize;
                }
            }

            if (free == 0 && emptySlots == 0) {
                continue;
            }

            if (amount > free) {
                if (emptySlots > 0) {
                    int requiredSlots = (int) Math.ceil(((double) amount - free) / maxStackSize);
                    if (requiredSlots <= emptySlots) {
                        emptySlots = emptySlots - requiredSlots;
                    } else {
                        amount = free + maxStackSize * emptySlots;
                        emptySlots = 0;
                    }
                } else {
                    amount = free;
                }
            }
            Collections.addAll(resultStock, inventoryService.getItemStacked(item, amount));
        }
        return resultStock.toArray(new ItemStack[0]);
    }
}
