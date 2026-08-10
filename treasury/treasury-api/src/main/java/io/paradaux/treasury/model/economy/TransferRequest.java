package io.paradaux.treasury.model.economy;

import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

public record TransferRequest(
        int fromAccountId,
        int toAccountId,
        BigDecimal amount,
        String message,
        UUID initiator,
        @Nullable UUID authorizer,
        @Nullable String pluginSystem,
        byte @Nullable [] dedupKey) {

    public TransferRequest {
        // Validate the money-bearing fields up front so a bad request can't reach
        // the ledger (ADT-40), and defensively copy the mutable dedup key array so
        // a caller can't mutate the idempotency key after construction.
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(initiator, "initiator");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be > 0, was " + amount);
        }
        dedupKey = dedupKey == null ? null : dedupKey.clone();
    }

    /** Defensive copy so the returned idempotency key can't mutate the record's. */
    @Override
    public byte @Nullable [] dedupKey() {
        return dedupKey == null ? null : dedupKey.clone();
    }
}
