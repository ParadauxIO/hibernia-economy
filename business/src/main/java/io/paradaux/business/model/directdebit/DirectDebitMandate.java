package io.paradaux.business.model.directdebit;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/** A customer's authority for a firm to debit their personal account (PAR-557). */
@Data
public class DirectDebitMandate {
    private Long mandateId;
    private Integer firmId;
    private Integer payeeAccountId;
    private Integer payerAccountId;
    private String payerUuid;
    private DirectDebitKind kind;
    private DirectDebitPeriod period;
    private BigDecimal cap;
    /** Recurring charge; {@code null} for on-demand mandates. */
    private BigDecimal amount;

    private String reference;
    private DirectDebitStatus status;
    private String proposedByUuid;
    private LocalDateTime proposedAt;
    private LocalDateTime expiresAt;
    private LocalDateTime acceptedAt;
    private LocalDateTime endedAt;
    private String endedByUuid;
    private String endReason;
    private LocalDateTime nextDueAt;
    private Integer missedPeriods;
}
