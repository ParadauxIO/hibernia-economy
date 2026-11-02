package io.paradaux.treasury.model.economy;

import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Value;
import lombok.With;

@Builder(toBuilder = true)
@Value
@With
@AllArgsConstructor
public class BalanceEntry {
    private int accountId;
    private UUID ownerUuid;
    private String displayName;
    private BigDecimal balance;
}
