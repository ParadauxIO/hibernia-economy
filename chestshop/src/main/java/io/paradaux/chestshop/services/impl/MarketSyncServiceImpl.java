package io.paradaux.chestshop.services.impl;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.chestshop.model.Account;
import io.paradaux.chestshop.model.CreatedShop;
import io.paradaux.chestshop.model.DestroyedShop;
import io.paradaux.chestshop.model.Transaction;
import io.paradaux.chestshop.services.AccountService;
import io.paradaux.chestshop.services.ItemService;
import io.paradaux.chestshop.services.MarketService;
import io.paradaux.chestshop.services.MarketSyncService;
import io.paradaux.chestshop.services.PreviewService;
import io.paradaux.chestshop.services.ShopBlockService;
import io.paradaux.chestshop.services.ShopStockPublisher;
import io.paradaux.chestshop.services.SignService;
import io.paradaux.chestshop.services.WildcardItems;
import io.paradaux.chestshop.utils.PriceUtil;
import io.paradaux.treasury.api.MarketApi;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Container;
import org.bukkit.block.Sign;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Keeps the market sales tracker + live shop registry in sync with ChestShop activity, extracted
 * from {@code MarketListener} (chestshop/structure/0002). The Bukkit {@code onInventoryClose}
 * handler stays in the listener and delegates to {@link #onInventoryClose}; the other three hooks
 * are invoked directly by {@code TransactionService}/{@code ShopService}. All are fully guarded —
 * analytics must never disrupt a trade.
 *
 * <p>Sign item lines are resolved through {@link ItemService#parse} (custom item → alias →
 * vanilla), never the vanilla-only item-code decoder, so Nexo-id and alias shops are registered
 * like any other. The shop's floating preview follows the registry from here too.
 */
@Singleton
@Slf4j
public class MarketSyncServiceImpl implements MarketSyncService {

    private final JavaPlugin plugin;
    private final MarketService marketService;
    private final ItemService items;
    private final SignService signService;
    private final ShopBlockService shopBlockService;
    private final AccountService accounts;
    private final PreviewService previews;
    private final ShopStockPublisher stockPublisher;

    @Inject
    public MarketSyncServiceImpl(
            JavaPlugin plugin,
            MarketService marketService,
            ItemService items,
            SignService signService,
            ShopBlockService shopBlockService,
            AccountService accounts,
            PreviewService previews,
            ShopStockPublisher stockPublisher) {
        this.plugin = plugin;
        this.marketService = marketService;
        this.items = items;
        this.signService = signService;
        this.shopBlockService = shopBlockService;
        this.accounts = accounts;
        this.previews = previews;
        this.stockPublisher = stockPublisher;
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
            UUID ownerUuid =
                    event.getOwnerAccount() != null ? event.getOwnerAccount().getUuid() : null;
            MarketService.Owner owner = marketService.ownerFromUuid(ownerUuid, admin);
            String direction = event.getTransactionType() == Transaction.TransactionType.BUY ? "BUY" : "SELL";
            Integer shopStock = admin ? null : marketService.stockOf(item, event.getOwnerInventory());
            Integer capacity = admin ? null : marketService.capacityOf(item, event.getOwnerInventory());

            MarketApi market = marketService.market();
            market.recordSale(marketService.sale(
                    sign,
                    item,
                    quantity,
                    event.getClient().getUniqueId(),
                    owner,
                    event.getExactPrice(),
                    event.getSalesTax(),
                    direction,
                    shopStock,
                    event.getSettlementTxnId()));
            if (!hideWildcard(sign, SignService.getItem(sign))) {
                market.upsertShop(marketService.shop(sign, item, owner, shopStock, capacity));
                stockPublisher.recorded(sign.getLocation(), shopStock, capacity);
            }
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
            String itemCode = event.getSignLines()[SignService.ITEM_LINE];
            if (hideWildcard(sign, itemCode)) return;
            ItemStack item = items.parse(itemCode);
            if (item == null) return;
            boolean admin = signService.isAdminShop(event.getSignLines());
            UUID ownerUuid =
                    event.getOwnerAccount() != null ? event.getOwnerAccount().getUuid() : null;
            MarketService.Owner owner = marketService.ownerFromUuid(ownerUuid, admin);
            Inventory container = (!admin && event.getContainer() != null)
                    ? event.getContainer().getInventory()
                    : null;
            Integer stock = container != null ? marketService.stockOf(item, container) : null;
            Integer capacity = container != null ? marketService.capacityOf(item, container) : null;
            String[] lines = event.getSignLines();
            marketService
                    .market()
                    .upsertShop(marketService.shop(
                            sign.getLocation(),
                            item,
                            owner,
                            PriceUtil.getExactBuyPrice(SignService.getPrice(lines)),
                            PriceUtil.getExactSellPrice(SignService.getPrice(lines)),
                            SignService.getQuantity(lines),
                            stock,
                            capacity));
            stockPublisher.recorded(sign.getLocation(), stock, capacity);
            Location l = sign.getLocation();
            previews.refresh(worldName(l), l.getBlockX(), l.getBlockY(), l.getBlockZ());
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
            previews.destroy(worldName(l), l.getBlockX(), l.getBlockY(), l.getBlockZ());
            marketService.market().deactivateShop(worldName(l), l.getBlockX(), l.getBlockY(), l.getBlockZ());
            stockPublisher.forget(l);
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
            for (Sign sign : signs) {
                if (signService.isAdminShop(sign)) continue;
                if (registerShop(sign, inv)) {
                    Location l = sign.getLocation();
                    previews.refresh(worldName(l), l.getBlockX(), l.getBlockY(), l.getBlockZ());
                }
            }
        } catch (Throwable t) {
            log.debug("market analytics hook failed (non-fatal)", t);
        }
    }

    @Override
    public boolean registerShop(Sign sign) {
        if (!marketService.enabled()) return false;
        try {
            Container container = signService.isAdminShop(sign) ? null : shopBlockService.findConnectedContainer(sign);
            return registerShop(sign, container == null ? null : container.getInventory());
        } catch (Throwable t) {
            log.debug("market analytics hook failed (non-fatal)", t);
            return false;
        }
    }

    private boolean registerShop(Sign sign, Inventory inventory) {
        try {
            String itemCode = SignService.getItem(sign);
            if (hideWildcard(sign, itemCode)) return false;
            ItemStack item = items.parse(itemCode);
            if (item == null) return false;
            boolean admin = signService.isAdminShop(sign);
            MarketService.Owner owner;
            if (admin) {
                owner = marketService.ownerFromUuid(null, true);
            } else {
                Account account = accounts.resolveAccount(SignService.getOwner(sign));
                if (account == null || account.getUuid() == null) {
                    return false; // owner not resolvable — skip rather than write a half row
                }
                owner = marketService.ownerFromUuid(account.getUuid(), false);
            }
            Integer stock = inventory != null ? marketService.stockOf(item, inventory) : null;
            Integer capacity = inventory != null ? marketService.capacityOf(item, inventory) : null;
            marketService.market().upsertShop(marketService.shop(sign, item, owner, stock, capacity));
            stockPublisher.recorded(sign.getLocation(), stock, capacity);
            return true;
        } catch (Throwable t) {
            log.debug("market analytics hook failed (non-fatal)", t);
            return false;
        }
    }

    @Override
    public void onBlockReplaced(String world, int x, int y, int z) {
        if (!marketService.enabled()) return;
        try {
            World loaded = plugin.getServer().getWorld(world);
            if (loaded == null || !loaded.isChunkLoaded(x >> 4, z >> 4)) return;
            Sign sign = shopSignAt(loaded, x, y, z);
            if (sign != null) {
                // Still (or again, after an undo) a shop: keep the row live and current. A row that
                // can't be rebuilt right now is left as it was rather than deactivated.
                registerShop(sign);
                previews.refresh(world, x, y, z);
                return;
            }
            previews.destroy(world, x, y, z);
            marketService.market().deactivateShop(world, x, y, z);
            stockPublisher.forget(world, x, y, z);
        } catch (Throwable t) {
            log.debug("market analytics hook failed (non-fatal)", t);
        }
    }

    /** The valid shop sign at a position, or null — never loads a chunk to look. */
    private Sign shopSignAt(World world, int x, int y, int z) {
        return world.getBlockAt(x, y, z).getState(false) instanceof Sign sign && signService.isValid(sign)
                ? sign
                : null;
    }

    private boolean hideWildcard(Sign sign, String itemCode) {
        if (!WildcardItems.isPattern(itemCode)) return false;
        Location l = sign.getLocation();
        previews.destroy(worldName(l), l.getBlockX(), l.getBlockY(), l.getBlockZ());
        marketService.market().deactivateShop(worldName(l), l.getBlockX(), l.getBlockY(), l.getBlockZ());
        stockPublisher.forget(l);
        return true;
    }

    private static String worldName(Location l) {
        return l.getWorld() != null ? l.getWorld().getName() : null;
    }
}
