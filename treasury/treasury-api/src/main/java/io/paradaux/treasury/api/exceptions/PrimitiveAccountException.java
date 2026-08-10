package io.paradaux.treasury.api.exceptions;

/**
 * Thrown when an operation that targets a primitive (system-managed)
 * government account is rejected — e.g., archiving the
 * {@code starting-balances} or {@code GovernmentFines} accounts.
 */
public class PrimitiveAccountException extends TreasuryException {
    private static final long serialVersionUID = 1L;
    private final String accountName;

    public PrimitiveAccountException(String accountName) {
        super("Cannot operate on primitive government account '" + accountName + "'");
        this.accountName = accountName;
    }

    public String getAccountName() { return accountName; }
}
