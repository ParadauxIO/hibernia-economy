package io.paradaux.chestshop.model;

/**
 * What a shop offers, derived purely from which sign prices are set (mirrors the
 * legacy chestshop-database model). A shop with both a buy and sell price is
 * {@link #BOTH}; sell-only is {@link #SELL}; otherwise {@link #BUY}. The find
 * quick-actions treat "buy" intent as {BUY, BOTH} and "sell" intent as
 * {SELL, BOTH} — a BOTH shop satisfies either.
 */
public enum ShopType {
    BUY("Buy"),
    SELL("Sell"),
    BOTH("Buy & Sell");

    private final String displayName;

    ShopType(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
