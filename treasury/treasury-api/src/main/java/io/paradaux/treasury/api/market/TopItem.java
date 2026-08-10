package io.paradaux.treasury.api.market;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** Aggregate sales for one item over a window (PAR-176 summary). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TopItem {
    private String itemKey;
    private String itemName;
    private long units;          // total quantity traded
    private BigDecimal volume;   // total price
    private long saleCount;      // number of sales
}
