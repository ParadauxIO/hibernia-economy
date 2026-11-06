package io.paradaux.business.model.directdebit;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/** One attempted debit under a mandate (PAR-557). */
@Data
public class DirectDebitCollection {
    private Long collectionId;
    private Long mandateId;
    private LocalDateTime periodStart;
    /** Equals {@link #periodStart} for recurring collections, {@code null} for on-demand. */
    private LocalDateTime recurringPeriod;

    private BigDecimal amount;
    private CollectionStatus status;
    private Integer attempts;
    private Long txnId;
    private String initiatorUuid;
    private String memo;
    private String failureReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
