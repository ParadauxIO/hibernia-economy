package io.paradaux.treasury.api;

import io.paradaux.treasury.model.Page;
import io.paradaux.treasury.model.economy.*;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Public, in-process Treasury API.
 *
 * <p><b>Nullability contract (ADT-85):</b> unless a method is annotated
 * {@link Nullable}, its return value is non-null. Collection, {@link List},
 * {@link Map} and {@link Page} returns are always non-null but may be empty;
 * a single-object lookup that can miss is annotated {@code @Nullable} and
 * documents the miss case. Reference parameters are required (non-null) unless
 * stated otherwise.
 */
public interface TreasuryApi {

    // ---- Balance ----

    /** Never null: an account with no balance row reads as {@link BigDecimal#ZERO}. */
    BigDecimal getBalanceByAccountId(int accountId);
    /** Never null: an unknown owner / missing balance reads as {@link BigDecimal#ZERO}. */
    BigDecimal getBalanceByOwnerUuid(UUID ownerUuid);

    /**
     * Batch variant of {@link #getBalanceByAccountId(int)}: reads many balances in
     * one round-trip. Accounts with no balance row are absent from the returned
     * map — callers should treat a missing key as {@link BigDecimal#ZERO}. An
     * empty input yields an empty map.
     */
    Map<Integer, BigDecimal> getBalancesByIds(Collection<Integer> accountIds);

    /** Returns true if the account's balance >= amount. */
    boolean hasFunds(int accountId, BigDecimal amount);

    // ---- Account lookups ----

    /** @return the owner's PERSONAL account, or {@code null} if they have none. */
    @Nullable Account getAccountByUUID(UUID ownerUuid);
    /** @return the account, or {@code null} if no account with that id exists. */
    @Nullable Account getAccountById(int accountId);

    /**
     * Batch variant of {@link #getAccountById(int)}: fetches many accounts in one
     * round-trip, keyed by account id. Ids with no matching account are absent
     * from the map. An empty input yields an empty map.
     */
    Map<Integer, Account> getAccountsByIds(Collection<Integer> accountIds);

    List<Account> getAccountsByOwner(UUID ownerUuid);
    List<Account> getAccountsByTypeAndOwner(AccountType accountType, UUID ownerUuid);

