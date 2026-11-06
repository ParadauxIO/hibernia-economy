package io.paradaux.business.internal.model.directdebit;

/** Mandate lifecycle (PAR-557). Only {@link #ACTIVE} mandates can be charged. */
public enum DirectDebitStatus {
    PENDING,
    ACTIVE,
    /** Recurring collections stopped after repeated missed periods; the customer may resume. */
    SUSPENDED,
    CANCELLED,
    REJECTED,
    EXPIRED;

    /** Whether the mandate can never be charged or resumed again. */
    public boolean isTerminal() {
        return this == CANCELLED || this == REJECTED || this == EXPIRED;
    }
}
