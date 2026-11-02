package io.paradaux.treasury.model.economy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.Nullable;

/**
 * Flattened view of a ledger posting joined with its parent transaction.
 * Used for paginated transaction history on an account.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class TransactionEntry {
    public TransactionEntry(
            long postingId,
            long txnId,
            int accountId,
            BigDecimal amount,
            String memo,
            Instant settlementTime,
            String message,
            UUID initiatorUuid,
            UUID authorizerUuid,
            String pluginSystem) {
        this(
                postingId,
                txnId,
                accountId,
                amount,
                memo,
                settlementTime,
                message,
                initiatorUuid,
                authorizerUuid,
                pluginSystem,
                null);
    }

    private long postingId;
    private long txnId;
    private int accountId;
    private BigDecimal amount;
    private String memo;
    private Instant settlementTime;
    private String message;
    private UUID initiatorUuid;
    private UUID authorizerUuid;
    private String pluginSystem;
    private @Nullable PayoutCategory payoutCategory;
}
