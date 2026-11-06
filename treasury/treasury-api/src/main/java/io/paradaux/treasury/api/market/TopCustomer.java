package io.paradaux.treasury.api.market;

import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Value;
import lombok.With;

/** Aggregate sales for one customer over a window (PAR-176 summary). */
@Builder(toBuilder = true)
@Value
@With
@AllArgsConstructor
public class TopCustomer {
    private UUID customerUuid;
    private String customerName; // resolved from economy_players; null if unknown
    private long saleCount;
    private BigDecimal volume; // total price
}
