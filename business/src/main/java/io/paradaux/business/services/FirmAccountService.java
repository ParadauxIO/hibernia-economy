package io.paradaux.business.services;

import io.paradaux.treasury.model.economy.Account;
import io.paradaux.treasury.model.economy.AccountMember;

import java.util.List;
import java.util.UUID;

public interface FirmAccountService {

    /**
     * Sets (or clears with null) a letters-only shop alias; proprietor only.
     * Previous aliases stop resolving but remain reserved to the same account.
     */
    void setShopAlias(int firmId, int accountId, String alias, UUID actorId);

    String getShopAlias(int firmId, int accountId);

    /** Resolves a live firm's alias to its Treasury account id, or null. */
    Integer resolveShopAlias(String alias);


    /**
     * Creates a new Treasury account for the firm and registers it.
     * Automatically syncs current staff members/authorizers based on their roles.
     */
    Account createAccount(Integer firmId, String accountName, UUID actorId);

    /**
     * Lists all Treasury accounts owned by the firm.
     */
    List<Account> listAccounts(Integer firmId);

    /**
     * Lists the Treasury account IDs currently linked to the firm. A lightweight
     * read for callers that only need the IDs (e.g. balance-tax allocation) and
     * shouldn't reach the persistence layer themselves.
     */
    List<Integer> listAccountIds(Integer firmId);

    /**
     * Sets the default account for the firm.
     */
    void setDefaultAccount(Integer firmId, Integer accountId, UUID actorId);

    /**
     * Archives a firm account (both in Treasury and removes from firm_accounts).
     */
    void archiveAccount(Integer firmId, Integer accountId, UUID actorId);

    /**
     * Syncs all current staff to the specified account based on their roles:
     * - Owner: always member + authorizer
     * - ADMIN permission: member + authorizer
     * - FINANCIAL permission: member only
     * Removes access for staff who no longer qualify.
     */
    void syncAccountMembers(Integer firmId, Integer accountId);

    /**
     * Syncs all firm accounts (calls syncAccountMembers for each).
     * Preferred over calling syncAccountMembers in a loop from callers.
     */
    void syncAllFirmAccounts(Integer firmId);

    /**
     * Repairs membership for all persisted live account links, including work
     * interrupted by a process crash. Failures are logged per account and retried
     * on the next pass; one broken account does not block the rest.
     */
    void reconcileLinkedAccounts();

    /**
     * Hands every live Treasury account of the firm to a new proprietor:
     * reassigns each account's Treasury owner to {@code newProprietorUuid} and
     * re-syncs members/authorizers. Must be called <em>after</em> the firm's
     * {@code proprietor_uuid} has been updated, so the sync derives the new
     * proprietor as owner (member + authorizer) and drops the previous owner
     * (unless they remain a qualifying employee). Without this the new owner is
     * locked out of the account and the old owner retains access (PAR-141).
     */
    void reassignAccountsToNewProprietor(Integer firmId, UUID newProprietorUuid);

    /**
     * Adds a player as a member to the specified account.
     */
    void addMemberToAccount(Integer firmId, Integer accountId, UUID memberUuid, UUID actorId);

    /**
     * Removes a player as a member from the specified account.
     */
    void removeMemberFromAccount(Integer firmId, Integer accountId, UUID memberUuid, UUID actorId);

    /**
     * Adds a player as an authorizer to the specified account.
     */
    void addAuthorizerToAccount(Integer firmId, Integer accountId, UUID authorizerUuid, UUID actorId);

    /**
     * Removes a player as an authorizer from the specified account.
     */
    void removeAuthorizerFromAccount(Integer firmId, Integer accountId, UUID authorizerUuid, UUID actorId);

    /**
     * Gets all members for an account.
     */
    List<AccountMember> getAccountMembers(Integer firmId, Integer accountId);

    /**
     * Gets all authorizers for an account.
     */
    List<AccountMember> getAccountAuthorizers(Integer firmId, Integer accountId);

    /**
     * Looks up the firm ID that owns a given Treasury account.
     *
     * @param accountId the Treasury account ID
     * @return the firm ID, or {@code null} if the account is not a registered firm account
     */
    Integer getFirmIdByAccountId(Integer accountId);
}
