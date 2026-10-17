package io.paradaux.treasury.api.market;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One completed ChestShop trade, for the structured sales tracker
 * ({@code chestshop_sale}). The caller (ChestShop-3) has already classified the
 * shop's owning account (type / firm / owner) using the Treasury + Business
 * APIs and resolved the item identity; this is a pure data carrier.
 *
 * <p>Analytics only — money stays authoritative in the ledger; {@link #txnId}
 * links back to it.
 */
public record ChestShopSaleRecord(
        Long txnId, // ledger_txns id; nullable if unlinkable
        String direction, // "BUY" | "SELL" (customer's perspective)
        UUID customerUuid, // the player who clicked the sign
        // shop owner account (already classified by the caller)
        Integer shopAccountId, // null for admin shops
        String shopAccountType, // PERSONAL|BUSINESS|GOVERNMENT|SYSTEM, or null (admin)
        Integer shopFirmId, // set iff BUSINESS
        UUID shopOwnerUuid, // set for PERSONAL
        boolean adminShop,
        // item
        String material,
        String itemKey,
        String itemName,
        boolean itemCustom,
        String itemData, // Bukkit YAML ItemStack under the "item" key; nullable
        // price
        int quantity,
        BigDecimal unitPrice,
        BigDecimal totalPrice,
        BigDecimal taxAmount,
        // location
        String world,
        Integer signX,
        Integer signY,
        Integer signZ,
        // shop stock of the traded item at sale time (post-trade); null for admin
        Integer shopStock) {}
