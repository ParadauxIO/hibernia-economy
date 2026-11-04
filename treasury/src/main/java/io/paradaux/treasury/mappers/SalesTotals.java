package io.paradaux.treasury.mappers;

import io.paradaux.treasury.api.market.SalesSummary;
import io.paradaux.treasury.api.market.TopCustomer;
import io.paradaux.treasury.api.market.TopItem;
import java.math.BigDecimal;
import java.util.List;

/** Result of the totals query before the leaderboards are loaded. */
public record SalesTotals(
        long saleCount,
        long totalUnits,
        BigDecimal totalVolume,
        BigDecimal totalTax,
        long buyCount,
        BigDecimal buyVolume,
        long sellCount,
        BigDecimal sellVolume) {
    public SalesSummary toSummary(List<TopItem> items, List<TopCustomer> customers) {
        return new SalesSummary(
                saleCount,
                totalUnits,
                totalVolume,
                totalTax,
                buyCount,
                buyVolume,
                sellCount,
                sellVolume,
                items,
                customers);
    }
}
