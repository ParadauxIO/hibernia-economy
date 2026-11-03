package io.paradaux.treasury.model.economy;

import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Value;
import lombok.With;

/**
 * Represents a member or authorizer row on an account.
 */
@Builder(toBuilder = true)
@Value
@With
@AllArgsConstructor
public class AccountMember {
    private int accountId;
    private UUID memberUuid;
    private UUID addedByUuid;
    private Instant createdAt;
}
