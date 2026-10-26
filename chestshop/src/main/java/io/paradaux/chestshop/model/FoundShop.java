package io.paradaux.chestshop.model;

import io.paradaux.treasury.api.market.ShopResult;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * A shop in {@code /find} results: the registry row plus the squared distance to
 * the querying player (precomputed, since only the client knows where they are).
 * Derived getters (shop type, unit prices, sort keys) are the contract the
 * {@link ShopAttribute} comparators and the results UI read.
 *
 * <p>Null stock/capacity means admin/infinite — treated as unbounded for sorting
 * and never filtered out by hide-empty / hide-full.
 */
public record FoundShop(
        String itemKey,
        String itemName,
        String material,
        boolean itemCustom,
        String itemData,
        String ownerName,
        BigDecimal buyPrice,
        BigDecimal sellPrice,
        int batchQty,
        Integer stock,
        Integer capacity,
        String world,
        int x, int y, int z,
        long distanceSquared
) {

    /** Build from a registry row, computing distance from the querying player's position. */
    public static FoundShop from(ShopResult r, String queryWorld, int qx, int qy, int qz,
                                 boolean hasPosition) {
        long distSq;
        if (!hasPosition || queryWorld == null || !queryWorld.equals(r.world())) {
            distSq = Long.MAX_VALUE; // unknown / cross-world → "∞"
        } else {
            long dx = (long) r.signX() - qx;
            long dy = (long) r.signY() - qy;
            long dz = (long) r.signZ() - qz;
            distSq = dx * dx + dy * dy + dz * dz;
        }
        return new FoundShop(
                r.itemKey(), r.itemName(), r.material(), r.itemCustom(), r.itemData(),
                r.ownerName(), r.buyPrice(), r.sellPrice(), Math.max(1, r.batchQty()),
                r.currentStock(), r.estimatedCapacity(),
                r.world(), r.signX(), r.signY(), r.signZ(), distSq);
    }

    public ShopType shopType() {
        boolean buy = buyPrice != null;
        boolean sell = sellPrice != null;
        if (buy && sell) return ShopType.BOTH;
        if (sell) return ShopType.SELL;
        return ShopType.BUY;
    }

    public Double buyPriceValue() {
        return buyPrice == null ? null : buyPrice.doubleValue();
    }

    public Double sellPriceValue() {
        return sellPrice == null ? null : sellPrice.doubleValue();
    }

    public Double unitBuyPrice() {
        return buyPrice == null ? null : buyPrice.doubleValue() / batchQty;
    }

    public Double unitSellPrice() {
        return sellPrice == null ? null : sellPrice.doubleValue() / batchQty;
    }

    /** Stock as a sort key; null (admin/infinite) sorts as unbounded. */
    public long stockForSort() {
        return stock == null ? Long.MAX_VALUE : stock;
    }

    /** Remaining capacity as a sort key; null (admin/infinite) sorts as unbounded. */
    public long capacityForSort() {
        return capacity == null ? Long.MAX_VALUE : capacity;
    }

    /** Whole-block Euclidean distance, or -1 when unknown / cross-world. */
    public int distanceBlocks() {
        if (distanceSquared == Long.MAX_VALUE) return -1;
        return (int) Math.floor(Math.sqrt((double) distanceSquared));
    }

    /** True if this shop's item code isn't the exact code queried (a fuzzy "similar" hit). */
    public boolean isSimilarTo(String queriedItemKey) {
        return queriedItemKey != null && !Objects.equals(queriedItemKey, itemKey);
    }
}
