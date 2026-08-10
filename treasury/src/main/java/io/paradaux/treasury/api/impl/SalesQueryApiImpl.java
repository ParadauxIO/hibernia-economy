package io.paradaux.treasury.api.impl;

import com.google.inject.Inject;
import io.paradaux.treasury.api.SalesQueryApi;
import io.paradaux.treasury.api.market.SaleRow;
import io.paradaux.treasury.api.market.SalesQuery;
import io.paradaux.treasury.api.market.SalesSummary;
import io.paradaux.treasury.mappers.ChestShopSalesReadMapper;
import org.mybatis.guice.transactional.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Reads the ChestShop sales tracker for the in-game sales commands (PAR-176).
 * Resolves the query's {@code windowDays} into an absolute lower bound once, then
 * delegates to {@link ChestShopSalesReadMapper}; the summary stitches the totals
 * pass together with the item/customer leaderboards.
 */
public class SalesQueryApiImpl implements SalesQueryApi {

    private final ChestShopSalesReadMapper mapper;

    @Inject
    public SalesQueryApiImpl(ChestShopSalesReadMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public List<SaleRow> listSales(SalesQuery query) {
        requireOwnerScope(query);
        return mapper.listSales(query, since(query));
    }

    @Override
    @Transactional
    public long countSales(SalesQuery query) {
        requireOwnerScope(query);
        return mapper.countSales(query, since(query));
    }

    @Override
    @Transactional
    public SalesSummary summarize(SalesQuery query, int topN) {
        requireOwnerScope(query);
        LocalDateTime since = since(query);
        SalesSummary summary = mapper.summarizeTotals(query, since);
        if (summary == null) {
            // COUNT(*) always returns a row, so this is defensive only.
            summary = new SalesSummary(0, 0, BigDecimal.ZERO, BigDecimal.ZERO,
                    0, BigDecimal.ZERO, 0, BigDecimal.ZERO, null, null);
        }
        summary.setTopItems(mapper.topItems(query, since, topN));
        summary.setTopCustomers(mapper.topCustomers(query, since, topN));
        return summary;
    }

    /**
     * Sales are a private, owner-scoped read (per-customer drilldown is sensitive).
     * Enforce the SalesQuery contract of <em>exactly one</em> owner scope, here
     * rather than leaving it to callers: refuse a query with no scope (which would
     * otherwise return server-wide sales) and refuse one with more than one scope.
     * The owner scopes are different granularities denormalised on the row, so two
     * at once AND together into a near-always-empty result — a silent caller bug
     * (ADT-90). The three scopes the row carries are firmId / ownerUuid / accountId.
     */
    private static void requireOwnerScope(SalesQuery query) {
        int scopes = (query.getFirmId() != null ? 1 : 0)
                + (query.getOwnerUuid() != null ? 1 : 0)
                + (query.getAccountId() != null ? 1 : 0);
        if (scopes == 0) {
            throw new IllegalArgumentException(
                    "SalesQuery requires an owner scope (firmId, ownerUuid or accountId)");
        }
        if (scopes > 1) {
            throw new IllegalArgumentException(
                    "SalesQuery accepts exactly one owner scope (firmId, ownerUuid or accountId), not several");
        }
    }

    /** Absolute lower bound for the query window, or null for all-time. */
    private static LocalDateTime since(SalesQuery query) {
        Integer days = query.getWindowDays();
        return (days == null) ? null : LocalDateTime.now().minusDays(days);
    }
}
