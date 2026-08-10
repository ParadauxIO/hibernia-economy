package io.paradaux.chestshop.services.impl;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Table;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.chestshop.model.Account;
import io.paradaux.chestshop.model.PendingTransaction;
import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import io.paradaux.chestshop.services.AccountService;
import io.paradaux.chestshop.services.AdminBypassService;
import io.paradaux.chestshop.services.EconomyService;
import io.paradaux.chestshop.services.InventoryService;
import io.paradaux.chestshop.services.ItemService;
import io.paradaux.chestshop.services.PartialFillCalculator;
import io.paradaux.chestshop.services.RestrictedSignService;
import io.paradaux.chestshop.services.SignBreakService;
import io.paradaux.chestshop.services.SignService;
import io.paradaux.chestshop.services.TradeValidator;
import io.paradaux.chestshop.utils.MessageUtil;
import io.paradaux.chestshop.utils.Permissions;
import io.paradaux.chestshop.utils.PriceUtil;
import io.paradaux.hibernia.framework.i18n.Message;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

import static io.paradaux.chestshop.model.PendingTransaction.TransactionOutcome.CLIENT_DOES_NOT_HAVE_ENOUGH_MONEY;
import static io.paradaux.chestshop.model.PendingTransaction.TransactionOutcome.CLIENT_DOES_NOT_HAVE_PERMISSION;
import static io.paradaux.chestshop.model.PendingTransaction.TransactionOutcome.CREATIVE_MODE_PROTECTION;
import static io.paradaux.chestshop.model.PendingTransaction.TransactionOutcome.INVALID_CLIENT_NAME;
import static io.paradaux.chestshop.model.PendingTransaction.TransactionOutcome.INVALID_SHOP;
import static io.paradaux.chestshop.model.PendingTransaction.TransactionOutcome.NOT_ENOUGH_SPACE_IN_CHEST;
import static io.paradaux.chestshop.model.PendingTransaction.TransactionOutcome.NOT_ENOUGH_SPACE_IN_INVENTORY;
import static io.paradaux.chestshop.model.PendingTransaction.TransactionOutcome.NOT_ENOUGH_STOCK_IN_CHEST;
import static io.paradaux.chestshop.model.PendingTransaction.TransactionOutcome.NOT_ENOUGH_STOCK_IN_INVENTORY;
import static io.paradaux.chestshop.model.PendingTransaction.TransactionOutcome.SHOP_DOES_NOT_BUY_THIS_ITEM;
import static io.paradaux.chestshop.model.PendingTransaction.TransactionOutcome.SHOP_DOES_NOT_HAVE_ENOUGH_MONEY;
import static io.paradaux.chestshop.model.PendingTransaction.TransactionOutcome.SHOP_DOES_NOT_SELL_THIS_ITEM;
import static io.paradaux.chestshop.model.Transaction.TransactionType.BUY;
import static io.paradaux.chestshop.utils.PriceUtil.NO_PRICE;

/**
 * Runs a shop interaction through the ordered pre-trade validation steps, mutating the
 * {@link PendingTransaction} (cancelling it, or adjusting its stock/price) as needed, then messages
 * the client (and, for full/out-of-stock, the owner) why a trade was cancelled. The steps run in the
 * exact order the former priority-ordered validators fired. Extracted from TransactionServiceImpl
 * (chestshop/structure/0001). Partial fills are resized by {@link PartialFillCalculator}.
 */
@Singleton
public class TradeValidatorImpl implements TradeValidator {

    /** Cooldown table for owner out-of-stock / full-shop notifications: (owner, msgKey) → last-sent ms. */
    private final Table<UUID, String, Long> notificationCooldowns = HashBasedTable.create();

    // Cache the compiled valid-playername pattern, rebuilt only when the config string
    // changes, instead of recompiling the regex on every click (per-click hot path).
    private volatile String cachedPlayernameRegexp;
    private volatile Pattern cachedPlayernamePattern;

    private final EconomyService economy;
    private final AccountService accounts;
    private final SignBreakService signBreak;
    private final Message message;
    private final ItemService items;
    private final ChestShopConfiguration config;
    private final SignService signService;
    private final InventoryService inventoryService;
    private final AdminBypassService adminBypass;
    private final RestrictedSignService restrictedSign;
    private final PartialFillCalculator partialFill;

    /** Wall-clock source for the notification cooldown; injectable so tests drive it deterministically. */
    private final LongSupplier clock;

    @Inject
    TradeValidatorImpl(EconomyService economy, AccountService accounts, SignBreakService signBreak, Message message,
                   ItemService items, ChestShopConfiguration config, SignService signService, InventoryService inventoryService,
                   AdminBypassService adminBypass, RestrictedSignService restrictedSign, PartialFillCalculator partialFill) {
        this(economy, accounts, signBreak, message, items, config, signService, inventoryService,
                adminBypass, restrictedSign, partialFill, System::currentTimeMillis);
    }

