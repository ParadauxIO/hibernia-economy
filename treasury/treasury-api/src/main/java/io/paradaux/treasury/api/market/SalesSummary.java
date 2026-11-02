package io.paradaux.treasury.api.market;

import java.math.BigDecimal;
import java.util.List;
import lombok.Builder;
import lombok.Value;
import lombok.With;

/**
 * Aggregate sales report over a window for an owner scope (PAR-178 / PAR-176).
 * Totals are computed in one pass; {@link #topItems} / {@link #topCustomers} are
 * the leaderboards over the same scope.
 */
@Builder(toBuilder = true)
@Value
@With
public class SalesSummary {
    private long saleCount;
    private long totalUnits;
    private BigDecimal totalVolume;
    private BigDecimal totalTax;
    // split by the customer's direction
    private long buyCount;
    private BigDecimal buyVolume;
    private long sellCount;
    private BigDecimal sellVolume;
    // Immutable leaderboards over the same scope
    private List<TopItem> topItems;
    private List<TopCustomer> topCustomers;

    public SalesSummary(
            long saleCount,
            long totalUnits,
            BigDecimal totalVolume,
            BigDecimal totalTax,
            long buyCount,
            BigDecimal buyVolume,
            long sellCount,
            BigDecimal sellVolume,
            List<TopItem> topItems,
            List<TopCustomer> topCustomers) {
        this.saleCount = saleCount;
        this.totalUnits = totalUnits;
        this.totalVolume = totalVolume;
        this.totalTax = totalTax;
        this.buyCount = buyCount;
        this.buyVolume = buyVolume;
        this.sellCount = sellCount;
        this.sellVolume = sellVolume;
        this.topItems = topItems == null ? List.of() : List.copyOf(topItems);
        this.topCustomers = topCustomers == null ? List.of() : List.copyOf(topCustomers);
    }
}
