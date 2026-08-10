package io.paradaux.chestshop.services;

import io.paradaux.chestshop.dialogs.FindState;
import io.paradaux.chestshop.model.FoundShop;
import io.paradaux.treasury.api.ShopQueryApi;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Runs a {@code /find} search off the main thread and returns the sorted,
 * filtered, distance-scored results. The registry read (item exact/LIKE, world,
 * active+visible) happens in Treasury via {@link ShopQueryApi}; the shop-type /
 * hide-empty-full filtering, distance and weighted sort happen here through
 * {@link FindState#pipeline}.
 */
public interface ShopFinderService {

    /** True when the registry read API is wired (Treasury present). */
    boolean available();

    /** Search asynchronously; the future completes with the page-ready, pipelined results. */
    CompletableFuture<List<FoundShop>> find(FindState state);
}