    /** Test seam: same as the injected constructor but with a supplied {@code clock}. */
    TradeValidatorImpl(EconomyService economy, AccountService accounts, SignBreakService signBreak, Message message,
                   ItemService items, ChestShopConfiguration config, SignService signService, InventoryService inventoryService,
                   AdminBypassService adminBypass, RestrictedSignService restrictedSign, PartialFillCalculator partialFill,
                   LongSupplier clock) {
        this.economy = economy;
        this.accounts = accounts;
        this.signBreak = signBreak;
        this.message = message;
        this.items = items;
        this.config = config;
        this.signService = signService;
        this.inventoryService = inventoryService;
        this.adminBypass = adminBypass;
        this.restrictedSign = restrictedSign;
        this.partialFill = partialFill;
        this.clock = clock;
    }

    @Override
    public void validate(PendingTransaction ctx) {
        rejectInvalidClientName(ctx);
        rejectCreativeMode(ctx);
        flagFreeShop(ctx);
        rejectMissingPrice(ctx);
        rejectInvalidShop(ctx);

        if (config.isAllowPartialTransactions()) {
            partialFill.adjustBuy(ctx);
            partialFill.adjustSell(ctx);
        }

        // RestrictedSignService is a genuine cross-plugin sign access gate; its pre-trade check
        // is invoked here in the former @LOW slot.
        restrictedSign.onPreTransaction(ctx);

        if (!config.isAllowPartialTransactions() && !ctx.isCancelled()) {
            checkFundsAndStock(ctx);
        }
        checkPermissions(ctx);
        checkStockFits(ctx);

        sendErrorMessage(ctx);

        // Side effects run only after validation has fully concluded: a legacy free shop
        // flagged above is removed here, exactly once, so the validation steps themselves
        // stay pure outcome-setters.
        removeFlaggedFreeShop(ctx);
    }

    /** Drop a player's notification-cooldown rows (called from PlayerConnectListener on quit). */
    @Override
    public void clearNotificationCooldowns(UUID playerUuid) {
        if (config.getNotificationMessageCooldown() > 0) {
            notificationCooldowns.rowMap().remove(playerUuid);
        }
    }

    private Pattern playernamePattern() {
        String regexp = config.getValidPlayernameRegexp();
        if (!regexp.equals(cachedPlayernameRegexp)) {
            cachedPlayernamePattern = Pattern.compile(regexp);
            cachedPlayernameRegexp = regexp;
        }
        return cachedPlayernamePattern;
    }

    /** Block trades by an admin-shop name or a client whose name fails the valid-name regex. */
    private void rejectInvalidClientName(PendingTransaction ctx) {
        if (ctx.isCancelled()) {
            return;
        }
        String name = ctx.getClient().getName();
        if (signService.isAdminShop(name) || !playernamePattern().matcher(name).matches()) {
            ctx.setCancelled(INVALID_CLIENT_NAME);
        }
    }

    /** Optionally block creative-mode players from trading. */
    private void rejectCreativeMode(PendingTransaction ctx) {
        if (ctx.isCancelled()) {
            return;
        }
        if (config.isIgnoreCreativeMode() && ctx.getClient().getGameMode() == GameMode.CREATIVE) {
            ctx.setCancelled(CREATIVE_MODE_PROTECTION);
        }
    }

    /**
     * Detect a free (price-0) shop that pre-dates the no-free-shops rule, unless
     * {@code ALLOW_FREE_SHOPS} permits them (PAR-88). Pure: it only cancels with
     * INVALID_SHOP (which sends the "invalid shop" message) and flags the shop for
     * removal — the actual de-registration + block break happens once, after validation,
     * in {@link #removeFlaggedFreeShop} (ADT-139).
     */
    private void flagFreeShop(PendingTransaction ctx) {
        if (config.isAllowFreeShops() || ctx.isCancelled()) {
            return;
        }
        Sign sign = ctx.getSign();
        if (sign == null) {
            return;
        }
        String price = SignService.getPrice(sign);
        if (!isFree(PriceUtil.getExactBuyPrice(price)) && !isFree(PriceUtil.getExactSellPrice(price))) {
            return;
        }
        ctx.setCancelled(INVALID_SHOP);
        ctx.setRejectedAsFreeShop(true);
    }

    /** Remove a free shop flagged by {@link #flagFreeShop}, after validation has concluded (ADT-139). */
    private void removeFlaggedFreeShop(PendingTransaction ctx) {
        if (!ctx.isRejectedAsFreeShop()) {
            return;
        }
        Sign sign = ctx.getSign();
        if (sign == null) {
            return;
        }
        signBreak.sendShopDestroyed(sign, ctx.getClient());
        sign.getBlock().breakNaturally();
    }

