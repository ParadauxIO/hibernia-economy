package io.paradaux.chestshop.services.impl;

import lombok.extern.slf4j.Slf4j;

import io.paradaux.chestshop.services.MarketService;
import io.paradaux.chestshop.services.MarketSyncService;
import io.paradaux.chestshop.services.ShopBlockService;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.chestshop.model.CreatedShop;
import io.paradaux.chestshop.model.DestroyedShop;
import io.paradaux.chestshop.model.Transaction;
import io.paradaux.chestshop.services.SignService;
import io.paradaux.treasury.api.MarketApi;
import org.bukkit.Location;
import org.bukkit.block.Sign;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;

/**
 * Keeps the market sales tracker + live shop registry in sync with ChestShop activity, extracted
 * from {@code MarketListener} (chestshop/structure/0002). The Bukkit {@code onInventoryClose}
 * handler stays in the listener and delegates to {@link #onInventoryClose}; the other three hooks
 * are invoked directly by {@code TransactionService}/{@code ShopService}. All are fully guarded —
 * analytics must never disrupt a trade.
 */
@Singleton
@Slf4j
public class MarketSyncServiceImpl implements MarketSyncService {

    private final MarketService marketService;
    private final io.paradaux.chestshop.services.ItemCodeService itemCodes;
    private final SignService signService;
    private final ShopBlockService shopBlockService;

    @Inject
    public MarketSyncServiceImpl(MarketService marketService, io.paradaux.chestshop.services.ItemCodeService itemCodes,
                                 SignService signService, ShopBlockService shopBlockService) {
        this.marketService = marketService;
        this.itemCodes = itemCodes;
        this.signService = signService;
        this.shopBlockService = shopBlockService;
    }

    // Invoked directly by TransactionService#process (was a @MONITOR Transaction listener).
    @Override
    public void onTransaction(Transaction event) {
        if (!marketService.enabled()) return;
        try {
            ItemStack[] stock = event.getStock();
            if (stock == null || stock.length == 0 || stock[0] == null) return;
            ItemStack item = stock[0];
            int quantity = marketService.totalAmount(stock);
            Sign sign = event.getSign();
            boolean admin = signService.isAdminShop(sign);
            UUID ownerUuid = event.getOwnerAccount() != null ? event.getOwnerAccount().getUuid() : null;
            MarketService.Owner owner = marketService.ownerFromUuid(ownerUuid, admin);
            String direction = event.getTransactionType() == Transaction.TransactionType.BUY ? "BUY" : "SELL";
            Integer shopStock = admin ? null : marketService.stockOf(item, event.getOwnerInventory());
            Integer capacity = admin ? null : marketService.capacityOf(item, event.getOwnerInventory());

            MarketApi market = marketService.market();
            market.recordSale(marketService.sale(sign, item, quantity, event.getClient().getUniqueId(),
                    owner, event.getExactPrice(), event.getSalesTax(), direction, shopStock, event.getSettlementTxnId()));
            market.upsertShop(marketService.shop(sign, item, owner, shopStock, capacity));
        } catch (Throwable t) {
            log.debug("market analytics hook failed (non-fatal)", t);
            // analytics only
        }
    }

    // Invoked directly by ShopService#onCreated (was a @MONITOR CreatedShop listener).
    @Override
    public void onShopCreated(CreatedShop event) {
        if (!marketService.enabled()) return;
        try {
            Sign sign = event.getSign();
            ItemStack item = itemCodes.decode(event.getSignLines()[SignService.ITEM_LINE]);
            if (item == null) return;
            boolean admin = signService.isAdminShop(event.getSignLines());
            UUID ownerUuid = event.getOwnerAccount() != null ? event.getOwnerAccount().getUuid() : null;
            MarketService.Owner owner = marketService.ownerFromUuid(ownerUuid, admin);
            Inventory container = (!admin && event.getContainer() != null)
                    ? event.getContainer().getInventory() : null;
            Integer stock = container != null ? marketService.stockOf(item, container) : null;
            Integer capacity = container != null ? marketService.capacityOf(item, container) : null;
            marketService.market().upsertShop(marketService.shop(sign, item, owner, stock, capacity));
        } catch (Throwable t) {
            log.debug("market analytics hook failed (non-fatal)", t);
        }
    }

    // Invoked directly by ShopService#onDestroyed (was a @MONITOR DestroyedShop listener).
    @Override
    public void onShopDestroyed(DestroyedShop event) {
        if (!marketService.enabled()) return;
        try {
            Location l = event.getSign().getLocation();
            marketService.market().deactivateShop(
                    l.getWorld() != null ? l.getWorld().getName() : null,
                    l.getBlockX(), l.getBlockY(), l.getBlockZ());
        } catch (Throwable t) {
            log.debug("market analytics hook failed (non-fatal)", t);
        }
    }

    @Override
    public void onInventoryClose(InventoryHolder holder, Inventory inv) {
        if (!marketService.enabled()) return;
        try {
            if (holder == null) return;
            List<Sign> signs = shopBlockService.findConnectedShopSigns(holder);
            if (signs.isEmpty()) return;
            MarketApi market = marketService.market();
            for (Sign sign : signs) {
                if (signService.isAdminShop(sign)) continue;
                String itemName = SignService.getItem(sign);
                ItemStack item = itemName != null ? itemCodes.decode(itemName) : null;
                if (item == null) continue;
                Location l = sign.getLocation();
                market.updateShopStock(
                        l.getWorld() != null ? l.getWorld().getName() : null,
                        l.getBlockX(), l.getBlockY(), l.getBlockZ(),
                        marketService.stockOf(item, inv), marketService.capacityOf(item, inv));
            }
        } catch (Throwable t) {
            log.debug("market analytics hook failed (non-fatal)", t);
        }
    }
}
