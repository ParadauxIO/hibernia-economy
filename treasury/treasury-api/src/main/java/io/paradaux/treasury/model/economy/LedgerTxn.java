package io.paradaux.treasury.model.economy;

import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;
import lombok.With;
import org.jetbrains.annotations.Nullable;

@Builder(toBuilder = true)
@Value
@With
public class LedgerTxn {
    private long txnId;
    private Instant tradeTime;
    private Instant settlementTime;
    private String message;
    private UUID initiatorUuid;
    private UUID authorizerUuid;
    private String pluginSystem;
    private byte @Nullable [] clientDedupKey;

    public LedgerTxn(
            long txnId,
            Instant tradeTime,
            Instant settlementTime,
            String message,
            UUID initiatorUuid,
            UUID authorizerUuid,
            String pluginSystem,
            byte @Nullable [] clientDedupKey) {
        this.txnId = txnId;
        this.tradeTime = tradeTime;
        this.settlementTime = settlementTime;
        this.message = message;
        this.initiatorUuid = initiatorUuid;
        this.authorizerUuid = authorizerUuid;
        this.pluginSystem = pluginSystem;
        this.clientDedupKey = clientDedupKey == null ? null : clientDedupKey.clone();
    }

    /** Returns a defensive copy of the idempotency key. */
    public byte @Nullable [] getClientDedupKey() {
        return clientDedupKey == null ? null : clientDedupKey.clone();
    }

    /** Structured purpose from the originating operation; memos and account labels are ignored. */
    public @Nullable PayoutCategory getPayoutCategory() {
        return PayoutCategory.fromSource(pluginSystem);
    }
}
