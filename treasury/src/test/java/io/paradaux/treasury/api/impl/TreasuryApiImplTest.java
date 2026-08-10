package io.paradaux.treasury.api.impl;

import io.paradaux.treasury.api.TaxApi;
import io.paradaux.treasury.model.Page;
import io.paradaux.treasury.model.economy.Account;
import io.paradaux.treasury.model.economy.AccountMember;
import io.paradaux.treasury.model.economy.AccountType;
import io.paradaux.treasury.model.economy.BalanceEntry;
import io.paradaux.treasury.model.economy.LedgerPosting;
import io.paradaux.treasury.model.economy.LedgerTxn;
import io.paradaux.treasury.model.economy.TransactionEntry;
import io.paradaux.treasury.model.economy.TransferRequest;
import io.paradaux.treasury.services.AccountService;
import io.paradaux.treasury.services.DataExportService;
import io.paradaux.treasury.services.LedgerService;
import io.paradaux.treasury.services.MembershipService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TreasuryApiImplTest {

    @Mock AccountService accountService;
    @Mock MembershipService membershipService;
    @Mock LedgerService ledgerService;
    @Mock DataExportService dataExportService;
    @Mock TaxApiImpl taxApi;

    private TreasuryApiImpl api;

    @BeforeEach
    void setUp() {
        api = new TreasuryApiImpl(accountService, membershipService, ledgerService, dataExportService, taxApi);
    }

    // ---- Balance ----

    @Test
    void getBalanceByAccountId_delegates() {
        when(accountService.getBalanceReadOnly(7)).thenReturn(new BigDecimal("100.00"));
        assertThat(api.getBalanceByAccountId(7)).isEqualByComparingTo("100.00");
    }

    @Test
    void getBalanceByOwnerUuid_delegates() {
        UUID u = UUID.randomUUID();
        when(accountService.getBalanceByOwnerUuid(u)).thenReturn(new BigDecimal("42.00"));
        assertThat(api.getBalanceByOwnerUuid(u)).isEqualByComparingTo("42.00");
    }

    @Test
    void hasFunds_delegates() {
        when(accountService.hasFunds(1, BigDecimal.TEN)).thenReturn(true);
        assertThat(api.hasFunds(1, BigDecimal.TEN)).isTrue();
    }

    // ---- Account lookups ----

    @Test
    void getAccountByUUID_delegates() {
        UUID u = UUID.randomUUID();
        Account a = new Account();
        when(accountService.getAccountByUUID(u)).thenReturn(a);
        assertThat(api.getAccountByUUID(u)).isSameAs(a);
    }

    @Test
    void getAccountById_delegates() {
        Account a = new Account();
        when(accountService.getAccountById(5)).thenReturn(a);
        assertThat(api.getAccountById(5)).isSameAs(a);
    }

    @Test
    void getAccountsByOwner_delegates() {
        UUID u = UUID.randomUUID();
        when(accountService.getAccountsByOwner(u)).thenReturn(List.of(new Account()));
        assertThat(api.getAccountsByOwner(u)).hasSize(1);
    }

    @Test
    void getAccountsByTypeAndOwner_delegates() {
        UUID u = UUID.randomUUID();
        when(accountService.getAccountsByTypeAndOwner(AccountType.BUSINESS, u)).thenReturn(List.of());
        assertThat(api.getAccountsByTypeAndOwner(AccountType.BUSINESS, u)).isEmpty();
    }

    @Test
    void getAccountsByMember_delegates() {
        UUID u = UUID.randomUUID();
        when(accountService.getAccountsByMember(u)).thenReturn(List.of(new Account()));
        assertThat(api.getAccountsByMember(u)).hasSize(1);
    }

    @Test
    void hasAccountByAccountId_delegates() {
        when(accountService.hasAccountByAccountId(3)).thenReturn(true);
        assertThat(api.hasAccountByAccountId(3)).isTrue();
    }

    @Test
    void hasAccountByOwnerUuid_delegates() {
        UUID u = UUID.randomUUID();
        when(accountService.hasAccountByOwnerUuid(u)).thenReturn(false);
        assertThat(api.hasAccountByOwnerUuid(u)).isFalse();
    }

    // ---- Access control ----

    @Test
    void isAccountMember_delegates() {
        UUID u = UUID.randomUUID();
        when(accountService.isAccountMember(u, 1)).thenReturn(true);
        assertThat(api.isAccountMember(u, 1)).isTrue();
    }

    @Test
    void isOwnerForAccountId_delegates() {
        UUID u = UUID.randomUUID();
        when(accountService.isOwnerForAccountId(u, 1)).thenReturn(true);
        assertThat(api.isOwnerForAccountId(u, 1)).isTrue();
    }

    @Test
    void canAccessAccount_delegates() {
        UUID u = UUID.randomUUID();
        when(accountService.canAccessAccount(u, 1)).thenReturn(false);
        assertThat(api.canAccessAccount(u, 1)).isFalse();
    }

    @Test
    void accountHasBalance_delegates() {
        UUID u = UUID.randomUUID();
        when(accountService.accountHasBalance(u, 1)).thenReturn(true);
        assertThat(api.accountHasBalance(u, 1)).isTrue();
    }

    // ---- Account lifecycle ----

    @Test
    void resolveOrCreatePersonal_delegatesToLedger() {
        UUID u = UUID.randomUUID();
        Account a = new Account();
        when(ledgerService.resolveOrCreatePersonal(u)).thenReturn(a);
        assertThat(api.resolveOrCreatePersonal(u)).isSameAs(a);
        verifyNoInteractions(accountService);
    }

    @Test
    void createAccount_delegates() {
        UUID u = UUID.randomUUID();
        Account a = new Account();
        when(accountService.createAccount(AccountType.BUSINESS, u, "biz")).thenReturn(a);
        assertThat(api.createAccount(AccountType.BUSINESS, u, "biz")).isSameAs(a);
    }

    @Test
    void updateAccount_delegates() {
        Account a = new Account();
        api.updateAccount(a);
        verify(accountService).updateAccount(a);
    }

    @Test
    void reassignOwner_delegates() {
        UUID newOwner = UUID.randomUUID();
        api.reassignOwner(5, newOwner);
        verify(accountService).reassignOwner(5, newOwner);
    }

    @Test
    void archive_unarchive_delegate() {
        api.archiveAccount(7);
        verify(accountService).archiveAccount(7);
        api.unarchiveAccount(7);
        verify(accountService).unarchiveAccount(7);
    }

    // ---- Members / authorizers ----

    @Test
    void member_authorizer_methods_delegate() {
        UUID u = UUID.randomUUID();
        UUID by = UUID.randomUUID();

        api.addMember(1, u, by);
        verify(membershipService).addMember(1, u, by);

        api.removeMember(1, u);
        verify(membershipService).removeMember(1, u);

        when(membershipService.getMembers(1)).thenReturn(List.of(new AccountMember()));
        assertThat(api.getMembers(1)).hasSize(1);

        api.addAuthorizer(1, u, by);
        verify(membershipService).addAuthorizer(1, u, by);

        api.removeAuthorizer(1, u);
        verify(membershipService).removeAuthorizer(1, u);

        when(membershipService.getAuthorizers(1)).thenReturn(List.of());
        assertThat(api.getAuthorizers(1)).isEmpty();
    }

    // ---- Transaction history ----

    @Test
    void getTransactionHistory_delegates() {
        Page<TransactionEntry> page = new Page<>(List.of(), 0, 0, 10);
        when(ledgerService.getTransactionHistory(1, 0, 10)).thenReturn(page);
        assertThat(api.getTransactionHistory(1, 0, 10)).isSameAs(page);
    }

    @Test
    void getTransactionHistory_batch_delegates() {
        Page<TransactionEntry> page = new Page<>(List.of(), 0, 0, 10);
        when(ledgerService.getTransactionHistory(List.of(1, 2), 0, 10)).thenReturn(page);
        assertThat(api.getTransactionHistory(List.of(1, 2), 0, 10)).isSameAs(page);
    }

    @Test
    void getAccountsByIds_delegates() {
        Map<Integer, Account> accounts = Map.of(1, new Account());
        when(accountService.getAccountsByIds(List.of(1))).thenReturn(accounts);
        assertThat(api.getAccountsByIds(List.of(1))).isSameAs(accounts);
    }

    @Test
    void getBalancesByIds_delegates() {
        Map<Integer, BigDecimal> balances = Map.of(1, BigDecimal.TEN);
        when(accountService.getBalancesByIds(List.of(1))).thenReturn(balances);
        assertThat(api.getBalancesByIds(List.of(1))).isSameAs(balances);
    }

    @Test
    void exportTransactionsFor_delegates() {
        when(dataExportService.exportTransactionsFor(1)).thenReturn("https://x");
        assertThat(api.exportTransactionsFor(1)).isEqualTo("https://x");
    }

    @Test
    void getTransaction_delegates() {
        LedgerTxn t = new LedgerTxn();
        when(ledgerService.getTransaction(99L)).thenReturn(t);
        assertThat(api.getTransaction(99L)).isSameAs(t);
    }

    @Test
    void getPostingsForTransaction_delegates() {
        when(ledgerService.getPostingsForTransaction(anyLong())).thenReturn(List.of());
        assertThat(api.getPostingsForTransaction(1L)).isEmpty();
    }

    // ---- Government lookup, transfer, top, formatting ----

    @Test
    void getGovernmentAccountByName_delegates() {
        Account a = new Account();
        when(accountService.getGovernmentAccountByName("X")).thenReturn(a);
        assertThat(api.getGovernmentAccountByName("X")).isSameAs(a);
    }

    @Test
    void transfer_delegates() {
        TransferRequest req = new TransferRequest(1, 2, BigDecimal.ONE, "m",
                UUID.randomUUID(), null, "p", null);
        when(ledgerService.transfer(req)).thenReturn(123L);
        assertThat(api.transfer(req)).isEqualTo(123L);
    }

    @Test
    void getTopBalances_delegates() {
        Page<BalanceEntry> page = new Page<>(List.of(), 0, 0, 5);
        when(accountService.getTopBalances(0, 5)).thenReturn(page);
        assertThat(api.getTopBalances(0, 5)).isSameAs(page);
    }

    @Test
    void formatting_delegates() {
        when(accountService.formatAmount(any())).thenReturn("$1.00");
        when(accountService.getCurrencyNameSingular()).thenReturn("Dollar");
        when(accountService.getCurrencyNamePlural()).thenReturn("Dollars");

        assertThat(api.formatAmount(BigDecimal.ONE)).isEqualTo("$1.00");
        assertThat(api.getCurrencyNameSingular()).isEqualTo("Dollar");
        assertThat(api.getCurrencyNamePlural()).isEqualTo("Dollars");
    }

    @Test
    void getTaxApi_returnsInjectedInstance() {
        assertThat(api.getTaxApi()).isSameAs(taxApi);
    }
}
