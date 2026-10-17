package io.paradaux.treasuryrestapi.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One live shop sign from {@code chestshop_shop}. The owner name is resolved at
 * query time (firm display name for BUSINESS shops, IGN for PERSONAL) and is
 * null for admin shops or players never seen on the server.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChestShopShopRow {
    private long shopId;
    private String world;
    private Integer signX;
    private Integer signY;
    private Integer signZ;
    private boolean adminShop;
    private String shopAccountType;
    private Integer shopFirmId;
    /** Decoded from BINARY(16) via UuidTypeHandler; set for PERSONAL shops. */
    private UUID shopOwnerUuid;
    private String ownerName;
    private String material;
    private String itemKey;
    private String itemName;
    private boolean itemCustom;
    private BigDecimal buyPrice;
    private BigDecimal sellPrice;
    private int batchQty;
    private Integer currentStock;
    private Integer currentSpace;
    private LocalDateTime stockAt;
    private LocalDateTime lastSeen;
}