    /**
     * @deprecated Use {@link #getAccountsByTypeAndOwner(AccountType, UUID)} instead.
     * @throws IllegalArgumentException if {@code accountType} is null or not a known
     *         {@link AccountType} (ADT deprecated-overload-npe — previously an
     *         unguarded {@code valueOf} surfaced a raw NPE / enum error).
     */
    @Deprecated
    default List<Account> getAccountsByTypeAndOwner(String accountType, UUID ownerUuid) {
        if (accountType == null) {
            throw new IllegalArgumentException("accountType must not be null");
        }
        final AccountType type;
        try {
            type = AccountType.valueOf(accountType.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("Unknown account type: " + accountType);
        }
        return getAccountsByTypeAndOwner(type, ownerUuid);
    }
    List<Account> getAccountsByMember(UUID memberUuid);

    boolean hasAccountByAccountId(int accountId);
    boolean hasAccountByOwnerUuid(UUID ownerUuid);

    // ---- Access control checks ----

    boolean isAccountMember(UUID uuid, int accountId);
    boolean isOwnerForAccountId(UUID uuid, int accountId);
    boolean canAccessAccount(UUID uuid, int accountId);
    boolean accountHasBalance(UUID uuid, int accountId);

    // ---- Account lifecycle ----

    /**
     * Returns the PERSONAL account for the given player, creating and funding it
     * with the configured starting balance if it does not yet exist.
     */
    Account resolveOrCreatePersonal(UUID playerUuid);

    /**
     * Creates a new account with a zero balance.
     * Use this for BUSINESS, GOVERNMENT, or other non-PERSONAL account types.
     */
    Account createAccount(AccountType accountType, UUID ownerUuid, String displayName);

    /** Updates mutable account fields (displayName, requiresAuthorization, archived, overdraft, creditLimit). */
    void updateAccount(Account account);

    /**
     * Reassigns the owner of an existing (non-PERSONAL) account. The owner is
     * granted access unconditionally by {@link #canAccessAccount}, so this is
     * how a BUSINESS firm account is handed to a new proprietor on transfer.
     */
    void reassignOwner(int accountId, UUID newOwnerUuid);

    void archiveAccount(int accountId);
    void unarchiveAccount(int accountId);

    // ---- Member / authorizer management ----

    void addMember(int accountId, UUID memberUuid, UUID addedByUuid);
    void removeMember(int accountId, UUID memberUuid);
    List<AccountMember> getMembers(int accountId);

    void addAuthorizer(int accountId, UUID authorizerUuid, UUID addedByUuid);
    void removeAuthorizer(int accountId, UUID authorizerUuid);
    List<AccountMember> getAuthorizers(int accountId);

    // ---- Transaction history ----

    /** Paginated transaction history for an account (most recent first). */
    Page<TransactionEntry> getTransactionHistory(int accountId, int offset, int limit);

    /**
     * Merged, paginated transaction history across several accounts (most recent
     * first), with a {@code totalCount} spanning all of them. Lets a caller page a
     * firm's whole transaction history in one query instead of over-fetching from
     * each account and sorting in memory. An empty input yields an empty page.
     */
    Page<TransactionEntry> getTransactionHistory(Collection<Integer> accountIds, int offset, int limit);

    /** Exports all transactions for an account as CSV, uploads to bytebin, returns the URL. */
    String exportTransactionsFor(int accountId);

    /** Single transaction lookup by ID. @return the transaction, or {@code null} if no txn has that id. */
    @Nullable LedgerTxn getTransaction(long txnId);

    /** All postings belonging to a transaction. */
    List<LedgerPosting> getPostingsForTransaction(long txnId);

    // ---- Government account lookup ----

    /**
     * Returns the GOVERNMENT account with the given display name, or {@code null} if not found.
     *
     * <p>Use this when a consuming plugin needs to route a payment to a specific named
     * government account (e.g. a configurable tax-destination account).
     */
    @Nullable Account getGovernmentAccountByName(String name);

    // ---- Transfers ----

    /** Direct account-to-account transfer. */
    long transfer(TransferRequest transferRequest);

    /**
     * Sweeps the <em>freshly locked</em> positive balance of {@code fromAccountId}
     * into {@code toAccountId} in a single ledger transaction.
     *
     * <p>Unlike {@link #transfer(TransferRequest)}, the moved amount is not a
     * caller-supplied snapshot: it is read under the same {@code SELECT ... FOR UPDATE}
     * lock that guards the move, in the identical ascending-account-id lock order used
     * by every transfer, so a concurrent credit or debit that lands between a caller's
     * snapshot and this call can neither leave a residual behind nor overdraw the source
     * (conservation stays exact). The balance delta is still applied solely by the
     * {@code trg_postings_ai} DB trigger — never an application-side balance UPDATE.
     *
     * <p>Intended for firm-disband draining, which must not trust an
     * {@code account_balances_mat} snapshot. Returns the transfer's txn id, or an empty
     * {@link OptionalLong} when the locked balance is zero-or-negative (nothing to move),
     * so the caller can archive the account without emitting an empty transfer.
     *
     * @param sourcePlugin ledger provenance ({@code plugin_system}) recorded on the sweep
     *                     txn — the calling plugin's identifier, so disband-drain rows keep
     *                     the same attribution the caller's ordinary transfers carry
     * @return the txn id of the sweep, or empty if the locked balance was not positive
     */
    OptionalLong sweepAll(int fromAccountId, int toAccountId, String memo, UUID initiator, String sourcePlugin);

    // ---- Balance top ----

    /** Paginated top personal account balances. */
    Page<BalanceEntry> getTopBalances(int offset, int limit);

    // ---- Formatting ----

    String formatAmount(BigDecimal amount);
    String getCurrencyNameSingular();
    String getCurrencyNamePlural();

    // ---- Tax ----

    /**
     * Returns the tax collection API.
     *
     * <p>Use this to collect taxes from accounts and to introspect the
     * scheduled cycle configuration. See {@link TaxApi} for full documentation.
     *
     * <p>Example — charge a 3 % sale tax when a Realty plot is purchased:
     * <pre>{@code
     * treasuryApi.getTaxApi().collectRateTax(
     *     buyerAccountId,
     *     salePrice,
     *     new BigDecimal("0.03"),
     *     "realty-sale-tax",
     *     "Plot Purchase Tax: " + regionId,
     *     buyerUuid,
     *     "realty");
     * }</pre>
     */
    TaxApi getTaxApi();
}
