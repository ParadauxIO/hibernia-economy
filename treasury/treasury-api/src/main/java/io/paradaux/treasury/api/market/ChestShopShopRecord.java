package io.paradaux.treasury.api.market;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A shop sign for the live shop registry ({@code chestshop_shop}) — "what's for
 * sale, where, and how much is in stock". Identified by its sign location.
 * Upserted by ChestShop-3 on create / restock / price change; the caller has
 * already classified the owning account and resolved the item.
 */
public record ChestShopShopRecord(
        // identity = sign location
        String world, int signX, int signY, int signZ,
        boolean adminShop,
        // owner account (already classified by the caller)
        Integer shopAccountId,
        String shopAccountType,
        Integer shopFirmId,
        UUID shopOwnerUuid,
        // item
        String material,
        String itemKey,
        String itemName,
        boolean itemCustom,
        String itemData,
        // sign pricing
        BigDecimal buyPrice,        // customer BUYS from shop; null if not offered
        BigDecimal sellPrice,       // customer SELLS to shop; null if not offered
        int batchQty,
        // live stock of the item in the chest; null = admin/infinite or unknown
        Integer currentStock,
        // remaining free space for the item (units that still fit); null = admin/infinite or unknown
        Integer estimatedCapacity,
        // stable world identity alongside the name key; null if the caller didn't resolve it
        UUID worldUuid
) {}
