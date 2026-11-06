package io.paradaux.treasury.services.exceptions;

/**
 * Base type for internal Treasury government-service runtime exceptions. Distinguished from the
 * grab-bag of {@link IllegalStateException} / {@link IllegalArgumentException}
 * that the implementation otherwise relies on, so handlers can pattern-match
 * specific failure modes without scraping {@code getMessage()}.
 */
public abstract class TreasuryException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    protected TreasuryException(String message) {
        super(message);
    }

    protected TreasuryException(String message, Throwable cause) {
        super(message, cause);
    }
}
