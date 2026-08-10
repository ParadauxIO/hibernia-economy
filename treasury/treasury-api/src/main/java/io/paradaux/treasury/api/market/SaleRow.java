package io.paradaux.treasury.api.market;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/** One {@code chestshop_sale} row for an in-game sales list (PAR-176). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SaleRow {
    private LocalDateTime occurredAt;
    private String direction;        // BUY | SELL (customer's perspective)
    private UUID customerUuid;
    private String customerName;     // resolved from economy_players; null if unknown
    private int quantity;
    private String material;
    private String itemName;
    private boolean itemCustom;
    private BigDecimal unitPrice;
    private BigDecimal totalPrice;
    private BigDecimal taxAmount;
    private String world;
    private Integer signX;
    private Integer signY;
    private Integer signZ;
    private Long txnId;              // ledger link; null if unlinkable
}
