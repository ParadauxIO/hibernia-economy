package io.paradaux.treasury.model.economy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Value;
import lombok.With;

@Builder(toBuilder = true)
@Value
@With
@AllArgsConstructor
public class GovernmentFine {
    private long fineId;
    /** The fined player, for player fines; {@code null} for firm/account fines. */
    private UUID playerUuid;
    /** The account actually debited (a PERSONAL account for player fines, a BUSINESS account for firm fines). */
    private Integer debtorAccountId;

    private int govAccountId;
    private BigDecimal amount;
    private String reason;
    private long txnId;
    private UUID issuedBy;
    private Instant issuedAt;
    private boolean revoked;
    private UUID revokedBy;
    private Long revokeTxnId;
    private Instant revokedAt;
}
