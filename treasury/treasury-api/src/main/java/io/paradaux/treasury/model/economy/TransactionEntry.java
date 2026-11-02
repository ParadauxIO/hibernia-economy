package io.paradaux.treasury.model.economy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Flattened view of a ledger posting joined with its parent transaction.
 * Used for paginated transaction history on an account.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class TransactionEntry {
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
    /** Structured purpose from the originating operation; memos and account labels are ignored. */
    public @org.jetbrains.annotations.Nullable PayoutCategory getPayoutCategory() {
        return PayoutCategory.fromSource(pluginSystem);
    }
}
