package io.paradaux.treasury.api.market;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Value;
import lombok.With;

/** Aggregate sales for one item over a window (PAR-176 summary). */
@Builder(toBuilder = true)
@Value
@With
@AllArgsConstructor
public class TopItem {
    private String itemKey;
    private String itemName;
    private long units; // total quantity traded
    private BigDecimal volume; // total price
    private long saleCount; // number of sales
}
