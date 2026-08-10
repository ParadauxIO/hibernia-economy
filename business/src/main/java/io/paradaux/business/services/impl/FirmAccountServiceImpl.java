package io.paradaux.business.services.impl;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.hibernia.framework.exceptions.BadCommandException;
import io.paradaux.hibernia.framework.exceptions.NoPermissionException;
import io.paradaux.business.mappers.FirmAccountsMapper;
import io.paradaux.business.mappers.FirmMapper;
import io.paradaux.business.mappers.FirmRoleMapper;
import io.paradaux.business.mappers.FirmStaffMapper;
import io.paradaux.business.model.*;
import io.paradaux.business.services.FirmAccountService;
import io.paradaux.business.services.FirmService;
import io.paradaux.business.utils.NameValidator;
import io.paradaux.treasury.api.TreasuryApi;
import io.paradaux.treasury.model.economy.Account;
import io.paradaux.treasury.model.economy.AccountMember;
import io.paradaux.treasury.model.economy.AccountType;
import org.mybatis.guice.transactional.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

@Singleton
public class FirmAccountServiceImpl implements FirmAccountService {

    private final TreasuryApi treasury;
    private final FirmAccountsMapper firmAccounts;
    private final FirmMapper firms;
    private final FirmStaffMapper staff;
    private final FirmRoleMapper roles;
    private final FirmService firmService;

    /**
     * Per-firm locks serialising member/authorizer reconciliation. The sync is a
     * read-current → diff → mutate against Treasury with no DB transaction
     * spanning it, so two overlapping staff/role mutations on the same firm could
     * interleave and leave a qualifying employee removed (or a removed one
     * retained) — i.e. wrong access to firm money (ADT-12). Holding one lock per
     * firmId for the duration of a reconciliation makes the read-diff-mutate
     * atomic with respect to other reconciliations of the same firm.
     */
    private final ConcurrentMap<Integer, ReentrantLock> firmSyncLocks = new ConcurrentHashMap<>();

    @Inject
    public FirmAccountServiceImpl(
            TreasuryApi treasury,
            FirmAccountsMapper firmAccounts,
            FirmMapper firms,
            FirmStaffMapper staff,
            FirmRoleMapper roles,
            FirmService firmService
    ) {
        this.treasury = treasury;
        this.firmAccounts = firmAccounts;
        this.firms = firms;
        this.staff = staff;
        this.roles = roles;
        this.firmService = firmService;
    }

