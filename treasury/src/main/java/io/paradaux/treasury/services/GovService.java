package io.paradaux.treasury.services;

import io.paradaux.treasury.model.economy.Account;
import io.paradaux.treasury.model.economy.GovernmentFine;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Internal government-account and fine operations; not a published plugin API. */
public interface GovService {

    // ---- Account management ----

    /** Creates a new department GOVERNMENT account. Throws if a GOVERNMENT account with that name already exists. */
    Account createDepartmentAccount(String name, UUID createdBy);

    /**
     * Archives the named government account.
     * @throws io.paradaux.treasury.services.exceptions.PrimitiveAccountException
     *         if the account is system-managed
     * @throws io.paradaux.treasury.services.exceptions.GovAccountNotFoundException
     *         if the account does not exist
     */
    void archiveDepartmentAccount(String name, UUID archivedBy);

    /** Returns all non-archived GOVERNMENT accounts, sorted by display name. */
    List<Account> listGovernmentAccounts();

    // ---- Fine management ----

    /**
     * Issues a fine by transferring {@code amount} from the player's PERSONAL account to the
     * configured default fines account, then records the fine in {@code government_fines}.
     * Convenience for {@link #issueFine(UUID, int, BigDecimal, String, UUID)} that targets
     * {@code government.fines-account} from config.
     * @throws io.paradaux.treasury.services.exceptions.GovAccountNotFoundException
     *         if the configured fines account does not exist
     * @throws io.paradaux.treasury.services.exceptions.InsufficientFineFundsException
     *         if the player cannot afford the fine
     */
    GovernmentFine issueFine(UUID player, BigDecimal amount, String reason, UUID issuedBy);

    /**
     * Issues a fine paid into a specific GOVERNMENT account: transfers {@code amount} from the
     * player's PERSONAL account to {@code govAccountId} and records the fine (with that account)
     * in {@code government_fines}. The fine remembers its account, so a later
     * {@link #revokeFine(long, UUID)} refunds from the same account.
     *
     * @throws io.paradaux.treasury.services.exceptions.GovAccountNotFoundException
     *         if {@code govAccountId} is not an existing GOVERNMENT account
     * @throws io.paradaux.treasury.services.exceptions.InsufficientFineFundsException
     *         if the player can't afford the fine
     */
    GovernmentFine issueFine(UUID player, int govAccountId, BigDecimal amount, String reason, UUID issuedBy);

    /**
     * Issues a fine against an arbitrary account (e.g. a firm's BUSINESS account):
     * transfers {@code amount} from {@code debtorAccountId} to {@code govAccountId}
     * and records the owner for PERSONAL accounts, or no player for other types. The fine remembers its
     * debtor account, so a later {@link #revokeFine(long, UUID)} refunds it.
     *
     * @throws IllegalArgumentException if {@code debtorAccountId} is not an existing account
     * @throws io.paradaux.treasury.services.exceptions.GovAccountNotFoundException
     *         if {@code govAccountId} is not an existing GOVERNMENT account
     * @throws io.paradaux.treasury.services.exceptions.InsufficientFineFundsException
     *         if the debtor account can't afford the fine
     */
    GovernmentFine issueFine(int debtorAccountId, int govAccountId, BigDecimal amount, String reason, UUID issuedBy);

    /**
     * Revokes a previously issued fine by reversing the transfer.
     * @throws io.paradaux.treasury.services.exceptions.FineNotFoundException
     *         if the fine does not exist
     * @throws io.paradaux.treasury.services.exceptions.FineAlreadyRevokedException
     *         if the fine is already revoked
     * @throws io.paradaux.treasury.services.exceptions.GovAccountNotFoundException
     *         if the fine's government account no longer exists
     */
    GovernmentFine revokeFine(long fineId, UUID revokedBy);

    /** Returns the fine, or {@code null} if it does not exist. */
    GovernmentFine getFine(long fineId);

    List<GovernmentFine> getPlayerFines(UUID player);

    List<GovernmentFine> getActivePlayerFines(UUID player);
}
