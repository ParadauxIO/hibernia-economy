package io.paradaux.treasuryrestapi.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Joined result of ledger_postings + ledger_txns used by the transaction history endpoint.
 * Underscore-to-camelCase mapping is handled by MyBatis configuration.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionRow {
    private long postingId;
    private long txnId;
    private BigDecimal amount;
    private String memo;
    private String message;
    private LocalDateTime settlementTime;
    /** Decoded from BINARY(16) via UuidTypeHandler. */
    private UUID initiatorUuidBin;
    /** Player name resolved via {@code LEFT JOIN economy_players ON player_uuid_bin = initiator_uuid_bin}. */
    private String initiatorName;
    private String pluginSystem;
    /** The posting's account — populated by the dispatcher tail query; 0 on the per-account history query. */
    private long accountId;
}
