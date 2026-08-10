package io.paradaux.treasury.api.market;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One live shop sign read back from {@code chestshop_shop} for the in-game
 * {@code /find} search (PAR-5) and the hologram loader (PAR-172). The owner name
 * is resolved at query time (firm display name for BUSINESS, IGN for PERSONAL,
 * account display name otherwise) and is null for admin shops / players never
 * seen on the server.
 *
 * <p>Deliberately lean: the read API returns the matching rows and ChestShop
 * applies shop-type / hide-empty-full filtering, distance and multi-key sorting
 * client-side (it alone knows the querying player's position) — mirroring the
 * legacy "thin SQL, rich Java pipeline" split.
 *
 * <p>{@code buyPrice}/{@code sellPrice} nullability alone defines the shop type:
 * both → BOTH, sell only → SELL, else BUY. Unit price = price / {@code batchQty}.
 */
public record ShopResult(
        // identity = sign location
        String world,
        UUID worldUuid,           // stable world id; null for rows written before V25
        int signX, int signY, int signZ,
        boolean adminShop,
        // owner
        String shopAccountType,
        Integer shopFirmId,
        UUID shopOwnerUuid,
        String ownerName,         // resolved; null for admin / unknown
        // item
        String material,
        String itemKey,
        String itemName,
        boolean itemCustom,
        String itemData,          // base64 ItemStack when itemCustom; else null
        // pricing
        BigDecimal buyPrice,      // customer buys from shop; null if not offered
        BigDecimal sellPrice,     // customer sells to shop; null if not offered
        int batchQty,             // units per transaction
        // live state
        Integer currentStock,     // units in the chest; null = admin/infinite/unknown
        Integer estimatedCapacity,// remaining free space; null = admin/infinite/unknown
        boolean visible,          // owner search-visibility flag
        boolean hologram          // per-shop hologram flag
) {}
