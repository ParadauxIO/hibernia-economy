package io.paradaux.chestshop.services.impl;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.chestshop.ChestShop;
import io.paradaux.chestshop.model.DestroyedShop;
import io.paradaux.chestshop.model.Transaction;
import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import io.paradaux.chestshop.services.AccountService;
import io.paradaux.chestshop.services.EconomyService;
import io.paradaux.chestshop.services.InventoryService;
import io.paradaux.chestshop.services.ItemService;
import io.paradaux.chestshop.services.PostTradeReactions;
import io.paradaux.chestshop.services.ShopBlockService;
import io.paradaux.chestshop.services.ShopService;
import io.paradaux.chestshop.services.SignService;
import io.paradaux.chestshop.utils.InventoryUtil;
import io.paradaux.chestshop.utils.LocationUtil;
import io.paradaux.hibernia.framework.i18n.Message;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Container;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;

import static io.paradaux.chestshop.model.Transaction.TransactionType.BUY;

/**
 * The ChestShop-internal MONITOR reactions to a settled trade — remove an emptied shop, write the
 * shop log (off-thread), and notify the buyer + owner — extracted from TransactionServiceImpl
 * (chestshop/structure/0001). The genuine cross-cutting reactions (stock counter, market sync,
 * metrics, legacy-sign migration) remain wired by the orchestrator.
 */
@Singleton
@Slf4j
public class PostTradeReactionsImpl implements PostTradeReactions {

    private static final String BUY_LOG = "%1$s bought %2$s for %3$.2f from %4$s at %5$s";
    private static final String SELL_LOG = "%1$s sold %2$s for %3$.2f to %4$s at %5$s";

    private final EconomyService economy;
    private final ShopService shops;
    private final AccountService accounts;
    private final Message message;
    private final ItemService items;
    private final ChestShopConfiguration config;
    private final SignService signService;
    private final ShopBlockService shopBlockService;
    private final InventoryService inventoryService;

    @Inject
    PostTradeReactionsImpl(EconomyService economy, ShopService shops, AccountService accounts, Message message, ItemService items,
                       ChestShopConfiguration config, SignService signService, ShopBlockService shopBlockService, InventoryService inventoryService) {
        this.economy = economy;
        this.shops = shops;
        this.accounts = accounts;
        this.message = message;
        this.items = items;
        this.config = config;
        this.signService = signService;
        this.shopBlockService = shopBlockService;
        this.inventoryService = inventoryService;
    }

    /** Remove a shop whose container ran dry after a buy (config-gated, never admin shops). */
    @Override
    public void deleteEmptyShop(Transaction event) {
        if (event.getTransactionType() != BUY) {
            return;
        }
        Sign sign = event.getSign();
        if (signService.isAdminShop(sign)) {
            return;
        }
        Inventory ownerInventory = event.getOwnerInventory();
        if (!shopShouldBeRemoved(ownerInventory, event.getStock()) || !isInRemoveWorld(sign)) {
            return;
        }

        Container connectedContainer = shopBlockService.findConnectedContainer(sign);
        shops.onDestroyed(new DestroyedShop(null, sign, connectedContainer));

        Material signType = sign.getType();
        sign.getBlock().setType(Material.AIR);

        if (config.isRemoveEmptyChests() && InventoryUtil.isEmpty(ownerInventory)) {
            if (connectedContainer != null) {
                connectedContainer.getBlock().setType(Material.AIR);
            }
        } else {
            if (!signType.isItem()) {
                try {
                    signType = Material.valueOf(signType.name().replace("WALL_", ""));
                } catch (IllegalArgumentException ignored) {}
            }
            if (signType.isItem()) {
                ownerInventory.addItem(new ItemStack(signType, 1));
            } else {
                log.warn("Unable to get item for sign " + signType + " to add to removed shop's container!");
            }
        }
    }

