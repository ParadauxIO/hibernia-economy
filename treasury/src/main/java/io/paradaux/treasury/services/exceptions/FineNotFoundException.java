package io.paradaux.treasury.services.exceptions;

/** Thrown when {@code revokeFine} can't find the requested id. */
public class FineNotFoundException extends TreasuryException {
    private static final long serialVersionUID = 1L;
    private final long fineId;

    public FineNotFoundException(long fineId) {
        super("Fine " + fineId + " not found");
        this.fineId = fineId;
    }

    public long getFineId() {
        return fineId;
    }
}
