package io.paradaux.treasury.api.market;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

/** Aggregate sales for one customer over a window (PAR-176 summary). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TopCustomer {
    private UUID customerUuid;
    private String customerName;   // resolved from economy_players; null if unknown
    private long saleCount;
    private BigDecimal volume;     // total price
}
