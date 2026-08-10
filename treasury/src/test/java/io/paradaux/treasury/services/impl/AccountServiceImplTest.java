package io.paradaux.treasury.services.impl;

import io.paradaux.treasury.mappers.AccountMapper;
import io.paradaux.treasury.mappers.MembershipMapper;
import io.paradaux.treasury.services.cache.AccountRedirectCache;
import io.paradaux.treasury.services.cache.PersonalAccountCache;
import io.paradaux.treasury.model.config.EconomyConfiguration;
import io.paradaux.treasury.model.economy.Account;
import io.paradaux.treasury.model.economy.AccountBalance;
import io.paradaux.treasury.model.economy.AccountType;
import io.paradaux.treasury.model.economy.AccountTypeTotal;
import io.paradaux.treasury.model.economy.EconomySummary;
import io.paradaux.treasury.testsupport.TestConfigs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountServiceImplTest {

    @Mock AccountMapper accountMapper;
    @Mock MembershipMapper membershipMapper;
    @Mock AccountRedirectCache redirectCache;

    private AccountServiceImpl svc;

    @BeforeEach
    void setUp() {
        EconomyConfiguration econ = TestConfigs.economy();
        // Real PersonalAccountCache (a plain map) so resolve-then-cache behaviour is exercised.
        svc = new AccountServiceImpl(accountMapper, membershipMapper,
                redirectCache, new PersonalAccountCache(), econ);
    }

    // ---- Balance reads ----

    @Test
    void getBalanceReadOnly_missingRow_returnsZero() {
        when(accountMapper.readBalance(7)).thenReturn(null);
        assertThat(svc.getBalanceReadOnly(7)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void getBalanceReadOnly_returnsBalance() {
        AccountBalance b = new AccountBalance();
        b.setBalance(new BigDecimal("123.45"));
        when(accountMapper.readBalance(7)).thenReturn(b);
        assertThat(svc.getBalanceReadOnly(7)).isEqualByComparingTo("123.45");
    }

    @Test
    void getBalancesByIds_mapsBalancesByAccountId() {
        AccountBalance b1 = new AccountBalance(10, new BigDecimal("5.00"), 0);
        AccountBalance b2 = new AccountBalance(11, new BigDecimal("7.50"), 0);
        when(accountMapper.readBalances(List.of(10, 11))).thenReturn(List.of(b1, b2));

        Map<Integer, BigDecimal> result = svc.getBalancesByIds(List.of(10, 11));
        assertThat(result.get(10)).isEqualByComparingTo("5.00");
        assertThat(result.get(11)).isEqualByComparingTo("7.50");
    }

    @Test
    void getBalancesByIds_emptyInputShortCircuits() {
        assertThat(svc.getBalancesByIds(List.of())).isEmpty();
        verify(accountMapper, never()).readBalances(any());
    }

    @Test
    void getAccountsByIds_mapsAccountsByAccountId() {
        Account a1 = new Account();
        a1.setAccountId(10);
        Account a2 = new Account();
        a2.setAccountId(11);
        when(accountMapper.findByIds(List.of(10, 11))).thenReturn(List.of(a1, a2));

        Map<Integer, Account> result = svc.getAccountsByIds(List.of(10, 11));
        assertThat(result).containsOnlyKeys(10, 11);
        assertThat(result.get(10)).isSameAs(a1);
        assertThat(result.get(11)).isSameAs(a2);
    }

    @Test
    void getAccountsByIds_emptyInputShortCircuits() {
        assertThat(svc.getAccountsByIds(List.of())).isEmpty();
        verify(accountMapper, never()).findByIds(any());
    }

    @Test
    void getBalanceByOwnerUuid_noPersonalAccount_returnsZero() {
        UUID u = UUID.randomUUID();
        when(accountMapper.findPersonalAccountId(u)).thenReturn(null);
        assertThat(svc.getBalanceByOwnerUuid(u)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void getBalanceByOwnerUuid_returnsBalance() {
        UUID u = UUID.randomUUID();
        AccountBalance b = new AccountBalance();
        b.setBalance(new BigDecimal("99.99"));
        when(accountMapper.findPersonalAccountId(u)).thenReturn(42);
        when(accountMapper.readBalance(42)).thenReturn(b);
        assertThat(svc.getBalanceByOwnerUuid(u)).isEqualByComparingTo("99.99");
    }

    @Test
    void hasFunds_noBalanceRow_returnsFalse() {
        when(accountMapper.readBalance(1)).thenReturn(null);
        assertThat(svc.hasFunds(1, BigDecimal.TEN)).isFalse();
    }

    @Test
    void hasFunds_belowAmount_returnsFalse() {
        AccountBalance b = new AccountBalance();
        b.setBalance(new BigDecimal("5"));
        when(accountMapper.readBalance(1)).thenReturn(b);
        assertThat(svc.hasFunds(1, BigDecimal.TEN)).isFalse();
    }

    @Test
    void hasFunds_atOrAboveAmount_returnsTrue() {
        AccountBalance b = new AccountBalance();
        b.setBalance(new BigDecimal("10"));
        when(accountMapper.readBalance(1)).thenReturn(b);
        assertThat(svc.hasFunds(1, BigDecimal.TEN)).isTrue();
    }

    // ---- Lookups ----

    @Test
    void getAccountByUUID_missing_returnsNull() {
        UUID u = UUID.randomUUID();
        when(accountMapper.findPersonalAccountId(u)).thenReturn(null);
        assertThat(svc.getAccountByUUID(u)).isNull();
    }

    @Test
    void getAccountByUUID_present_returnsAccount() {
        UUID u = UUID.randomUUID();
        Account a = new Account();
        when(accountMapper.findPersonalAccountId(u)).thenReturn(5);
        when(accountMapper.findById(5)).thenReturn(a);
        assertThat(svc.getAccountByUUID(u)).isSameAs(a);
    }

    @Test
    void getAccountById_delegatesToMapper() {
        Account a = new Account();
        when(accountMapper.findById(3)).thenReturn(a);
        assertThat(svc.getAccountById(3)).isSameAs(a);
    }

    @Test
    void getAccountsByOwner_delegates() {
        UUID u = UUID.randomUUID();
        when(accountMapper.findAccountsByOwner(u)).thenReturn(List.of(new Account()));
        assertThat(svc.getAccountsByOwner(u)).hasSize(1);
    }

    @Test
    void getAccountsByTypeAndOwner_delegates() {
        UUID u = UUID.randomUUID();
        when(accountMapper.findAccountsByTypeAndOwner(AccountType.GOVERNMENT, u))
                .thenReturn(List.of());
        assertThat(svc.getAccountsByTypeAndOwner(AccountType.GOVERNMENT, u)).isEmpty();
    }

    @Test
    void getAccountsByMember_delegates() {
        UUID u = UUID.randomUUID();
        when(accountMapper.findAccountsByMember(u)).thenReturn(List.of(new Account()));
        assertThat(svc.getAccountsByMember(u)).hasSize(1);
    }

    @Test
    void hasAccountByAccountId_truthy() {
        when(accountMapper.findById(1)).thenReturn(new Account());
        assertThat(svc.hasAccountByAccountId(1)).isTrue();
    }

    @Test
    void hasAccountByAccountId_missing() {
        when(accountMapper.findById(1)).thenReturn(null);
        assertThat(svc.hasAccountByAccountId(1)).isFalse();
    }

    @Test
    void hasAccountByOwnerUuid_truthy() {
        UUID u = UUID.randomUUID();
        when(accountMapper.findPersonalAccountId(u)).thenReturn(7);
        assertThat(svc.hasAccountByOwnerUuid(u)).isTrue();
    }

    @Test
    void hasAccountByOwnerUuid_missing() {
        UUID u = UUID.randomUUID();
        when(accountMapper.findPersonalAccountId(u)).thenReturn(null);
        assertThat(svc.hasAccountByOwnerUuid(u)).isFalse();
    }

    // ---- Access control ----

    @Test
    void isAccountMember_truthy() {
        UUID u = UUID.randomUUID();
        when(membershipMapper.isMember(1, u)).thenReturn(1);
        assertThat(svc.isAccountMember(u, 1)).isTrue();
    }

    @Test
    void isOwnerForAccountId_unknownAccountReturnsFalse() {
        when(accountMapper.findById(1)).thenReturn(null);
        assertThat(svc.isOwnerForAccountId(UUID.randomUUID(), 1)).isFalse();
    }

    @Test
    void isOwnerForAccountId_matchingOwnerReturnsTrue() {
        UUID owner = UUID.randomUUID();
        Account a = new Account();
        a.setOwnerUuid(owner);
        when(accountMapper.findById(1)).thenReturn(a);
        assertThat(svc.isOwnerForAccountId(owner, 1)).isTrue();
    }

    @Test
    void canAccessAccount_ownerWins() {
        UUID owner = UUID.randomUUID();
        Account a = new Account();
        a.setOwnerUuid(owner);
        when(accountMapper.findById(1)).thenReturn(a);
        assertThat(svc.canAccessAccount(owner, 1)).isTrue();
        verify(membershipMapper, never()).isAuthorizer(eq(1), any());
    }

    @Test
    void canAccessAccount_authorizerOrMember() {
        UUID u = UUID.randomUUID();
        when(accountMapper.findById(1)).thenReturn(new Account());
        when(membershipMapper.isAuthorizer(1, u)).thenReturn(1);
        assertThat(svc.canAccessAccount(u, 1)).isTrue();
    }

    @Test
    void accountHasBalance_noAccess_returnsFalse() {
        UUID u = UUID.randomUUID();
        when(accountMapper.findById(1)).thenReturn(new Account());
        when(membershipMapper.isAuthorizer(1, u)).thenReturn(0);
        when(membershipMapper.isMember(1, u)).thenReturn(0);
        assertThat(svc.accountHasBalance(u, 1)).isFalse();
    }

    @Test
    void accountHasBalance_zeroBalance_returnsFalse() {
        UUID u = UUID.randomUUID();
        Account a = new Account();
        a.setOwnerUuid(u);
        when(accountMapper.findById(1)).thenReturn(a);
        AccountBalance b = new AccountBalance();
        b.setBalance(BigDecimal.ZERO);
        when(accountMapper.readBalance(1)).thenReturn(b);
        assertThat(svc.accountHasBalance(u, 1)).isFalse();
    }

    @Test
    void accountHasBalance_positiveBalance_returnsTrue() {
        UUID u = UUID.randomUUID();
        Account a = new Account();
        a.setOwnerUuid(u);
        when(accountMapper.findById(1)).thenReturn(a);
        AccountBalance b = new AccountBalance();
        b.setBalance(new BigDecimal("0.01"));
        when(accountMapper.readBalance(1)).thenReturn(b);
        assertThat(svc.accountHasBalance(u, 1)).isTrue();
    }

    // ---- Personal account convenience ----

    @Test
    void getPersonalAccountId_missing_throws() {
        UUID u = UUID.randomUUID();
        when(accountMapper.findPersonalAccountId(u)).thenReturn(null);
        assertThatThrownBy(() -> svc.getPersonalAccountId(u))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void getPersonalAccountId_present_returnsId() {
        UUID u = UUID.randomUUID();
        when(accountMapper.findPersonalAccountId(u)).thenReturn(42);
        assertThat(svc.getPersonalAccountId(u)).isEqualTo(42);
    }

    // ---- Lifecycle: update / archive validation ----

    @Test
    void updateAccount_unknownAccount_throws() {
        Account a = new Account();
        a.setAccountId(99);
        when(accountMapper.findById(99)).thenReturn(null);
        assertThatThrownBy(() -> svc.updateAccount(a))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void archiveAccount_unknown_throws() {
        when(accountMapper.findById(99)).thenReturn(null);
        assertThatThrownBy(() -> svc.archiveAccount(99))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unarchiveAccount_unknown_throws() {
        when(accountMapper.findById(99)).thenReturn(null);
        assertThatThrownBy(() -> svc.unarchiveAccount(99))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reassignOwner_unknownAccount_throws() {
        when(accountMapper.findById(99)).thenReturn(null);
        assertThatThrownBy(() -> svc.reassignOwner(99, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reassignOwner_updatesOwner() {
        UUID newOwner = UUID.randomUUID();
        when(accountMapper.findById(5)).thenReturn(new Account());
        svc.reassignOwner(5, newOwner);
        verify(accountMapper).updateOwner(5, newOwner);
    }

    // ---- Government / business lookups ----

    @Test
    void getGovernmentAccountByName_delegates() {
        Account a = new Account();
        when(accountMapper.findGovernmentAccountByName("X")).thenReturn(a);
        assertThat(svc.getGovernmentAccountByName("X")).isSameAs(a);
    }

    @Test
    void governmentAccountExists_delegates() {
        when(accountMapper.existsGovernmentAccountByName("X")).thenReturn(true);
        assertThat(svc.governmentAccountExists("X")).isTrue();
    }

    @Test
    void getBusinessAccountByName_delegates() {
        Account a = new Account();
        when(accountMapper.findBusinessAccountByName("X")).thenReturn(a);
        assertThat(svc.getBusinessAccountByName("X")).isSameAs(a);
    }

    @Test
    void getOrCreateSystemAccountId_existingReturnedDirectly() {
        Account existing = new Account();
        existing.setAccountId(11);
        when(accountMapper.findSystemAccountForPlugin("MyPlugin")).thenReturn(existing);
        assertThat(svc.getOrCreateSystemAccountId("MyPlugin", UUID.randomUUID())).isEqualTo(11);
        verify(accountMapper, never()).insertAccount(any());
    }

    @Test
    void getOrCreateSystemAccountId_missing_createsNewWithUnlimitedCredit() {
        UUID owner = UUID.randomUUID();
        when(accountMapper.findSystemAccountForPlugin("NewPlugin")).thenReturn(null);

        org.mockito.ArgumentCaptor<Account> captor = org.mockito.ArgumentCaptor.forClass(Account.class);
        // Simulate the @Options keyProperty assignment that MyBatis would do
        org.mockito.Mockito.doAnswer(inv -> {
            Account a = inv.getArgument(0);
            a.setAccountId(99);
            return 1;
        }).when(accountMapper).insertAccount(captor.capture());

        int id = svc.getOrCreateSystemAccountId("NewPlugin", owner);

        assertThat(id).isEqualTo(99);
        Account inserted = captor.getValue();
        assertThat(inserted.getAccountType()).isEqualTo(AccountType.SYSTEM);
        assertThat(inserted.getDisplayName()).isEqualTo("NewPlugin");
        assertThat(inserted.isAllowOverdraft()).isTrue();
        assertThat(inserted.getCreditLimit()).isEqualByComparingTo("-1");
        verify(accountMapper).seedBalance(99);
    }

    @Test
    void getOrCreateSystemAccountId_concurrentInsert_reResolvesToWinner() {
        UUID owner = UUID.randomUUID();
        when(accountMapper.findSystemAccountForPlugin("RacyPlugin")).thenReturn(null);
        // The insert loses the race against uq_one_system_per_plugin (V24, ADT-74).
        org.mockito.Mockito.doThrow(new org.apache.ibatis.exceptions.PersistenceException("dup"))
                .when(accountMapper).insertAccount(org.mockito.ArgumentMatchers.any());
        when(accountMapper.findSystemAccountIdForPluginLocking("RacyPlugin")).thenReturn(123);

        assertThat(svc.getOrCreateSystemAccountId("RacyPlugin", owner)).isEqualTo(123);
        verify(accountMapper, org.mockito.Mockito.never()).seedBalance(org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void getOrCreateSystemAccountId_insertFailsAndNoWinner_propagates() {
        UUID owner = UUID.randomUUID();
        when(accountMapper.findSystemAccountForPlugin("BrokenPlugin")).thenReturn(null);
        org.mockito.Mockito.doThrow(new org.apache.ibatis.exceptions.PersistenceException("boom"))
                .when(accountMapper).insertAccount(org.mockito.ArgumentMatchers.any());
        when(accountMapper.findSystemAccountIdForPluginLocking("BrokenPlugin")).thenReturn(null);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> svc.getOrCreateSystemAccountId("BrokenPlugin", owner))
                .isInstanceOf(org.apache.ibatis.exceptions.PersistenceException.class);
    }

    @Test
    void updateAccount_known_callsMapperUpdate() {
        Account a = new Account();
        a.setAccountId(7);
        when(accountMapper.findById(7)).thenReturn(a);
        svc.updateAccount(a);
        verify(accountMapper).updateAccount(a);
    }

    @Test
    void archiveAccount_known_setsArchivedAndCallsUpdate() {
        Account a = new Account();
        a.setAccountId(8);
        when(accountMapper.findById(8)).thenReturn(a);
        svc.archiveAccount(8);
        assertThat(a.isArchived()).isTrue();
        verify(accountMapper).updateAccount(a);
    }

    @Test
    void unarchiveAccount_known_clearsArchivedAndCallsUpdate() {
        Account a = new Account();
        a.setAccountId(9);
        a.setArchived(true);
        when(accountMapper.findById(9)).thenReturn(a);
        svc.unarchiveAccount(9);
        assertThat(a.isArchived()).isFalse();
        verify(accountMapper).updateAccount(a);
    }

    @Test
    void createAccount_validatesArgsAndPersists() {
        UUID owner = UUID.randomUUID();
        org.mockito.ArgumentCaptor<Account> captor = org.mockito.ArgumentCaptor.forClass(Account.class);
        org.mockito.Mockito.doAnswer(inv -> {
            Account a = inv.getArgument(0);
            a.setAccountId(51);
            return 1;
        }).when(accountMapper).insertAccount(captor.capture());

        Account result = svc.createAccount(AccountType.BUSINESS, owner, "Coffee Shop");

        assertThat(result.getAccountId()).isEqualTo(51);
        assertThat(captor.getValue().getDisplayName()).isEqualTo("Coffee Shop");
        verify(accountMapper).seedBalance(51);
    }

    @Test
    void getTopBalances_returnsPagedResult() {
        io.paradaux.treasury.model.economy.BalanceEntry entry =
                new io.paradaux.treasury.model.economy.BalanceEntry();
        when(accountMapper.getTopBalances(eq(10), eq(0))).thenReturn(List.of(entry));
        when(accountMapper.countPersonalAccounts()).thenReturn(42);

        var page = svc.getTopBalances(0, 10);

        assertThat(page.items()).hasSize(1);
        assertThat(page.totalCount()).isEqualTo(42);
        assertThat(page.offset()).isZero();
        assertThat(page.limit()).isEqualTo(10);
    }

    @Test
    void hasPersonalAccount_truthyAndFalsy() {
        UUID u = UUID.randomUUID();
        when(accountMapper.findPersonalAccountId(u)).thenReturn(7);
        assertThat(svc.hasPersonalAccount(u)).isTrue();

        UUID v = UUID.randomUUID();
        when(accountMapper.findPersonalAccountId(v)).thenReturn(null);
        assertThat(svc.hasPersonalAccount(v)).isFalse();
    }

    @Test
    void getOrCreatePersonalAccountId_existingReturnedDirectly() {
        UUID u = UUID.randomUUID();
        when(accountMapper.findPersonalAccountId(u)).thenReturn(33);
        assertThat(svc.getOrCreatePersonalAccountId(u)).isEqualTo(33);
        verify(accountMapper, never()).insertAccount(any());
    }

    @Test
    void getOrCreatePersonalAccountId_missing_createsAndSeeds() {
        UUID u = UUID.randomUUID();
        when(accountMapper.findPersonalAccountId(u)).thenReturn(null);
        org.mockito.Mockito.doAnswer(inv -> {
            Account a = inv.getArgument(0);
            a.setAccountId(77);
            return 1;
        }).when(accountMapper).insertAccount(any(Account.class));

        assertThat(svc.getOrCreatePersonalAccountId(u)).isEqualTo(77);
        verify(accountMapper).seedBalance(77);
    }

    @Test
    void listGovernmentAccounts_delegates() {
        when(accountMapper.findAllGovernmentAccounts()).thenReturn(List.of(new Account(), new Account()));
        assertThat(svc.listGovernmentAccounts()).hasSize(2);
    }

    // ---- Economy summary ----

    @Test
    void getEconomySummary_foldsTypeTotalsAndSumsGrandTotal() {
        when(accountMapper.getEconomyTotalsByType()).thenReturn(List.of(
                new AccountTypeTotal(AccountType.PERSONAL, new BigDecimal("106500.00")),
                new AccountTypeTotal(AccountType.BUSINESS, new BigDecimal("25000.00")),
                new AccountTypeTotal(AccountType.GOVERNMENT, new BigDecimal("8000.00"))));

        EconomySummary s = svc.getEconomySummary();

        assertThat(s.getPersonal()).isEqualByComparingTo("106500.00");
        assertThat(s.getBusiness()).isEqualByComparingTo("25000.00");
        assertThat(s.getGovernment()).isEqualByComparingTo("8000.00");
        assertThat(s.getTotal()).isEqualByComparingTo("139500.00");
    }

    @Test
    void getEconomySummary_missingTypesDefaultToZero() {
        // Only PERSONAL has any balance; BUSINESS/GOVERNMENT rows absent.
        when(accountMapper.getEconomyTotalsByType()).thenReturn(List.of(
                new AccountTypeTotal(AccountType.PERSONAL, new BigDecimal("500.00"))));

        EconomySummary s = svc.getEconomySummary();

        assertThat(s.getPersonal()).isEqualByComparingTo("500.00");
        assertThat(s.getBusiness()).isEqualByComparingTo("0");
        assertThat(s.getGovernment()).isEqualByComparingTo("0");
        assertThat(s.getTotal()).isEqualByComparingTo("500.00");
    }

    // ---- Formatting ----

    @Test
    void formatAmount_usesConfiguredPattern() {
        // TestConfigs.economy() uses pattern "$#,##0.00"
        assertThat(svc.formatAmount(new BigDecimal("1234.5"))).isEqualTo("$1,234.50");
        assertThat(svc.formatAmount(new BigDecimal("0.5"))).isEqualTo("$0.50");
    }

    @Test
    void currencyNames_areReadFromConfig() {
        assertThat(svc.getCurrencyNameSingular()).isEqualTo("Dollar");
        assertThat(svc.getCurrencyNamePlural()).isEqualTo("Dollars");
    }
}
