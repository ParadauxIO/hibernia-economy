package io.paradaux.treasury.model.economy;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class LedgerTxn {
    private long txnId;
    private Instant tradeTime;
    private Instant settlementTime;
    private String message;
    private UUID initiatorUuid;
    private UUID authorizerUuid;
    private String pluginSystem;
    private byte[] clientDedupKey;

    // Defensive copies on the mutable dedup-key array (ADT-40): defining these
    // suppresses Lombok's generated accessors so neither a caller nor the source
    // array can mutate the stored idempotency key.
    public byte[] getClientDedupKey() {
        return clientDedupKey == null ? null : clientDedupKey.clone();
    }

    public void setClientDedupKey(byte[] clientDedupKey) {
        this.clientDedupKey = clientDedupKey == null ? null : clientDedupKey.clone();
    }
}
