package io.paradaux.business.model.directdebit;

/** Outcome of one direct-debit collection (PAR-557). */
public enum CollectionStatus {
    /** Counted against the cap; the ledger transfer has not been settled yet. */
    RESERVED,
    COLLECTED,
    FAILED
}
