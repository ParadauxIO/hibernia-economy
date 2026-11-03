package io.paradaux.treasury.model.economy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Value;
import lombok.With;

/**
 * Flattened view of a ledger posting joined with its parent transaction.
 * Used for paginated transaction history on an account.
 */
@Builder(toBuilder = true)
@Value
@With
@AllArgsConstructor
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
}