    private boolean shopShouldBeRemoved(Inventory inventory, ItemStack[] stock) {
        if (config.isRemoveEmptyShops()) {
            if (config.isAllowPartialTransactions()) {
                for (ItemStack itemStack : stock) {
                    if (inventory.containsAtLeast(itemStack, 1)) {
                        return false;
                    }
                }
                return true;
            } else if (!inventoryService.hasItems(stock, inventory)) {
                return true;
            }
        }
        return false;
    }

    private boolean isInRemoveWorld(Sign sign) {
        return config.getRemoveEmptyWorlds().isEmpty() || config.getRemoveEmptyWorlds().contains(sign.getWorld().getName());
    }

    /** Write a completed trade to the shop log. */
    @Override
    public void logTransaction(Transaction event) {
        String template = event.getTransactionType() == BUY ? BUY_LOG : SELL_LOG;

        StringBuilder itemList = new StringBuilder(50);
        for (Map.Entry<ItemStack, Integer> entry : inventoryService.getItemCounts(event.getStock()).entrySet()) {
            itemList.append(entry.getValue()).append(' ').append(items.getName(entry.getKey()));
        }

        // Build the line on the main thread (item-name/account/sign reads), then push the
        // log call off the tick: per-trade logging I/O otherwise stalls the main
        // thread on contended shared-host storage. slf4j is thread-safe and the line is
        // self-contained + timestamped, so async logging is order-recoverable and can never
        // affect the already-committed, synchronous trade. This is the only post-trade step
        // with no main-thread dependency — the money legs (atomic) and the
        // market sync (recordSale fires a synchronous Bukkit event) must stay on-thread.
        String line = String.format(template,
                event.getClient().getName(),
                itemList.toString(),
                event.getExactPrice(),
                event.getOwnerAccount().getName(),
                LocationUtil.locationToString(event.getSign().getLocation()));
        ChestShop.runInAsyncThread(() -> log.info(line));
    }

    /** Notify the buyer and (unless they muted) the owner that a trade settled. */
    @Override
    public void sendTransactionMessages(Transaction event) {
        Player player = event.getClient();
        boolean buy = event.getTransactionType() == BUY;

        if (config.isShowTransactionInformationClient()) {
            sendTradeMessage(player,
                    buy ? "chestshop.YOU_BOUGHT_FROM_SHOP" : "chestshop.YOU_SOLD_TO_SHOP", event,
                    buy ? "owner" : "buyer", event.getOwnerAccount().getName());
        }

        if (config.isShowTransactionInformationOwner() && !accounts.isIgnoring(event.getOwnerAccount().getUuid())) {
            Player owner = Bukkit.getPlayer(event.getOwnerAccount().getUuid());
            sendTradeMessage(owner,
                    buy ? "chestshop.SOMEBODY_BOUGHT_FROM_YOUR_SHOP" : "chestshop.SOMEBODY_SOLD_TO_YOUR_SHOP", event,
                    buy ? "buyer" : "seller", player.getName());
        }
    }

    private void sendTradeMessage(Player player, String key, Transaction event, String... replacements) {
        Location loc = event.getSign().getLocation();
        Map<String, String> replacementMap = new LinkedHashMap<>();
        replacementMap.put("price", economy.format(event.getExactPrice()));
        replacementMap.put("world", LocationUtil.worldName(loc)); // ADT-140: world may be unloaded
        replacementMap.put("x", String.valueOf(loc.getBlockX()));
        replacementMap.put("y", String.valueOf(loc.getBlockY()));
        replacementMap.put("z", String.valueOf(loc.getBlockZ()));
        replacementMap.put("material", "%item");

        for (int i = 0; i + 1 < replacements.length; i += 2) {
            replacementMap.put(replacements[i], replacements[i + 1]);
        }

        if (player == null) {
            return; // recipient isn't on this server — nothing to notify
        }

        replacementMap.put("item", items.getItemList(event.getStock()));
        player.sendMessage(message.component(key, io.paradaux.chestshop.utils.MessageUtil.values(true, replacementMap)));
    }
}
