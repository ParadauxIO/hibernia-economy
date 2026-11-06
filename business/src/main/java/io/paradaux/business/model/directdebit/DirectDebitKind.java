package io.paradaux.business.model.directdebit;

/** How a mandate is charged (PAR-557). */
public enum DirectDebitKind {
    /** A fixed amount collected automatically once per period. */
    RECURRING,
    /** The firm charges ad hoc while the period total stays within the cap. */
    ON_DEMAND
}
