package io.paradaux.treasury.services;

import io.paradaux.treasury.model.Page;
import io.paradaux.treasury.model.economy.Account;
import io.paradaux.treasury.model.economy.AccountType;
import io.paradaux.treasury.model.economy.BalanceEntry;
import io.paradaux.treasury.model.economy.EconomySummary;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface AccountService {

    // ---- Balance ----

    /** Non-locking balance read for read-only calls. Locking is done internally by transfer(). */
    BigDecimal getBalanceReadOnly(int accountId);

    /** Non-locking batch balance read; balances keyed by account id (missing rows absent). */
    Map<Integer, BigDecimal> getBalancesByIds(Collection<Integer> accountIds);

    BigDecimal getBalanceByOwnerUuid(UUID ownerUuid);

    boolean hasFunds(int accountId, BigDecimal amount);

    // ---- Account lookups ----

    Account getAccountByUUID(UUID ownerUuid);
    Account getAccountById(int accountId);

    /** Batch account lookup; accounts keyed by id (missing ids absent). */
    Map<Integer, Account> getAccountsByIds(Collection<Integer> accountIds);

    List<Account> getAccountsByOwner(UUID ownerUuid);
    List<Account> getAccountsByTypeAndOwner(AccountType accountType, UUID ownerUuid);
    List<Account> getAccountsByMember(UUID memberUuid);

    boolean hasAccountByAccountId(int accountId);
    boolean hasAccountByOwnerUuid(UUID ownerUuid);

    // ---- Access control checks ----

    boolean isAccountMember(UUID uuid, int accountId);
    boolean isOwnerForAccountId(UUID uuid, int accountId);
    boolean canAccessAccount(UUID uuid, int accountId);
    boolean accountHasBalance(UUID uuid, int accountId);

    // ---- Personal account convenience ----

    /** Returns true if a PERSONAL account exists for this UUID. */
    boolean hasPersonalAccount(UUID ownerUuid);

    /**
     * Cache-aware, read-only resolve of a player's PERSONAL account id, or
     * {@code null} if they have none. Never creates an account. This is the single
     * lookup the read paths (balance, hasAccount) share so a known player resolves
     * from memory instead of re-querying — important on the synchronous Vault bridge
     * that runs on the caller's (often main) thread.
     */
    Integer findPersonalAccountId(UUID ownerUuid);

    /** Returns the PERSONAL account ID, throwing if it does not exist. */
    int getPersonalAccountId(UUID ownerUuid);

    /**
     * Returns the PERSONAL account ID, creating a bare (unseeded) account if missing.
     * Seeding happens separately via {@code LedgerService.resolveOrCreatePersonal()}.
     */
    int getOrCreatePersonalAccountId(UUID ownerUuid);

    /**
     * Returns the SYSTEM account ID for the given plugin name, creating one atomically if missing.
     */
    int getOrCreateSystemAccountId(String pluginName, UUID owner);

    // ---- Account lifecycle ----

    /**
     * Creates a new account with a zero balance.
     * Use this for BUSINESS, GOVERNMENT, or other non-PERSONAL account types.
     */
    Account createAccount(AccountType accountType, UUID ownerUuid, String displayName);

    /** Updates mutable account fields (displayName, requiresAuthorization, archived, overdraft, creditLimit). */
    void updateAccount(Account account);

    /**
     * Reassigns the owner of an existing account. Owner is part of access
     * control ({@code canAccessAccount} grants the owner unconditionally), so
     * this is the lever for transferring a BUSINESS firm account to a new
     * proprietor. Not for PERSONAL accounts.
     */
    void reassignOwner(int accountId, UUID newOwnerUuid);

    void archiveAccount(int accountId);

    void unarchiveAccount(int accountId);

    /** Returns the GOVERNMENT account with the given display name, or null if not found. */
    Account getGovernmentAccountByName(String name);

    /**
     * Returns true if a non-archived GOVERNMENT account with the given display
     * name exists. A cheap existence check for disambiguating bare-name targets
     * that also match a player (see PAR-142) without fetching the whole account.
     */
    boolean governmentAccountExists(String name);

    /** Returns the non-archived BUSINESS account with the given display name, or null if not found. */
    Account getBusinessAccountByName(String name);

    /**
     * Resolves a BUSINESS account from a single {@code word()}-tokenised argument:
     * the bare token first, then the canonical {@code "<token> Corporate Account"}
     * display-name convention (FirmServiceImpl). Lets a user type just the firm name
     * without knowing the suffix. Returns null if neither matches.
     */
    default Account resolveBusinessAccountByToken(String token) {
        Account account = getBusinessAccountByName(token);
        if (account == null) {
            account = getBusinessAccountByName(token + " Corporate Account");
        }
        return account;
    }

    /** Returns all non-archived GOVERNMENT accounts sorted by display name. */
    List<Account> listGovernmentAccounts();

    // ---- Vault redirects ----

    /**
     * Returns the redirected {@code account_id} for a UUID, or empty
     * if no redirect is configured. Used by the Vault bridge to route
     * legacy "player" UUIDs (e.g. DCGovernment, GovReserve) onto their
     * canonical GOVERNMENT accounts. Native callers should not use this
     * — they target accounts by ID directly.
     */
    java.util.Optional<Integer> findRedirectedAccount(UUID uuid);

    // ---- Balance top ----

    Page<BalanceEntry> getTopBalances(int offset, int limit);

    // ---- Economy summary ----

    /** Top-level money supply by account type (active PERSONAL/BUSINESS/GOVERNMENT, SYSTEM excluded). */
    EconomySummary getEconomySummary();

    // ---- Formatting ----

    String formatAmount(BigDecimal amount);
    String getCurrencyNameSingular();
    String getCurrencyNamePlural();
}
