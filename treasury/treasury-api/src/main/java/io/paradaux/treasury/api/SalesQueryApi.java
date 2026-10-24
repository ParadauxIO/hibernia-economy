package io.paradaux.treasury.api;

import io.paradaux.treasury.api.market.SaleRow;
import io.paradaux.treasury.api.market.SalesQuery;
import io.paradaux.treasury.api.market.SalesSummary;

import java.util.List;

/**
 * Read access over the {@code chestshop_sale} tracker for the in-game sales
 * commands (PAR-176). Complements the write-only {@link MarketApi}. Pure reads —
 * no money mutation. Per-customer / per-seller drilldown is sensitive, so
 * callers must gate this on firm financial access / staff (PAR-177 onward).
 */
public interface SalesQueryApi {

    /** A page of sales for the query's owner scope + filters, newest first. */
    List<SaleRow> listSales(SalesQuery query);

    /** Total number of sales matching the query (for pagination). */
    long countSales(SalesQuery query);

    /**
     * Aggregate report over the query's scope + window: totals, BUY/SELL split,
     * and the top {@code topN} items and customers.
     */
    SalesSummary summarize(SalesQuery query, int topN);
}
