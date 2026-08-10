package io.paradaux.treasury.api.exceptions;

/** Thrown when {@code revokeFine} is called on a fine that is already revoked. */
public class FineAlreadyRevokedException extends TreasuryException {
    private static final long serialVersionUID = 1L;
    private final long fineId;

    public FineAlreadyRevokedException(long fineId) {
        super("Fine " + fineId + " is already revoked");
        this.fineId = fineId;
    }

    public long getFineId() { return fineId; }
}
