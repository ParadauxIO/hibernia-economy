package io.paradaux.chestshop.services;

import io.paradaux.business.api.BusinessApi;
import io.paradaux.treasury.api.MarketApi;
import io.paradaux.treasury.api.ShopQueryApi;
import io.paradaux.treasury.api.TreasuryApi;
import io.paradaux.treasury.api.market.ChestShopSaleRecord;
import io.paradaux.treasury.api.market.ChestShopShopRecord;
import org.bukkit.block.Sign;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * ChestShop's market-analytics boundary: holds the optional Treasury {@link MarketApi} (writes the
 * sales tracker + live shop registry to the shared economy DB), {@link ShopQueryApi} (the
 * {@code /find} read side), and {@link TreasuryApi}/{@link BusinessApi} (owner classification),
 * resolved from the Bukkit ServicesManager at enable; and builds the MarketApi DTOs from ChestShop
 * data (owner classification + custom-aware item identity). Merged the former static
 * {@code MarketHook} + the {@code MarketRecords} DTO builder (PAR-316). Inert when the MarketApi
 * isn't present.
 */
public interface MarketService {

    /** Resolve the Treasury market APIs from the ServicesManager (called at enable). */
    void init();

    /** True when both the MarketApi (writes) and TreasuryApi (account classification) are available. */
    boolean enabled();

    /** True when the ShopQueryApi (the {@code /find} read side) is available. */
    boolean searchEnabled();

    MarketApi market();

    ShopQueryApi shopQuery();

    TreasuryApi treasury();

    BusinessApi business();

    /** A shop's owning account, classified as personal / business firm / government / admin. */
    record Owner(Integer accountId, String type, Integer firmId, UUID ownerUuid, boolean admin) {}

    /** Resolve the shop's owning account from the ChestShop owner-account UUID. */
    Owner ownerFromUuid(UUID ownerUuid, boolean adminShop);

    /** The stock of {@code item} in {@code inventory} (0 if none / no inventory). */
    int stockOf(ItemStack item, Inventory inventory);

    /** Remaining free space for the item in the container; {@code null} if unknown (no inventory). */
    Integer capacityOf(ItemStack item, Inventory inventory);

    /** Build a sale record DTO for the markets DB. */
    ChestShopSaleRecord sale(Sign sign, ItemStack item, int quantity, UUID customer,
                             Owner owner, BigDecimal total, BigDecimal tax,
                             String direction, Integer shopStock, Long txnId);

    /** Build a shop record DTO for the live shop registry. */
    ChestShopShopRecord shop(Sign sign, ItemStack item, Owner owner,
                             Integer currentStock, Integer estimatedCapacity);

    /** Total item count across a stock array (nulls skipped). */
    int totalAmount(ItemStack[] stock);
}
