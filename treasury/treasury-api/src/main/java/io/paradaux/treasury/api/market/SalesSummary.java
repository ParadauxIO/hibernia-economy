package io.paradaux.treasury.api.market;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Aggregate sales report over a window for an owner scope (PAR-178 / PAR-176).
 * Totals are computed in one pass; {@link #topItems} / {@link #topCustomers} are
 * the leaderboards over the same scope.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
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
    // leaderboards (set by the service after the totals pass)
    private List<TopItem> topItems;
    private List<TopCustomer> topCustomers;
}