    private static boolean isFree(BigDecimal price) {
        return price.compareTo(PriceUtil.FREE) == 0;
    }

    /** Cancel if the shop has no price for the requested direction. */
    private void rejectMissingPrice(PendingTransaction ctx) {
        if (ctx.isCancelled()) {
            return;
        }
        if (ctx.getExactPrice().equals(NO_PRICE)) {
            ctx.setCancelled(ctx.getTransactionType() == BUY ? SHOP_DOES_NOT_SELL_THIS_ITEM : SHOP_DOES_NOT_BUY_THIS_ITEM);
        }
    }

    /** Cancel if the trade has no stock, or a non-admin shop has no backing container. */
    private void rejectInvalidShop(PendingTransaction ctx) {
        if (ctx.isCancelled()) {
            return;
        }
        if (isEmpty(ctx.getStock())) {
            ctx.setCancelled(INVALID_SHOP);
            return;
        }
        if (!signService.isAdminShop(ctx.getSign()) && ctx.getOwnerInventory() == null) {
            ctx.setCancelled(INVALID_SHOP);
        }
    }

    private static boolean isEmpty(ItemStack[] array) {
        for (ItemStack element : array) {
            if (element != null) {
                return false;
            }
        }
        return true;
    }

    /** Whole-amount funds + stock check (used when partial transactions are disabled). */
    private void checkFundsAndStock(PendingTransaction ctx) {
        if (ctx.getTransactionType() == BUY) {
            if (!economy.hasFunds(ctx.getClient().getUniqueId(), ctx.getExactPrice())) {
                ctx.setCancelled(CLIENT_DOES_NOT_HAVE_ENOUGH_MONEY);
                return;
            }
            if (!ctx.isUnlimitedOwner() && !inventoryService.hasItems(ctx.getStock(), ctx.getOwnerInventory())) {
                ctx.setCancelled(NOT_ENOUGH_STOCK_IN_CHEST);
            }
        } else {
            if (!economy.hasFunds(ctx.getOwnerAccount().getUuid(), ctx.getExactPrice())) {
                ctx.setCancelled(SHOP_DOES_NOT_HAVE_ENOUGH_MONEY);
                return;
            }
            if (!inventoryService.hasItems(ctx.getStock(), ctx.getClientInventory())) {
                ctx.setCancelled(NOT_ENOUGH_STOCK_IN_INVENTORY);
            }
        }
    }

    /** Per-item buy/sell permission check (supports per-material and #-prefixed nodes). */
    private void checkPermissions(PendingTransaction ctx) {
        if (ctx.isCancelled()) {
            return;
        }
        Player client = ctx.getClient();
        boolean buy = ctx.getTransactionType() == BUY;

        String itemLine = SignService.getItem(ctx.getSign());
        if (itemLine.contains("#") && Permissions.hasPermissionSetFalse(client, (buy ? Permissions.BUY_ID : Permissions.SELL_ID) + itemLine)) {
            ctx.setCancelled(CLIENT_DOES_NOT_HAVE_PERMISSION);
            return;
        }

        for (ItemStack stock : ctx.getStock()) {
            String matID = stock.getType().toString().toLowerCase(Locale.ROOT);
            boolean hasPerm = buy
                    ? adminBypass.has(client, Permissions.BUY) && !Permissions.hasPermissionSetFalse(client, Permissions.BUY_ID + matID)
                            || adminBypass.has(client, Permissions.BUY_ID + matID)
                    : adminBypass.has(client, Permissions.SELL) && !Permissions.hasPermissionSetFalse(client, Permissions.SELL_ID + matID)
                            || adminBypass.has(client, Permissions.SELL_ID + matID);
            if (!hasPerm) {
                ctx.setCancelled(CLIENT_DOES_NOT_HAVE_PERMISSION);
                return;
            }
        }
    }

    /** Cancel if the receiving inventory (chest on SELL, client on BUY) can't hold the stock. */
    private void checkStockFits(PendingTransaction ctx) {
        if (ctx.isCancelled()) {
            return;
        }
        if (ctx.getTransactionType() == io.paradaux.chestshop.model.Transaction.TransactionType.SELL) {
            if (!ctx.isUnlimitedOwner() && !inventoryService.fits(ctx.getStock(), ctx.getOwnerInventory())) {
                ctx.setCancelled(NOT_ENOUGH_SPACE_IN_CHEST);
            }
        } else {
            if (!inventoryService.fits(ctx.getStock(), ctx.getClientInventory())) {
                ctx.setCancelled(NOT_ENOUGH_SPACE_IN_INVENTORY);
            }
        }
    }

