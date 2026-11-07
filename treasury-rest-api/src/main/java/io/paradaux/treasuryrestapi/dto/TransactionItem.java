package io.paradaux.treasuryrestapi.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * One account's posting view of a transaction. {@code amount} is signed from
 * that account's side; {@code counterparty} is the account on the other leg,
 * or null if the transaction has no other posting.
 */
public record TransactionItem(
        long postingId,
        long txnId,
        String amount,
        String memo,
        String message,
        Instant settledAt,
        String initiatorUuid,
        String pluginSystem,
        Counterparty counterparty,

        @Schema(
                description = "Purpose derived from the originating payment source; null for mixed or unknown sources.",
                allowableValues = {"EMPLOYEE", "AUCTION_PROCEEDS"},
                nullable = true)
        String payoutCategory) {

    /**
     * The other side of the transaction. {@code accountType} is PERSONAL |
     * BUSINESS | GOVERNMENT | SYSTEM; {@code ownerUuid} is the account's
     * {@code owner_uuid_bin} (the player for PERSONAL, the creating player for
     * BUSINESS).
     */
    public record Counterparty(long accountId, String accountType, String displayName, String ownerUuid) {

        /** Null when the row carries no counterparty (no other leg). */
        public static Counterparty of(Long accountId, String accountType, String displayName, UUID ownerUuid) {
            if (accountId == null) return null;
            return new Counterparty(
                    accountId, accountType, displayName, ownerUuid != null ? ownerUuid.toString() : null);
        }
    }
}
