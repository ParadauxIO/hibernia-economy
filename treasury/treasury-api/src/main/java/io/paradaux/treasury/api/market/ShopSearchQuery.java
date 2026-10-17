package io.paradaux.treasury.api.market;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

/**
 * Read query over {@code chestshop_shop} for the in-game {@code /find} search
 * (PAR-5). Intentionally thin: it filters on the indexed, owner-visible axes
 * (item key, world) and leaves shop-type / hide-empty-full / sort / distance to
 * the ChestShop-side pipeline, which alone knows the querying player's position.
 *
 * <p>{@link #itemKey} is required. With {@link #fuzzy} off it matches exactly;
 * with it on it matches {@code item_key LIKE %itemKey%} (the legacy "include
 * similar items" behaviour — a substring match on the code, not semantic). Only
 * {@code active = 1 AND visible = 1} rows are returned. Build with
 * {@code ShopSearchQuery.builder()}.
 */
@Getter
@Builder
@ToString
public class ShopSearchQuery {

    /** The item code to search for (required). */
    private final String itemKey;

    /** Substring (LIKE) match instead of exact — "include similar items". */
    @Builder.Default
    private final boolean fuzzy = false;

    /** Restrict to one world by name; null = all worlds. */
    private final String world;

    /**
     * Safety cap on rows returned (the Java pipeline filters/sorts these). A
     * single item rarely has more than a few hundred shops; if the cap is hit
     * the caller logs it rather than silently truncating.
     */
    @Builder.Default
    private final int limit = 500;
}
