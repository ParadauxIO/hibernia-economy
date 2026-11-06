package io.paradaux.chestshop.utils;

import java.util.regex.Pattern;

/**
 * @author bricefrisco
 */
public final class QuantityUtil {

    private QuantityUtil() {}

    public static final Pattern QUANTITY_LINE_WITH_COUNTER_PATTERN =
            Pattern.compile("^Q [1-9][0-9]{0,4} : C [0-9]{1,5}$");

    public static int parseQuantity(String quantityLine) throws IllegalArgumentException {
        if (quantityLineContainsCounter(quantityLine)) {
            // The pattern guarantees " : "; indexOf avoids compiling a split regex per call.
            return Integer.parseInt(
                    quantityLine.substring(0, quantityLine.indexOf(" : ")).replace("Q ", ""));
        }

        return Integer.parseInt(quantityLine);
    }

    public static boolean quantityLineContainsCounter(String quantityLine) {
        return QUANTITY_LINE_WITH_COUNTER_PATTERN.matcher(quantityLine).matches();
    }
}
