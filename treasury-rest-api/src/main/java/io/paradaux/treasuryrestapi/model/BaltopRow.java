package io.paradaux.treasuryrestapi.model;

import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BaltopRow {
    private long accountId;
    private UUID ownerUuid;
    private String playerName;
    private BigDecimal balance;
}
