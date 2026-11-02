package io.paradaux.treasury.model.economy;

import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Value;
import lombok.With;

@AllArgsConstructor
@Builder(toBuilder = true)
@Value
@With
public class Account {
    private Integer accountId;
    private AccountType accountType;
    private UUID ownerUuid;
    private String displayName;
    private boolean requiresAuthorization;
    private boolean isArchived;
    private boolean allowOverdraft;
    private BigDecimal creditLimit;
}
