package io.paradaux.business.services;

import io.paradaux.business.model.FirmBalanceEntry;
import io.paradaux.treasury.model.Page;
import io.paradaux.treasury.model.economy.TransactionEntry;

import java.math.BigDecimal;
import java.util.UUID;

public interface FirmTransactionService {

    BigDecimal getFirmBalance(Integer firmId);

    String getFormattedBalance(Integer firmId);

    /**
     * Leaderboard of active firms ranked by the collective balance summed across
     * every treasury account each firm owns (descending; ties broken by name).
     * Backs {@code /firm baltop}. {@code page}/{@code pageSize} below 1 are clamped.
     */
    Page<FirmBalanceEntry> getFirmBalanceTop(int page, int pageSize);

    /** Formats a raw amount using Treasury's configured currency formatting. */
    String formatAmount(BigDecimal amount);

    Page<TransactionEntry> getTransactions(Integer firmId, int page, int pageSize);

    long deposit(Integer firmId, UUID playerUuid, BigDecimal amount);

    /**
     * Deposit with an optional free-text memo that is recorded as the transfer reason
     * (PAR-10). A blank/null memo is equivalent to {@link #deposit(Integer, UUID, BigDecimal)}.
     */
    long deposit(Integer firmId, UUID playerUuid, BigDecimal amount, String memo);

    long withdraw(Integer firmId, UUID playerUuid, BigDecimal amount);

    // Aggregate methods (across all firm accounts)

    BigDecimal getAggregateBalance(Integer firmId);

    String getFormattedAggregateBalance(Integer firmId);

    Page<TransactionEntry> getAggregateTransactions(Integer firmId, int page, int pageSize);

    // Multi-account support methods

    BigDecimal getAccountBalance(Integer firmId, Integer accountId);

    String getFormattedAccountBalance(Integer firmId, Integer accountId);

    Page<TransactionEntry> getAccountTransactions(Integer firmId, Integer accountId, int page, int pageSize);

    long depositToAccount(Integer firmId, Integer accountId, UUID playerUuid, BigDecimal amount);

    long withdrawFromAccount(Integer firmId, Integer accountId, UUID playerUuid, BigDecimal amount);

    // Payments ----------------------------------------------------------------
    //
    // Unlike deposit/withdraw (which only move money between a player and a firm
    // account they belong to), these move money to/from arbitrary counterparties.

    /**
     * Player pays into a firm's default account. Open to <em>any</em> player —
     * no firm-finance permission or account membership is required, since money
     * only ever flows inward. The payer must have sufficient personal funds.
     *
     * @return the new transaction id
     */
    long payIntoFirm(Integer firmId, UUID payerUuid, BigDecimal amount);

    /**
     * Player pays into a specific firm account. As {@link #payIntoFirm} but
     * targets an explicit account that must belong to the firm.
     */
    long payIntoAccount(Integer firmId, Integer accountId, UUID payerUuid, BigDecimal amount);

    /**
     * Firm pays a player out of its default account into the target's personal
     * account. {@code actorUuid} must have Treasury access to the source account
     * (and be an authorizer if it requires authorization); firm-finance
     * permission is enforced by the caller.
     *
     * @return the new transaction id
     */
    long payPlayer(Integer firmId, UUID targetPlayerUuid, UUID actorUuid, BigDecimal amount);

    /**
     * Firm pays a player out of a specific firm account. As {@link #payPlayer}
     * but targets an explicit source account that must belong to the firm.
     */
    long payPlayerFromAccount(Integer firmId, Integer accountId, UUID targetPlayerUuid, UUID actorUuid, BigDecimal amount);

    /**
     * Firm pays another firm: source firm's default account → target firm's
     * default account. {@code actorUuid} must have Treasury access to the source
     * account (and be an authorizer if required); firm-finance permission on the
     * source firm is enforced by the caller.
     *
     * @return the new transaction id
     */
    long payFirm(Integer sourceFirmId, Integer targetFirmId, UUID actorUuid, BigDecimal amount);

    /**
     * As {@link #payFirm(Integer, Integer, UUID, BigDecimal)} but records an
     * optional free-text memo as part of the transfer reason (PAR-138). A
     * blank/null memo is equivalent to the no-memo overload.
     */
    long payFirm(Integer sourceFirmId, Integer targetFirmId, UUID actorUuid, BigDecimal amount, String memo);

    /**
     * Firm pays another firm out of a specific source account into the target
     * firm's default account. As {@link #payFirm} but the source account is
     * explicit and must belong to the source firm.
     */
    long payFirmFromAccount(Integer sourceFirmId, Integer sourceAccountId, Integer targetFirmId, UUID actorUuid, BigDecimal amount);
}
