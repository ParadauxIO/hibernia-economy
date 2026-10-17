package io.paradaux.treasury.api.impl;

import com.google.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import io.paradaux.treasury.api.TaxApi;
import io.paradaux.treasury.api.TreasuryApi;
import io.paradaux.treasury.model.Page;
import io.paradaux.treasury.model.economy.*;
import io.paradaux.treasury.services.AccountService;
import io.paradaux.treasury.services.DataExportService;
import io.paradaux.treasury.services.LedgerService;
import io.paradaux.treasury.services.MembershipService;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
public class TreasuryApiImpl implements TreasuryApi {

    private final AccountService accountService;
    private final MembershipService membershipService;
    private final LedgerService ledgerService;
    private final DataExportService dataExportService;
    private final TaxApi taxApi;

    @Inject
    public TreasuryApiImpl(AccountService accountService,
                           MembershipService membershipService,
                           LedgerService ledgerService,
                           DataExportService dataExportService,
                           TaxApi taxApi) {
        this.accountService = accountService;
        this.membershipService = membershipService;
        this.ledgerService = ledgerService;
        this.dataExportService = dataExportService;
        this.taxApi = taxApi;
    }

    // ---- Balance ----

    @Override
    public BigDecimal getBalanceByAccountId(int accountId) {
        return accountService.getBalanceReadOnly(accountId);
    }

    @Override
    public Map<Integer, BigDecimal> getBalancesByIds(Collection<Integer> accountIds) {
        return accountService.getBalancesByIds(accountIds);
    }

    @Override
    public BigDecimal getBalanceByOwnerUuid(UUID ownerUuid) {
        return accountService.getBalanceByOwnerUuid(ownerUuid);
    }

    @Override
    public boolean hasFunds(int accountId, BigDecimal amount) {
        return accountService.hasFunds(accountId, amount);
    }

    // ---- Account lookups ----

    @Override
    public Account getAccountByUUID(UUID ownerUuid) {
        return accountService.getAccountByUUID(ownerUuid);
    }

    @Override
    public Account getAccountById(int accountId) {
        return accountService.getAccountById(accountId);
    }

    @Override
    public Map<Integer, Account> getAccountsByIds(Collection<Integer> accountIds) {
        return accountService.getAccountsByIds(accountIds);
    }

    @Override
    public List<Account> getAccountsByOwner(UUID ownerUuid) {
        return accountService.getAccountsByOwner(ownerUuid);
    }

    @Override
    public List<Account> getAccountsByTypeAndOwner(AccountType accountType, UUID ownerUuid) {
        return accountService.getAccountsByTypeAndOwner(accountType, ownerUuid);
    }

    @Override
    public List<Account> getAccountsByMember(UUID memberUuid) {
        return accountService.getAccountsByMember(memberUuid);
    }

    @Override
    public boolean hasAccountByAccountId(int accountId) {
        return accountService.hasAccountByAccountId(accountId);
    }

    @Override
    public boolean hasAccountByOwnerUuid(UUID ownerUuid) {
        return accountService.hasAccountByOwnerUuid(ownerUuid);
    }

    // ---- Access control checks ----

    @Override
    public boolean isAccountMember(UUID uuid, int accountId) {
        return accountService.isAccountMember(uuid, accountId);
    }

    @Override
    public boolean isOwnerForAccountId(UUID uuid, int accountId) {
        return accountService.isOwnerForAccountId(uuid, accountId);
    }

    @Override
    public boolean canAccessAccount(UUID uuid, int accountId) {
        return accountService.canAccessAccount(uuid, accountId);
    }

    @Override
    public boolean accountHasBalance(UUID uuid, int accountId) {
        return accountService.accountHasBalance(uuid, accountId);
    }

    // ---- Account lifecycle ----

    @Override
    public Account resolveOrCreatePersonal(UUID playerUuid) {
        return ledgerService.resolveOrCreatePersonal(playerUuid);
    }

    @Override
    public Account createAccount(AccountType accountType, UUID ownerUuid, String displayName) {
        return accountService.createAccount(accountType, ownerUuid, displayName);
    }

    @Override
    public void updateAccount(Account account) {
        accountService.updateAccount(account);
    }

    @Override
    public void reassignOwner(int accountId, UUID newOwnerUuid) {
        accountService.reassignOwner(accountId, newOwnerUuid);
    }

    @Override
    public void archiveAccount(int accountId) {
        accountService.archiveAccount(accountId);
    }

    @Override
    public void unarchiveAccount(int accountId) {
        accountService.unarchiveAccount(accountId);
    }

    // ---- Member / authorizer management ----

    @Override
    public void addMember(int accountId, UUID memberUuid, UUID addedByUuid) {
        membershipService.addMember(accountId, memberUuid, addedByUuid);
    }

    @Override
    public void removeMember(int accountId, UUID memberUuid) {
        membershipService.removeMember(accountId, memberUuid);
    }

    @Override
    public List<AccountMember> getMembers(int accountId) {
        return membershipService.getMembers(accountId);
    }

    @Override
    public void addAuthorizer(int accountId, UUID authorizerUuid, UUID addedByUuid) {
        membershipService.addAuthorizer(accountId, authorizerUuid, addedByUuid);
    }

    @Override
    public void removeAuthorizer(int accountId, UUID authorizerUuid) {
        membershipService.removeAuthorizer(accountId, authorizerUuid);
    }

    @Override
    public List<AccountMember> getAuthorizers(int accountId) {
        return membershipService.getAuthorizers(accountId);
    }

    // ---- Transaction history ----

    @Override
    public Page<TransactionEntry> getTransactionHistory(int accountId, int offset, int limit) {
        return ledgerService.getTransactionHistory(accountId, offset, limit);
    }

    @Override
    public Page<TransactionEntry> getTransactionHistory(Collection<Integer> accountIds, int offset, int limit) {
        return ledgerService.getTransactionHistory(accountIds, offset, limit);
    }

    @Override
    public String exportTransactionsFor(int accountId) {
        return dataExportService.exportTransactionsFor(accountId);
    }

    @Override
    public LedgerTxn getTransaction(long txnId) {
        return ledgerService.getTransaction(txnId);
    }

    @Override
    public List<LedgerPosting> getPostingsForTransaction(long txnId) {
        return ledgerService.getPostingsForTransaction(txnId);
    }

    // ---- Government account lookup ----

    @Override
    public Account getGovernmentAccountByName(String name) {
        return accountService.getGovernmentAccountByName(name);
    }

    // ---- Transfers ----

    @Override
    public long transfer(TransferRequest transferRequest) {
        return ledgerService.transfer(transferRequest);
    }

    @Override
    public java.util.OptionalLong sweepAll(int fromAccountId, int toAccountId, String memo, UUID initiator, String sourcePlugin) {
        return ledgerService.sweepAll(fromAccountId, toAccountId, memo, initiator, sourcePlugin);
    }

    // ---- Balance top ----

    @Override
    public Page<BalanceEntry> getTopBalances(int offset, int limit) {
        return accountService.getTopBalances(offset, limit);
    }

    // ---- Formatting ----

    @Override
    public String formatAmount(BigDecimal amount) {
        return accountService.formatAmount(amount);
    }

    @Override
    public String getCurrencyNameSingular() {
        return accountService.getCurrencyNameSingular();
    }

    @Override
    public String getCurrencyNamePlural() {
        return accountService.getCurrencyNamePlural();
    }

    // ---- Tax ----

    @Override
    public TaxApi getTaxApi() {
        return taxApi;
    }
}
