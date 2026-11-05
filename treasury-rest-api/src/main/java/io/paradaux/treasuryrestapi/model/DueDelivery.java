package io.paradaux.treasuryrestapi.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Data;

/**
 * A webhook delivery that is due to be attempted, joined with its subscription
 * (target + secret) and the underlying posting/transaction (payload fields).
 */
@Data
public class DueDelivery {
    private long deliveryId;
    private long subscriptionId;
    private long txnId;
    private long accountId;
    private int attempts;
    private String targetUrl;
    private String keyType;
    /** The REST API key that created the subscription; null for Explorer-managed webhooks. */
    private Long apiKeyId;

    private String secret;
    // payload (the matched account's posting view of the transaction)
    private long postingId;
    private BigDecimal amount;
    private String memo;
    private String message;
    private LocalDateTime settlementTime;
    private UUID initiatorUuidBin;
    private String pluginSystem;
    // the other leg's account; null if the transaction has no other posting
    private Long counterpartyAccountId;
    private String counterpartyAccountType;
    private String counterpartyDisplayName;
    private UUID counterpartyOwnerUuidBin;

    public String getPayoutCategory() {
        var category = io.paradaux.treasury.model.economy.PayoutCategory.fromSource(pluginSystem);
        return category == null ? null : category.name();
    }
}
