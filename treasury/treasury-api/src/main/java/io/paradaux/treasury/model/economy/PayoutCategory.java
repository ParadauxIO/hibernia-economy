package io.paradaux.treasury.model.economy;

import java.util.Locale;
import org.jetbrains.annotations.Nullable;

/** Purpose of a payment's originating operation, independent of memos and account labels. */
public enum PayoutCategory {
    EMPLOYEE,
    AUCTION_PROCEEDS;

    /**
     * Dedicated sources establish purpose. Mixed-purpose plugins (including Realty and Business)
     * remain unclassified until their producer supplies an operation-specific source.
     */
    public static @Nullable PayoutCategory fromSource(@Nullable String pluginSystem) {
        if (pluginSystem == null) return null;
        return switch (pluginSystem.toLowerCase(Locale.ROOT)) {
            case "treasury-salary" -> EMPLOYEE;
            case "realty-auction" -> AUCTION_PROCEEDS;
            default -> null;
        };
    }
}