    /** Tell the client (and, for full/out-of-stock, the owner) why a trade was cancelled. */
    private void sendErrorMessage(PendingTransaction ctx) {
        if (!ctx.isCancelled()) {
            return;
        }

        String messageKey = null;
        switch (ctx.getTransactionOutcome()) {
            case SHOP_DOES_NOT_BUY_THIS_ITEM -> messageKey = "chestshop.NO_SELLING_HERE";
            case SHOP_DOES_NOT_SELL_THIS_ITEM -> messageKey = "chestshop.NO_BUYING_HERE";
            case CLIENT_DOES_NOT_HAVE_PERMISSION -> messageKey = "chestshop.NO_PERMISSION";
            case CLIENT_DOES_NOT_HAVE_ENOUGH_MONEY -> messageKey = "chestshop.NOT_ENOUGH_MONEY";
            case SHOP_DOES_NOT_HAVE_ENOUGH_MONEY -> messageKey = "chestshop.NOT_ENOUGH_MONEY_SHOP";
            case NOT_ENOUGH_SPACE_IN_CHEST -> {
                if (config.isShowMessageFullShop() && !config.isCstoggleTogglesFullShop() || !accounts.isIgnoring(ctx.getOwnerAccount().getUuid())) {
                    sendShopLocationMessage(ctx, "chestshop.NOT_ENOUGH_SPACE_IN_YOUR_SHOP", "seller");
                }
                messageKey = "chestshop.NOT_ENOUGH_SPACE_IN_CHEST";
            }
            case NOT_ENOUGH_SPACE_IN_INVENTORY -> messageKey = "chestshop.NOT_ENOUGH_SPACE_IN_INVENTORY";
            case NOT_ENOUGH_STOCK_IN_INVENTORY -> messageKey = "chestshop.NOT_ENOUGH_ITEMS_TO_SELL";
            case NOT_ENOUGH_STOCK_IN_CHEST -> {
                if (config.isShowMessageOutOfStock() && !config.isCstoggleTogglesOutOfStock() || !accounts.isIgnoring(ctx.getOwnerAccount().getUuid())) {
                    sendShopLocationMessage(ctx, "chestshop.NOT_ENOUGH_STOCK_IN_YOUR_SHOP", "buyer");
                }
                messageKey = "chestshop.NOT_ENOUGH_STOCK";
            }
            case CLIENT_DEPOSIT_FAILED -> messageKey = "chestshop.CLIENT_DEPOSIT_FAILED";
            case SHOP_DEPOSIT_FAILED -> {
                sendMessageToOwner(ctx.getOwnerAccount(), "chestshop.SHOP_DEPOSIT_FAILED_OWNER", new String[0]);
                messageKey = "chestshop.SHOP_DEPOSIT_FAILED";
            }
            case SHOP_IS_RESTRICTED -> messageKey = "chestshop.ACCESS_DENIED";
            case INVALID_SHOP -> messageKey = "chestshop.INVALID_SHOP_DETECTED";
            case INVALID_CLIENT_NAME -> messageKey = "chestshop.INVALID_CLIENT_NAME";
            case CREATIVE_MODE_PROTECTION -> messageKey = "chestshop.TRADE_DENIED_CREATIVE_MODE";
            default -> { }
        }

        if (messageKey != null) {
            message.send(ctx.getClient(), messageKey);
        }
    }

    private void sendShopLocationMessage(PendingTransaction ctx, String key, String actorPlaceholder) {
        Location loc = ctx.getSign().getLocation();
        sendMessageToOwner(ctx.getOwnerAccount(), key, new String[]{
                "price", economy.format(ctx.getExactPrice()),
                actorPlaceholder, ctx.getClient().getName(),
                "world", io.paradaux.chestshop.utils.LocationUtil.worldName(loc), // ADT-140: world may be unloaded
                "x", String.valueOf(loc.getBlockX()),
                "y", String.valueOf(loc.getBlockY()),
                "z", String.valueOf(loc.getBlockZ())
        }, ctx.getStock());
    }

    private void sendMessageToOwner(Account ownerAccount, String key, String[] replacements, ItemStack... stock) {
        Player player = Bukkit.getPlayer(ownerAccount.getUuid());
        if (player == null) {
            return; // owner isn't on this server — nothing to notify
        }

        if (config.getNotificationMessageCooldown() > 0) {
            String cacheKey = key + "|" + String.join(",", replacements) + "|" + items.getItemList(stock);
            Long last = notificationCooldowns.get(ownerAccount.getUuid(), cacheKey);
            long now = clock.getAsLong();
            if (last != null && last + config.getNotificationMessageCooldown() * 1000L > now) {
                return;
            }
            notificationCooldowns.put(ownerAccount.getUuid(), cacheKey, now);
        }

        String itemList = items.getItemList(stock);
        player.sendMessage(message.component(key, MessageUtil.values(true, ImmutableMap.of("material", itemList, "item", itemList), replacements)));
    }
}