    @Transactional
    @Override
    public Account createAccount(Integer firmId, String accountName, UUID actorId) {
        if (!NameValidator.isValidAccountName(accountName)) {
            throw new BadCommandException("Account name must be 2–40 characters of letters, digits, space, underscore, hyphen, ampersand, or period.");
        }

        Firm firm = firms.getFirmById(firmId);
        // getFirmById is archived-inclusive; reject archived firms so a createAccount
        // racing a disband can't mint an orphan account against an archived firm.
        if (firm == null || Boolean.TRUE.equals(firm.getArchived())) {
            throw new BadCommandException("Firm not found");
        }

        if (!firmService.isProprietor(firmId, actorId)) {
            throw new NoPermissionException("Only the proprietor can create accounts");
        }

        // Serialise the duplicate-name check + create per firm so two concurrent
        // creates of the same name can't both pass the check and mint two accounts
        // (ADT-93). Reuses the per-firm reconciliation lock; it's reentrant, so the
        // syncAccountMembers call below (which re-acquires it) is safe.
        ReentrantLock lock = firmSyncLocks.computeIfAbsent(firmId, k -> new ReentrantLock());
        lock.lock();
        try {
            // Reject duplicate display names within the same firm. Without this,
            // a proprietor can spam-create N accounts named "Savings", each backed
            // by a distinct Treasury BUSINESS account_id — making subsequent
            // /business account deposit / withdraw by name ambiguous and the
            // member-sync churn unbounded. One batch read instead of N (ADT-36).
            List<Integer> existingIds = firmAccounts.listAccountsByFirm(firmId).stream()
                    .map(FirmAccount::getAccountId)
                    .toList();
            for (Account a : treasury.getAccountsByIds(existingIds).values()) {
                if (a.getDisplayName() != null
                        && a.getDisplayName().equalsIgnoreCase(accountName)) {
                    throw new BadCommandException("An account named '" + accountName
                            + "' already exists for this firm.");
                }
            }

            // Create Treasury account
            Account account = treasury.createAccount(AccountType.BUSINESS, actorId, accountName);

            // Register it with the firm
            firmAccounts.insertFirmAccount(firmId, account.getAccountId());

            // Sync all current staff
            syncAccountMembers(firmId, account.getAccountId());

            return account;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<Account> listAccounts(Integer firmId) {
        // One batch read instead of one getAccountById per linked account (ADT-36).
        List<Integer> accountIds = firmAccounts.listAccountsByFirm(firmId).stream()
                .map(FirmAccount::getAccountId)
                .toList();
        Map<Integer, Account> byId = treasury.getAccountsByIds(accountIds);
        return accountIds.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public List<Integer> listAccountIds(Integer firmId) {
        return firmAccounts.listAccountsByFirm(firmId).stream()
                .map(FirmAccount::getAccountId)
                .toList();
    }

    @Transactional
    @Override
    public void setDefaultAccount(Integer firmId, Integer accountId, UUID actorId) {
        Firm firm = firms.getFirmById(firmId);
        if (firm == null) {
            throw new BadCommandException("Firm not found");
        }

        if (!firmService.isProprietor(firmId, actorId)) {
            throw new NoPermissionException("Only the proprietor can set the default account");
        }

        if (!firmAccounts.isFirmAccount(firmId, accountId)) {
            throw new BadCommandException("This account does not belong to the firm");
        }

        Firm update = new Firm();
        update.setFirmId(firmId);
        update.setDefaultAccountId(accountId);
        firms.updateFirm(update);
    }

    @Transactional
    @Override
    public void archiveAccount(Integer firmId, Integer accountId, UUID actorId) {
        Firm firm = firms.getFirmById(firmId);
        if (firm == null) {
            throw new BadCommandException("Firm not found");
        }

        if (!firmService.isProprietor(firmId, actorId)) {
            throw new NoPermissionException("Only the proprietor can archive accounts");
        }

        if (!firmAccounts.isFirmAccount(firmId, accountId)) {
            throw new BadCommandException("This account does not belong to the firm");
        }

        if (firm.getDefaultAccountId() != null && firm.getDefaultAccountId().equals(accountId)) {
            throw new BadCommandException("Cannot archive the default account. Set a different default account first.");
        }

        // Archive in Treasury
        treasury.archiveAccount(accountId);

        // Remove from firm_accounts
        firmAccounts.removeFirmAccount(firmId, accountId);
    }

    @Override
    public void syncAccountMembers(Integer firmId, Integer accountId) {
        // Serialise reconciliation per firm so concurrent staff/role mutations
        // can't interleave their read-diff-mutate against Treasury (ADT-12).
        ReentrantLock lock = firmSyncLocks.computeIfAbsent(firmId, k -> new ReentrantLock());
        lock.lock();
        try {
            reconcileAccountMembers(firmId, accountId);
        } finally {
            lock.unlock();
        }
    }

    private void reconcileAccountMembers(Integer firmId, Integer accountId) {
        Firm firm = firms.getFirmById(firmId);
        if (firm == null) {
            throw new BadCommandException("Firm not found");
        }

        if (!firmAccounts.isFirmAccount(firmId, accountId)) {
            throw new BadCommandException("This account does not belong to the firm");
        }

        UUID ownerUuid = UUID.fromString(firm.getProprietorUuid());

        // Build the expected member and authorizer sets from current staff
        java.util.Set<UUID> expectedMembers = new java.util.HashSet<>();
        java.util.Set<UUID> expectedAuthorizers = new java.util.HashSet<>();

        // Owner always has full access
        expectedMembers.add(ownerUuid);
        expectedAuthorizers.add(ownerUuid);

        // Determine expected access for each employee
        for (FirmEmployee employee : staff.listCurrentEmployeesByFirm(firmId)) {
            UUID employeeUuid = UUID.fromString(employee.getPlayerUuid());
            List<FirmRolePermission> permissions = roles.getFirmRolePermissions(firmId, employee.getRoleName());

            boolean hasAdmin = permissions.stream().anyMatch(p -> p.getPermission() == RolePermission.ADMIN);
            boolean hasFinancial = permissions.stream().anyMatch(p -> p.getPermission() == RolePermission.FINANCIAL);

            if (hasAdmin) {
                expectedMembers.add(employeeUuid);
                expectedAuthorizers.add(employeeUuid);
            } else if (hasFinancial) {
                expectedMembers.add(employeeUuid);
            }
        }

        // Reconcile members: add missing, remove stale
        List<AccountMember> currentMembers = treasury.getMembers(accountId);
        java.util.Set<UUID> currentMemberUuids = currentMembers.stream()
                .map(AccountMember::getMemberUuid)
                .collect(java.util.stream.Collectors.toSet());

        for (UUID toAdd : expectedMembers) {
            if (!currentMemberUuids.contains(toAdd)) {
                treasury.addMember(accountId, toAdd, ownerUuid);
            }
        }
        for (UUID toRemove : currentMemberUuids) {
            if (!expectedMembers.contains(toRemove)) {
                treasury.removeMember(accountId, toRemove);
            }
        }

        // Reconcile authorizers: add missing, remove stale
        List<AccountMember> currentAuthorizers = treasury.getAuthorizers(accountId);
        java.util.Set<UUID> currentAuthorizerUuids = currentAuthorizers.stream()
                .map(AccountMember::getMemberUuid)
                .collect(java.util.stream.Collectors.toSet());

        for (UUID toAdd : expectedAuthorizers) {
            if (!currentAuthorizerUuids.contains(toAdd)) {
                treasury.addAuthorizer(accountId, toAdd, ownerUuid);
            }
        }
        for (UUID toRemove : currentAuthorizerUuids) {
            if (!expectedAuthorizers.contains(toRemove)) {
                treasury.removeAuthorizer(accountId, toRemove);
            }
        }
    }

    @Override
    public void syncAllFirmAccounts(Integer firmId) {
        for (FirmAccount fa : firmAccounts.listAccountsByFirm(firmId)) {
            syncAccountMembers(firmId, fa.getAccountId());
        }
    }

    @Override
    public void reassignAccountsToNewProprietor(Integer firmId, UUID newProprietorUuid) {
        // Reassign the Treasury owner of every live firm account first: owner is
        // granted access unconditionally by canAccessAccount, so without this the
        // previous owner would keep access regardless of membership. The sync
        // then makes the new proprietor a member + authorizer and removes the
        // old owner if they are no longer a qualifying employee.
        for (FirmAccount fa : firmAccounts.listAccountsByFirm(firmId)) {
            treasury.reassignOwner(fa.getAccountId(), newProprietorUuid);
        }
        syncAllFirmAccounts(firmId);
    }

    @Override
    public void addMemberToAccount(Integer firmId, Integer accountId, UUID memberUuid, UUID actorId) {
        validateAccountAccess(firmId, accountId, actorId);
        treasury.addMember(accountId, memberUuid, actorId);
        // Access is role-derived (PAR-77): reconcile immediately so the account
        // always reflects firm roles and never drifts until a manual resync.
        syncAccountMembers(firmId, accountId);
    }

    @Override
    public void removeMemberFromAccount(Integer firmId, Integer accountId, UUID memberUuid, UUID actorId) {
        validateAccountAccess(firmId, accountId, actorId);
        Firm firm = firms.getFirmById(firmId);
        if (firm == null) { // ADT-95: firm could vanish between the access check and this re-fetch
            throw new BadCommandException("Firm not found");
        }

        if (firm.getProprietorUuid().equals(memberUuid.toString())) {
            throw new BadCommandException("Cannot remove the proprietor from account membership");
        }

        treasury.removeMember(accountId, memberUuid);
        syncAccountMembers(firmId, accountId);
    }

    @Override
    public void addAuthorizerToAccount(Integer firmId, Integer accountId, UUID authorizerUuid, UUID actorId) {
        validateAccountAccess(firmId, accountId, actorId);
        treasury.addAuthorizer(accountId, authorizerUuid, actorId);
        // Access is role-derived (PAR-77): reconcile immediately so the account
        // always reflects firm roles and never drifts until a manual resync.
        syncAccountMembers(firmId, accountId);
    }

    @Override
    public void removeAuthorizerFromAccount(Integer firmId, Integer accountId, UUID authorizerUuid, UUID actorId) {
        validateAccountAccess(firmId, accountId, actorId);
        Firm firm = firms.getFirmById(firmId);
        if (firm == null) { // ADT-95: firm could vanish between the access check and this re-fetch
            throw new BadCommandException("Firm not found");
        }

        if (firm.getProprietorUuid().equals(authorizerUuid.toString())) {
            throw new BadCommandException("Cannot remove the proprietor from account authorization");
        }

        treasury.removeAuthorizer(accountId, authorizerUuid);
        syncAccountMembers(firmId, accountId);
    }

    @Override
    public List<AccountMember> getAccountMembers(Integer firmId, Integer accountId) {
        if (!firmAccounts.isFirmAccount(firmId, accountId)) {
            throw new BadCommandException("This account does not belong to the firm");
        }
        return treasury.getMembers(accountId);
    }

    @Override
    public List<AccountMember> getAccountAuthorizers(Integer firmId, Integer accountId) {
        if (!firmAccounts.isFirmAccount(firmId, accountId)) {
            throw new BadCommandException("This account does not belong to the firm");
        }
        return treasury.getAuthorizers(accountId);
    }

    @Override
    public Integer getFirmIdByAccountId(Integer accountId) {
        return firmAccounts.getFirmIdByAccountId(accountId);
    }

    private void validateAccountAccess(Integer firmId, Integer accountId, UUID actorId) {
        Firm firm = firms.getFirmById(firmId);
        if (firm == null) {
            throw new BadCommandException("Firm not found");
        }

        if (!firmService.isProprietor(firmId, actorId)) {
            throw new NoPermissionException("Only the proprietor can modify account access");
        }

        if (!firmAccounts.isFirmAccount(firmId, accountId)) {
            throw new BadCommandException("This account does not belong to the firm");
        }
    }
}
