package io.paradaux.treasury.api.exceptions;

import java.util.UUID;

/**
 * Thrown by {@code issueFine} when the targeted player's account doesn't have
 * enough funds to cover the fine. Wraps the underlying {@link IllegalStateException}
 * raised by the ledger service so handlers can render an i18n message without
 * scraping the message text.
 */
public class InsufficientFineFundsException extends TreasuryException {
    private static final long serialVersionUID = 1L;
    private final UUID playerUuid;
    private final Integer debtorAccountId;

    public InsufficientFineFundsException(UUID playerUuid, Throwable cause) {
        super("Player " + playerUuid + " has insufficient funds to cover the fine", cause);
        this.playerUuid = playerUuid;
        this.debtorAccountId = null;
    }

    /** For fines whose debtor is an account rather than a player (e.g. a firm). */
    public InsufficientFineFundsException(int debtorAccountId, Throwable cause) {
        super("Account " + debtorAccountId + " has insufficient funds to cover the fine", cause);
        this.playerUuid = null;
        this.debtorAccountId = debtorAccountId;
    }

    /** The fined player, or {@code null} if the debtor was an account (firm fine). */
    public UUID getPlayerUuid() { return playerUuid; }

    /** The debited account, or {@code null} if the debtor was a player. */
    public Integer getDebtorAccountId() { return debtorAccountId; }
}
