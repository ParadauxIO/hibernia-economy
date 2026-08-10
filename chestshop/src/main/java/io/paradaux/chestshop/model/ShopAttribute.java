package io.paradaux.chestshop.model;

import java.util.Comparator;

/**
 * A sortable shop characteristic for {@code /find}. Each carries a display name
 * and a base ascending {@link Comparator} over {@link FoundShop}; the find
 * pipeline composes the selected attributes by weight and flips direction per
 * {@link SortDirection}. Ported from the legacy chestshop-database ShopAttribute
 * + ShopComparators.
 *
 * <p>Null-price ordering: a present price sorts before a missing one (ascending),
 * so shops that actually offer the side you're sorting on come first. Null stock
 * / capacity (admin/infinite shops) sort as effectively unbounded.
 */
public enum ShopAttribute {
    BUY_PRICE("Buy Price", Comparator.comparing(FoundShop::buyPriceValue, ShopAttribute::nullsLastDouble)),
    SELL_PRICE("Sell Price", Comparator.comparing(FoundShop::sellPriceValue, ShopAttribute::nullsLastDouble)),
    UNIT_BUY_PRICE("Unit Buy Price", Comparator.comparing(FoundShop::unitBuyPrice, ShopAttribute::nullsLastDouble)),
    UNIT_SELL_PRICE("Unit Sell Price", Comparator.comparing(FoundShop::unitSellPrice, ShopAttribute::nullsLastDouble)),
    STOCK("Stock", Comparator.comparingLong(FoundShop::stockForSort)),
    REMAINING_CAPACITY("Remaining Capacity", Comparator.comparingLong(FoundShop::capacityForSort)),
    QUANTITY("Quantity", Comparator.comparingInt(FoundShop::batchQty)),
    DISTANCE("Distance", Comparator.comparingLong(FoundShop::distanceSquared));

    private final String displayName;
    private final Comparator<FoundShop> ascending;

    ShopAttribute(String displayName, Comparator<FoundShop> ascending) {
        this.displayName = displayName;
        this.ascending = ascending;
    }

    public String displayName() {
        return displayName;
    }

    /** Base (ascending) comparator for this attribute. */
    public Comparator<FoundShop> comparator() {
        return ascending;
    }

    /** Comparator honouring a direction. */
    public Comparator<FoundShop> comparator(SortDirection direction) {
        return direction == SortDirection.DESCENDING ? ascending.reversed() : ascending;
    }

    /** Present value first (ascending puts a real price/figure before a missing one). */
    private static int nullsLastDouble(Double a, Double b) {
        if (a == null && b == null) return 0;
        if (a == null) return 1;
        if (b == null) return -1;
        return a.compareTo(b);
    }
}
