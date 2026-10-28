package com.Acrobot.Breeze.Utils;

/** Binary compatibility for ChestShop add-ons compiled against Breeze pricing helpers. */
public final class PriceUtil {
    private PriceUtil() {}

    public static final java.math.BigDecimal NO_PRICE = io.paradaux.chestshop.utils.PriceUtil.NO_PRICE;
    public static final java.math.BigDecimal FREE = io.paradaux.chestshop.utils.PriceUtil.FREE;
    public static final java.math.BigDecimal MAX = io.paradaux.chestshop.utils.PriceUtil.MAX;
    public static final String FREE_TEXT = io.paradaux.chestshop.utils.PriceUtil.FREE_TEXT;
    public static final char BUY_INDICATOR = io.paradaux.chestshop.utils.PriceUtil.BUY_INDICATOR;
    public static final char SELL_INDICATOR = io.paradaux.chestshop.utils.PriceUtil.SELL_INDICATOR;

    public static java.math.BigDecimal getExact(String text, char indicator) {
        return io.paradaux.chestshop.utils.PriceUtil.getExact(text, indicator);
    }

    public static java.math.BigDecimal getExactBuyPrice(String text) {
        return io.paradaux.chestshop.utils.PriceUtil.getExactBuyPrice(text);
    }

    public static java.math.BigDecimal getExactSellPrice(String text) {
        return io.paradaux.chestshop.utils.PriceUtil.getExactSellPrice(text);
    }

    public static boolean hasBuyPrice(String text) {
        return io.paradaux.chestshop.utils.PriceUtil.hasBuyPrice(text);
    }

    public static boolean hasSellPrice(String text) {
        return io.paradaux.chestshop.utils.PriceUtil.hasSellPrice(text);
    }

    public static boolean hasPrice(String text, char indicator) {
        return io.paradaux.chestshop.utils.PriceUtil.hasPrice(text, indicator);
    }

    public static boolean isPrice(String text) {
        return io.paradaux.chestshop.utils.PriceUtil.isPrice(text);
    }

    public static boolean hasSingleMultiplier(String text) {
        return io.paradaux.chestshop.utils.PriceUtil.hasSingleMultiplier(text);
    }
}
