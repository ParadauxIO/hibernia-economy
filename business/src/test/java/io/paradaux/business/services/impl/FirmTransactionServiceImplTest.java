package io.paradaux.business.services.impl;

import io.paradaux.business.mappers.FirmAccountsMapper;
import io.paradaux.business.model.Firm;
import io.paradaux.business.model.FirmAccount;
import io.paradaux.business.services.FirmService;
import io.paradaux.hibernia.framework.exceptions.BadCommandException;
import io.paradaux.hibernia.framework.exceptions.ConflictException;
import io.paradaux.hibernia.framework.exceptions.ExceedsLimitException;
import io.paradaux.hibernia.framework.exceptions.KeyedException;
import io.paradaux.hibernia.framework.exceptions.NoPermissionException;
import io.paradaux.hibernia.framework.exceptions.NotFoundException;
import io.paradaux.treasury.api.TreasuryApi;
import io.paradaux.treasury.model.Page;
import io.paradaux.treasury.model.economy.Account;
import io.paradaux.treasury.model.economy.AccountMember;
import io.paradaux.treasury.model.economy.TransactionEntry;
import io.paradaux.treasury.model.economy.TransferRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FirmTransactionServiceImplTest {

    @Mock FirmService firms;
    @Mock TreasuryApi treasury;
    @Mock FirmAccountsMapper firmAccounts;
    @Mock io.paradaux.business.services.FirmPlayerService players;

    private FirmTransactionServiceImpl svc;

    private final UUID player = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        svc = new FirmTransactionServiceImpl(firms, treasury, firmAccounts, players);
        // By default a firm's default account is still owned/live; staleness tests
        // override this with a specific false stub.
        lenient().when(firmAccounts.isFirmAccount(anyInt(), anyInt())).thenReturn(true);
    }

    private Firm firm(int id, Integer defaultAccountId) {
        Firm f = new Firm();
        f.setFirmId(id);
        f.setProprietorUuid(UUID.randomUUID().toString());
        f.setDefaultAccountId(defaultAccountId);
        return f;
    }

    private Firm namedFirm(int id, Integer defaultAccountId, String displayName) {
        Firm f = firm(id, defaultAccountId);
        f.setDisplayName(displayName);
        return f;
    }

    // ---------- resolveAccountId ----------

    @Test
    void getFirmBalance_usesDefaultAccount() {
        when(firms.getFirmById(1)).thenReturn(firm(1, 100));
        when(treasury.getBalanceByAccountId(100)).thenReturn(new BigDecimal("50.00"));
        assertThat(svc.getFirmBalance(1)).isEqualByComparingTo("50.00");
    }

    @Test
    void getFirmBalance_unsetDefault_selfHealsToSurvivingAccount() {
        when(firms.getFirmById(1)).thenReturn(firm(1, null));
        when(firmAccounts.getAnyAccountId(1)).thenReturn(200);
        when(treasury.getBalanceByAccountId(200)).thenReturn(new BigDecimal("12"));
        assertThat(svc.getFirmBalance(1)).isEqualByComparingTo("12");
        // The unset default is re-pointed at the surviving account and persisted.
        verify(firms).updateDefaultAccount(1, 200);
    }

    @Test
    void getFirmBalance_staleDefault_selfHealsToSurvivingAccount() {
        // Default points at account 100, but the firm no longer owns it (archived/removed).
        when(firms.getFirmById(1)).thenReturn(firm(1, 100));
        when(firmAccounts.isFirmAccount(1, 100)).thenReturn(false);
        when(firmAccounts.getAnyAccountId(1)).thenReturn(200);
        when(treasury.getBalanceByAccountId(200)).thenReturn(new BigDecimal("7"));
        assertThat(svc.getFirmBalance(1)).isEqualByComparingTo("7");
        verify(firms).updateDefaultAccount(1, 200);
    }

    @Test
    void getFirmBalance_throwsWhenFirmHasNoAccount() {
        when(firms.getFirmById(1)).thenReturn(firm(1, null));
        when(firmAccounts.getAnyAccountId(1)).thenReturn(null);
        // The missing-account condition is a distinct semantic exception carrying its
        // own message key so the player learns the real cause (behaviour/0001).
        assertThatThrownBy(() -> svc.getFirmBalance(1))
                .isInstanceOf(NotFoundException.class)
                .extracting(e -> ((KeyedException) e).messageKey())
                .isEqualTo("business.finance.no-account");
    }

    @Test
    void getFirmBalance_throwsForUnknownFirm() {
        when(firms.getFirmById(1)).thenReturn(null);
        assertThatThrownBy(() -> svc.getFirmBalance(1))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void getFormattedBalance_delegates() {
        when(firms.getFirmById(1)).thenReturn(firm(1, 100));
        when(treasury.getBalanceByAccountId(100)).thenReturn(new BigDecimal("1.00"));
        when(treasury.formatAmount(new BigDecimal("1.00"))).thenReturn("$1.00");
        assertThat(svc.getFormattedBalance(1)).isEqualTo("$1.00");
    }

    @Test
    void getTransactions_clampsPagination() {
        when(firms.getFirmById(1)).thenReturn(firm(1, 100));
        Page<TransactionEntry> page = new Page<>(List.of(), 0, 0, 10);
        when(treasury.getTransactionHistory(100, 0, 10)).thenReturn(page);
        assertThat(svc.getTransactions(1, 0, 0)).isSameAs(page);
    }

    @Test
    void getTransactions_paginates() {
        when(firms.getFirmById(1)).thenReturn(firm(1, 100));
        Page<TransactionEntry> page = new Page<>(List.of(), 0, 20, 10);
        when(treasury.getTransactionHistory(100, 20, 10)).thenReturn(page);
        assertThat(svc.getTransactions(1, 3, 10)).isSameAs(page);
    }

    // ---------- deposit ----------

    @Test
    void deposit_rejectsNonPositive() {
        // Invalid amount surfaces as BadCommandException keyed to the invalid-amount message.
        assertThatThrownBy(() -> svc.deposit(1, player, BigDecimal.ZERO))
                .isInstanceOf(BadCommandException.class)
                .extracting(e -> ((KeyedException) e).messageKey())
                .isEqualTo("business.finance.invalid-amount");
        assertThatThrownBy(() -> svc.deposit(1, player, new BigDecimal("-1")))
                .isInstanceOf(BadCommandException.class)
                .extracting(e -> ((KeyedException) e).messageKey())
                .isEqualTo("business.finance.invalid-amount");
    }

    @Test
    void deposit_insufficientPersonalFunds_throws() {
        when(firms.getFirmById(1)).thenReturn(firm(1, 100));
        Account personal = new Account(); personal.setAccountId(7);
        when(treasury.resolveOrCreatePersonal(player)).thenReturn(personal);
        when(treasury.hasFunds(7, BigDecimal.ONE)).thenReturn(false);
        assertThatThrownBy(() -> svc.deposit(1, player, BigDecimal.ONE))
                .isInstanceOf(ExceedsLimitException.class)
                .extracting(e -> ((KeyedException) e).messageKey())
                .isEqualTo("business.finance.insufficient-personal");
    }

    @Test
    void deposit_firmHasNoAccount_reportsNoAccountNotInsufficientFunds() {
        // behaviour/0001: a firm with no usable Treasury account must report the real
        // cause (no-account) rather than misreporting "insufficient personal funds".
        // resolveAccountId runs before the funds check, so no funds stubbing is needed.
        when(firms.getFirmById(1)).thenReturn(firm(1, null));
        when(firmAccounts.getAnyAccountId(1)).thenReturn(null);
        assertThatThrownBy(() -> svc.deposit(1, player, BigDecimal.ONE))
                .isInstanceOf(NotFoundException.class)
                .extracting(e -> ((KeyedException) e).messageKey())
                .isEqualTo("business.finance.no-account");
    }

    @Test
    void payIntoFirm_firmHasNoAccount_reportsNoAccountNotInsufficientFunds() {
        // behaviour/0001: /firm pay into a firm with no usable account reports no-account.
        when(firms.getFirmById(1)).thenReturn(firm(1, null));
        when(firmAccounts.getAnyAccountId(1)).thenReturn(null);
        assertThatThrownBy(() -> svc.payIntoFirm(1, player, BigDecimal.ONE))
                .isInstanceOf(NotFoundException.class)
                .extracting(e -> ((KeyedException) e).messageKey())
                .isEqualTo("business.finance.no-account");
    }

    @Test
    void deposit_noAccess_throws() {
        when(firms.getFirmById(1)).thenReturn(firm(1, 100));
        Account personal = new Account(); personal.setAccountId(7);
        when(treasury.resolveOrCreatePersonal(player)).thenReturn(personal);
        when(treasury.hasFunds(7, BigDecimal.ONE)).thenReturn(true);
        when(treasury.canAccessAccount(player, 100)).thenReturn(false);
        assertThatThrownBy(() -> svc.deposit(1, player, BigDecimal.ONE))
                .isInstanceOf(NoPermissionException.class)
                .extracting(e -> ((KeyedException) e).messageKey())
                .isEqualTo("business.general.no-permission");
    }

    @Test
    void deposit_buildsTransferAndReturnsId() {
        when(firms.getFirmById(1)).thenReturn(firm(1, 100));
        Account personal = new Account(); personal.setAccountId(7);
        when(treasury.resolveOrCreatePersonal(player)).thenReturn(personal);
        when(treasury.hasFunds(7, BigDecimal.TEN)).thenReturn(true);
        when(treasury.canAccessAccount(player, 100)).thenReturn(true);
        when(treasury.transfer(any())).thenReturn(42L);

        long txnId = svc.deposit(1, player, BigDecimal.TEN);
        assertThat(txnId).isEqualTo(42L);

        ArgumentCaptor<TransferRequest> req = ArgumentCaptor.forClass(TransferRequest.class);
        verify(treasury).transfer(req.capture());
        assertThat(req.getValue().fromAccountId()).isEqualTo(7);
        assertThat(req.getValue().toAccountId()).isEqualTo(100);
        assertThat(req.getValue().amount()).isEqualByComparingTo("10");
        assertThat(req.getValue().initiator()).isEqualTo(player);
        assertThat(req.getValue().authorizer()).isNull();
        assertThat(req.getValue().message()).isEqualTo("Business deposit");
    }

    @Test
    void deposit_withMemo_recordsItAsReason() {
        when(firms.getFirmById(1)).thenReturn(firm(1, 100));
        Account personal = new Account(); personal.setAccountId(7);
        when(treasury.resolveOrCreatePersonal(player)).thenReturn(personal);
        when(treasury.hasFunds(7, BigDecimal.TEN)).thenReturn(true);
        when(treasury.canAccessAccount(player, 100)).thenReturn(true);
        when(treasury.transfer(any())).thenReturn(42L);

        svc.deposit(1, player, BigDecimal.TEN, "  payroll   top-up ");

        ArgumentCaptor<TransferRequest> req = ArgumentCaptor.forClass(TransferRequest.class);
        verify(treasury).transfer(req.capture());
        // Memo is whitespace-collapsed and trimmed before being appended.
        assertThat(req.getValue().message()).isEqualTo("Business deposit: payroll top-up");
    }

    @Test
    void deposit_withBlankMemo_fallsBackToDefaultReason() {
        when(firms.getFirmById(1)).thenReturn(firm(1, 100));
        Account personal = new Account(); personal.setAccountId(7);
        when(treasury.resolveOrCreatePersonal(player)).thenReturn(personal);
        when(treasury.hasFunds(7, BigDecimal.TEN)).thenReturn(true);
        when(treasury.canAccessAccount(player, 100)).thenReturn(true);
        when(treasury.transfer(any())).thenReturn(42L);

        svc.deposit(1, player, BigDecimal.TEN, "   ");

        ArgumentCaptor<TransferRequest> req = ArgumentCaptor.forClass(TransferRequest.class);
        verify(treasury).transfer(req.capture());
        assertThat(req.getValue().message()).isEqualTo("Business deposit");
    }

    @Test
    void deposit_withOverlongMemo_capsReasonAt255() {
        when(firms.getFirmById(1)).thenReturn(firm(1, 100));
        Account personal = new Account(); personal.setAccountId(7);
        when(treasury.resolveOrCreatePersonal(player)).thenReturn(personal);
        when(treasury.hasFunds(7, BigDecimal.TEN)).thenReturn(true);
        when(treasury.canAccessAccount(player, 100)).thenReturn(true);
        when(treasury.transfer(any())).thenReturn(42L);

        svc.deposit(1, player, BigDecimal.TEN, "x".repeat(400));

        ArgumentCaptor<TransferRequest> req = ArgumentCaptor.forClass(TransferRequest.class);
        verify(treasury).transfer(req.capture());
        assertThat(req.getValue().message()).hasSize(255).startsWith("Business deposit: ");
    }

    // ---------- withdraw ----------

    @Test
    void withdraw_rejectsNonPositive() {
        assertThatThrownBy(() -> svc.withdraw(1, player, BigDecimal.ZERO))
                .isInstanceOf(BadCommandException.class)
                .extracting(e -> ((KeyedException) e).messageKey())
                .isEqualTo("business.finance.invalid-amount");
    }

    @Test
    void withdraw_noAccessThrows() {
        when(firms.getFirmById(1)).thenReturn(firm(1, 100));
        Account business = new Account(); business.setAccountId(100);
        Account personal = new Account(); personal.setAccountId(7);
        when(treasury.getAccountById(100)).thenReturn(business);
        when(treasury.resolveOrCreatePersonal(player)).thenReturn(personal);
        when(treasury.canAccessAccount(player, 100)).thenReturn(false);
        assertThatThrownBy(() -> svc.withdraw(1, player, BigDecimal.ONE))
                .isInstanceOf(NoPermissionException.class)
                .extracting(e -> ((KeyedException) e).messageKey())
                .isEqualTo("business.general.no-permission");
    }

    @Test
    void withdraw_insufficientBusinessFundsThrows() {
        when(firms.getFirmById(1)).thenReturn(firm(1, 100));
        Account business = new Account(); business.setAccountId(100);
        Account personal = new Account(); personal.setAccountId(7);
        when(treasury.getAccountById(100)).thenReturn(business);
        when(treasury.resolveOrCreatePersonal(player)).thenReturn(personal);
        when(treasury.canAccessAccount(player, 100)).thenReturn(true);
        when(treasury.hasFunds(100, BigDecimal.ONE)).thenReturn(false);
        assertThatThrownBy(() -> svc.withdraw(1, player, BigDecimal.ONE))
                .isInstanceOf(ExceedsLimitException.class)
                .extracting(e -> ((KeyedException) e).messageKey())
                .isEqualTo("business.finance.insufficient-business");
    }

    @Test
    void withdraw_authorizerRequired_andPlayerIsAuthorizer() {
        Firm f = firm(1, 100);
        when(firms.getFirmById(1)).thenReturn(f);

        Account business = new Account();
        business.setAccountId(100);
        business.setRequiresAuthorization(true);
        Account personal = new Account(); personal.setAccountId(7);

        when(treasury.getAccountById(100)).thenReturn(business);
        when(treasury.resolveOrCreatePersonal(player)).thenReturn(personal);
        when(treasury.canAccessAccount(player, 100)).thenReturn(true);
        when(treasury.hasFunds(100, BigDecimal.ONE)).thenReturn(true);
        when(treasury.getAuthorizers(100)).thenReturn(List.of(
                new AccountMember(100, player, player, Instant.EPOCH)));
        when(treasury.transfer(any())).thenReturn(11L);

        long txnId = svc.withdraw(1, player, BigDecimal.ONE);
        assertThat(txnId).isEqualTo(11L);

        ArgumentCaptor<TransferRequest> req = ArgumentCaptor.forClass(TransferRequest.class);
        verify(treasury).transfer(req.capture());
        assertThat(req.getValue().authorizer()).isEqualTo(player);
    }

    @Test
    void withdraw_authorizerRequired_butPlayerIsNot_throws() {
        when(firms.getFirmById(1)).thenReturn(firm(1, 100));
        Account business = new Account();
        business.setAccountId(100);
        business.setRequiresAuthorization(true);
        Account personal = new Account(); personal.setAccountId(7);

        when(treasury.getAccountById(100)).thenReturn(business);
        when(treasury.resolveOrCreatePersonal(player)).thenReturn(personal);
        when(treasury.canAccessAccount(player, 100)).thenReturn(true);
        when(treasury.hasFunds(100, BigDecimal.ONE)).thenReturn(true);
        when(treasury.getAuthorizers(100)).thenReturn(List.of());

        assertThatThrownBy(() -> svc.withdraw(1, player, BigDecimal.ONE))
                .isInstanceOf(NoPermissionException.class)
                .extracting(e -> ((KeyedException) e).messageKey())
                .isEqualTo("business.finance.not-authorizer");
    }

    @Test
    void withdraw_noAuthorizationRequired_succeeds() {
        when(firms.getFirmById(1)).thenReturn(firm(1, 100));
        Account business = new Account();
        business.setAccountId(100);
        business.setRequiresAuthorization(false);
        Account personal = new Account(); personal.setAccountId(7);

        when(treasury.getAccountById(100)).thenReturn(business);
        when(treasury.resolveOrCreatePersonal(player)).thenReturn(personal);
        when(treasury.canAccessAccount(player, 100)).thenReturn(true);
        when(treasury.hasFunds(100, BigDecimal.ONE)).thenReturn(true);
        when(treasury.transfer(any())).thenReturn(8L);

        assertThat(svc.withdraw(1, player, BigDecimal.ONE)).isEqualTo(8L);

        ArgumentCaptor<TransferRequest> req = ArgumentCaptor.forClass(TransferRequest.class);
        verify(treasury).transfer(req.capture());
        assertThat(req.getValue().authorizer()).isNull();
    }

    // ---------- aggregate ----------

    @Test
    void getAggregateBalance_zeroWhenNoAccounts() {
        // A firm with no live accounts (e.g. disbanded) reads as zero rather than throwing,
        // so /firm info, the disband prompt, and the public API don't crash.
        when(firmAccounts.listAccountsByFirm(1)).thenReturn(List.of());
        when(treasury.getBalancesByIds(List.of())).thenReturn(Map.of());
        assertThat(svc.getAggregateBalance(1)).isEqualByComparingTo("0");
    }

    @Test
    void getAggregateBalance_sumsAccounts() {
        when(firmAccounts.listAccountsByFirm(1)).thenReturn(List.of(
                new FirmAccount(1, 10, null), new FirmAccount(1, 11, null)));
        // One batch read instead of one IPC per account (ADT-36).
        when(treasury.getBalancesByIds(List.of(10, 11)))
                .thenReturn(Map.of(10, new BigDecimal("5"), 11, new BigDecimal("3")));
        assertThat(svc.getAggregateBalance(1)).isEqualByComparingTo("8");
    }

    @Test
    void getFormattedAggregateBalance_delegates() {
        when(firmAccounts.listAccountsByFirm(1)).thenReturn(List.of(new FirmAccount(1, 10, null)));
        when(treasury.getBalancesByIds(List.of(10))).thenReturn(Map.of(10, new BigDecimal("5")));
        when(treasury.formatAmount(new BigDecimal("5"))).thenReturn("$5");
        assertThat(svc.getFormattedAggregateBalance(1)).isEqualTo("$5");
    }

    @Test
    void getAggregateTransactions_emptyAccountsReturnsEmptyPage() {
        when(firmAccounts.listAccountsByFirm(1)).thenReturn(List.of());
        when(treasury.getTransactionHistory(List.of(), 0, 10)).thenReturn(new Page<>(List.of(), 0, 0, 10));
        Page<TransactionEntry> p = svc.getAggregateTransactions(1, 1, 10);
        assertThat(p.items()).isEmpty();
        assertThat(p.totalCount()).isZero();
    }

    @Test
    void getAggregateTransactions_delegatesToTreasuryMergedQuery() {
        // Merging/sorting/total now happen Treasury-side (ADT-36); the service just
        // collects the account ids and forwards the computed offset. page/pageSize
        // below 1 are clamped to 1 and 10.
        when(firmAccounts.listAccountsByFirm(1)).thenReturn(List.of(
                new FirmAccount(1, 10, null), new FirmAccount(1, 11, null)));
        TransactionEntry late = txn(2L, Instant.parse("2025-12-01T00:00:00Z"));
        TransactionEntry early = txn(1L, Instant.parse("2025-01-01T00:00:00Z"));
        Page<TransactionEntry> merged = new Page<>(List.of(late, early), 2, 0, 10);
        when(treasury.getTransactionHistory(List.of(10, 11), 0, 10)).thenReturn(merged);

        Page<TransactionEntry> p = svc.getAggregateTransactions(1, 0, 0);
        assertThat(p).isSameAs(merged);
        assertThat(p.items()).extracting(TransactionEntry::getPostingId).containsExactly(2L, 1L);
    }

    @Test
    void getAggregateTransactions_forwardsComputedOffset() {
        when(firmAccounts.listAccountsByFirm(1)).thenReturn(List.of(new FirmAccount(1, 10, null)));
        Page<TransactionEntry> empty = new Page<>(List.of(), 1, 40, 10);
        // page 5, pageSize 10 → offset (5-1)*10 = 40.
        when(treasury.getTransactionHistory(List.of(10), 40, 10)).thenReturn(empty);
        Page<TransactionEntry> p = svc.getAggregateTransactions(1, 5, 10);
        assertThat(p.items()).isEmpty();
    }

    // ---------- firm balance leaderboard (/firm baltop) ----------

    @Test
    void getFirmBalanceTop_ranksFirmsByCollectiveBalanceDescending() {
        when(firms.listAllActiveFirms()).thenReturn(List.of(
                namedFirm(1, 10, "Acme"),
                namedFirm(2, 20, "Globex")));
        // Acme owns accounts 10 (+ a second account 11); Globex owns 20.
        when(firmAccounts.listActiveAccountLinks()).thenReturn(List.of(
                new FirmAccount(1, 10, null),
                new FirmAccount(1, 11, null),
                new FirmAccount(2, 20, null)));
        when(treasury.getBalancesByIds(List.of(10, 11, 20))).thenReturn(Map.of(
                10, new BigDecimal("100"), 11, new BigDecimal("50"), 20, new BigDecimal("200")));

        Page<io.paradaux.business.model.FirmBalanceEntry> page = svc.getFirmBalanceTop(1, 10);

        assertThat(page.totalCount()).isEqualTo(2);
        // Globex (200) outranks Acme (100+50=150) despite being created later.
        assertThat(page.items()).extracting(e -> e.displayName()).containsExactly("Globex", "Acme");
        assertThat(page.items().get(0).balance()).isEqualByComparingTo("200");
        assertThat(page.items().get(1).balance()).isEqualByComparingTo("150");
    }

    @Test
    void getFirmBalanceTop_includesFirmWithNoAccountsAtZero() {
        when(firms.listAllActiveFirms()).thenReturn(List.of(
                namedFirm(1, 10, "Acme"),
                namedFirm(2, null, "Empty")));
        when(firmAccounts.listActiveAccountLinks()).thenReturn(List.of(new FirmAccount(1, 10, null)));
        when(treasury.getBalancesByIds(List.of(10))).thenReturn(Map.of(10, new BigDecimal("5")));

        Page<io.paradaux.business.model.FirmBalanceEntry> page = svc.getFirmBalanceTop(1, 10);

        assertThat(page.items()).extracting(e -> e.displayName()).containsExactly("Acme", "Empty");
        assertThat(page.items().get(1).balance()).isEqualByComparingTo("0");
    }

    @Test
    void getFirmBalanceTop_missingBalanceRowCountsAsZero() {
        when(firms.listAllActiveFirms()).thenReturn(List.of(namedFirm(1, 10, "Acme")));
        when(firmAccounts.listActiveAccountLinks()).thenReturn(List.of(
                new FirmAccount(1, 10, null), new FirmAccount(1, 11, null)));
        // Account 11 has no materialized balance row → treated as zero, not an NPE.
        when(treasury.getBalancesByIds(List.of(10, 11))).thenReturn(Map.of(10, new BigDecimal("7")));

        Page<io.paradaux.business.model.FirmBalanceEntry> page = svc.getFirmBalanceTop(1, 10);
        assertThat(page.items().get(0).balance()).isEqualByComparingTo("7");
    }

    @Test
    void getFirmBalanceTop_paginatesAndClampsBelowOne() {
        // Three firms, page size 2 → page 2 holds the third. page/pageSize < 1 clamp to 1/10.
        when(firms.listAllActiveFirms()).thenReturn(List.of(
                namedFirm(1, 10, "A"), namedFirm(2, 20, "B"), namedFirm(3, 30, "C")));
        when(firmAccounts.listActiveAccountLinks()).thenReturn(List.of(
                new FirmAccount(1, 10, null), new FirmAccount(2, 20, null), new FirmAccount(3, 30, null)));
        when(treasury.getBalancesByIds(List.of(10, 20, 30))).thenReturn(Map.of(
                10, new BigDecimal("30"), 20, new BigDecimal("20"), 30, new BigDecimal("10")));

        Page<io.paradaux.business.model.FirmBalanceEntry> p2 = svc.getFirmBalanceTop(2, 2);
        assertThat(p2.totalCount()).isEqualTo(3);
        assertThat(p2.offset()).isEqualTo(2);
        assertThat(p2.items()).extracting(e -> e.displayName()).containsExactly("C");
        assertThat(p2.hasMore()).isFalse();

        // page 0 / size 0 → clamps to page 1, size 10: all three, highest first.
        Page<io.paradaux.business.model.FirmBalanceEntry> clamped = svc.getFirmBalanceTop(0, 0);
        assertThat(clamped.items()).extracting(e -> e.displayName()).containsExactly("A", "B", "C");
    }

    @Test
    void getFirmBalanceTop_offsetPastEndReturnsEmptyPageWithTotal() {
        when(firms.listAllActiveFirms()).thenReturn(List.of(namedFirm(1, 10, "Acme")));
        when(firmAccounts.listActiveAccountLinks()).thenReturn(List.of(new FirmAccount(1, 10, null)));
        when(treasury.getBalancesByIds(List.of(10))).thenReturn(Map.of(10, new BigDecimal("5")));

        Page<io.paradaux.business.model.FirmBalanceEntry> page = svc.getFirmBalanceTop(5, 10);
        assertThat(page.items()).isEmpty();
        assertThat(page.totalCount()).isEqualTo(1);
    }

    @Test
    void getFirmBalanceTop_noFirmsReturnsEmptyPage() {
        when(firms.listAllActiveFirms()).thenReturn(List.of());
        when(firmAccounts.listActiveAccountLinks()).thenReturn(List.of());
        when(treasury.getBalancesByIds(List.of())).thenReturn(Map.of());

        Page<io.paradaux.business.model.FirmBalanceEntry> page = svc.getFirmBalanceTop(1, 10);
        assertThat(page.items()).isEmpty();
        assertThat(page.totalCount()).isZero();
    }

    @Test
    void formatAmount_delegatesToTreasury() {
        when(treasury.formatAmount(new BigDecimal("12.50"))).thenReturn("$12.50");
        assertThat(svc.formatAmount(new BigDecimal("12.50"))).isEqualTo("$12.50");
    }

    private TransactionEntry txn(long id, Instant when) {
        TransactionEntry e = new TransactionEntry();
        e.setPostingId(id);
        e.setSettlementTime(when);
        return e;
    }

    // ---------- per-account methods ----------

    @Test
    void getAccountBalance_validatesOwnership() {
        when(firmAccounts.isFirmAccount(1, 99)).thenReturn(false);
        assertThatThrownBy(() -> svc.getAccountBalance(1, 99))
                .isInstanceOf(BadCommandException.class)
                .extracting(e -> ((KeyedException) e).messageKey())
                .isEqualTo("business.finance.foreign-account");
    }

    @Test
    void getAccountBalance_returnsTreasuryBalance() {
        when(firmAccounts.isFirmAccount(1, 99)).thenReturn(true);
        when(treasury.getBalanceByAccountId(99)).thenReturn(new BigDecimal("3"));
        assertThat(svc.getAccountBalance(1, 99)).isEqualByComparingTo("3");
    }

    @Test
    void getFormattedAccountBalance_delegates() {
        when(firmAccounts.isFirmAccount(1, 99)).thenReturn(true);
        when(treasury.getBalanceByAccountId(99)).thenReturn(new BigDecimal("3"));
        when(treasury.formatAmount(new BigDecimal("3"))).thenReturn("$3");
        assertThat(svc.getFormattedAccountBalance(1, 99)).isEqualTo("$3");
    }

    @Test
    void getAccountTransactions_paginates() {
        when(firmAccounts.isFirmAccount(1, 99)).thenReturn(true);
        Page<TransactionEntry> page = new Page<>(List.of(), 0, 20, 10);
        when(treasury.getTransactionHistory(99, 20, 10)).thenReturn(page);
        assertThat(svc.getAccountTransactions(1, 99, 3, 10)).isSameAs(page);
    }

    @Test
    void depositToAccount_rejectsForeignAccount() {
        when(firmAccounts.isFirmAccount(1, 99)).thenReturn(false);
        assertThatThrownBy(() -> svc.depositToAccount(1, 99, player, BigDecimal.ONE))
                .isInstanceOf(BadCommandException.class)
                .extracting(e -> ((KeyedException) e).messageKey())
                .isEqualTo("business.finance.foreign-account");
    }

    @Test
    void depositToAccount_succeeds() {
        when(firmAccounts.isFirmAccount(1, 99)).thenReturn(true);
        Account business = new Account();
        business.setAccountId(99);
        business.setArchived(false);
        when(treasury.getAccountById(99)).thenReturn(business);
        Account personal = new Account(); personal.setAccountId(7);
        when(treasury.resolveOrCreatePersonal(player)).thenReturn(personal);
        when(treasury.hasFunds(7, BigDecimal.ONE)).thenReturn(true);
        when(treasury.canAccessAccount(player, 99)).thenReturn(true);
        when(treasury.transfer(any())).thenReturn(99L);
        assertThat(svc.depositToAccount(1, 99, player, BigDecimal.ONE)).isEqualTo(99L);
    }

    @Test
    void depositToAccount_archived_throws() {
        when(firmAccounts.isFirmAccount(1, 99)).thenReturn(true);
        Account business = new Account();
        business.setAccountId(99);
        business.setArchived(true);
        when(treasury.getAccountById(99)).thenReturn(business);
        assertThatThrownBy(() -> svc.depositToAccount(1, 99, player, BigDecimal.ONE))
                .isInstanceOf(ConflictException.class)
                .extracting(e -> ((KeyedException) e).messageKey())
                .isEqualTo("business.finance.archived");
    }

    @Test
    void withdrawFromAccount_rejectsForeignAccount() {
        when(firmAccounts.isFirmAccount(1, 99)).thenReturn(false);
        assertThatThrownBy(() -> svc.withdrawFromAccount(1, 99, player, BigDecimal.ONE))
                .isInstanceOf(BadCommandException.class)
                .extracting(e -> ((KeyedException) e).messageKey())
                .isEqualTo("business.finance.foreign-account");
    }

    @Test
    void withdrawFromAccount_succeedsWithoutAuthorization() {
        when(firmAccounts.isFirmAccount(1, 99)).thenReturn(true);
        Account business = new Account();
        business.setAccountId(99);
        business.setRequiresAuthorization(false);
        Account personal = new Account(); personal.setAccountId(7);
        when(treasury.getAccountById(99)).thenReturn(business);
        when(treasury.resolveOrCreatePersonal(player)).thenReturn(personal);
        when(treasury.canAccessAccount(player, 99)).thenReturn(true);
        when(treasury.hasFunds(99, BigDecimal.ONE)).thenReturn(true);
        when(treasury.transfer(any())).thenReturn(100L);

        assertThat(svc.withdrawFromAccount(1, 99, player, BigDecimal.ONE)).isEqualTo(100L);
    }

    // ---------- payIntoFirm / payIntoAccount (player -> business) ----------

    private Account account(int id, boolean archived, boolean requiresAuth) {
        Account a = new Account();
        a.setAccountId(id);
        a.setArchived(archived);
        a.setRequiresAuthorization(requiresAuth);
        return a;
    }

    @Test
    void payIntoFirm_rejectsNonPositive() {
        when(firms.getFirmById(1)).thenReturn(firm(1, 100));
        assertThatThrownBy(() -> svc.payIntoFirm(1, player, BigDecimal.ZERO))
                .isInstanceOf(BadCommandException.class)
                .extracting(e -> ((KeyedException) e).messageKey())
                .isEqualTo("business.finance.invalid-amount");
    }

    @Test
    void payIntoFirm_insufficientPersonalFunds_throws() {
        when(firms.getFirmById(1)).thenReturn(firm(1, 100));
        when(treasury.getAccountById(100)).thenReturn(account(100, false, false));
        Account personal = new Account(); personal.setAccountId(7);
        when(treasury.resolveOrCreatePersonal(player)).thenReturn(personal);
        when(treasury.hasFunds(7, BigDecimal.ONE)).thenReturn(false);
        assertThatThrownBy(() -> svc.payIntoFirm(1, player, BigDecimal.ONE))
                .isInstanceOf(ExceedsLimitException.class)
                .extracting(e -> ((KeyedException) e).messageKey())
                .isEqualTo("business.finance.insufficient-personal");
    }

    @Test
    void payIntoFirm_succeeds_noAccessCheckOnPayer() {
        when(firms.getFirmById(1)).thenReturn(firm(1, 100));
        when(treasury.getAccountById(100)).thenReturn(account(100, false, false));
        Account personal = new Account(); personal.setAccountId(7);
        when(treasury.resolveOrCreatePersonal(player)).thenReturn(personal);
        when(treasury.hasFunds(7, BigDecimal.TEN)).thenReturn(true);
        when(treasury.transfer(any())).thenReturn(55L);

        assertThat(svc.payIntoFirm(1, player, BigDecimal.TEN)).isEqualTo(55L);

        ArgumentCaptor<TransferRequest> req = ArgumentCaptor.forClass(TransferRequest.class);
        verify(treasury).transfer(req.capture());
        assertThat(req.getValue().fromAccountId()).isEqualTo(7);
        assertThat(req.getValue().toAccountId()).isEqualTo(100);
        assertThat(req.getValue().initiator()).isEqualTo(player);
        assertThat(req.getValue().authorizer()).isNull();
    }

    @Test
    void payIntoAccount_rejectsForeignAccount() {
        when(firmAccounts.isFirmAccount(1, 99)).thenReturn(false);
        assertThatThrownBy(() -> svc.payIntoAccount(1, 99, player, BigDecimal.ONE))
                .isInstanceOf(BadCommandException.class)
                .extracting(e -> ((KeyedException) e).messageKey())
                .isEqualTo("business.finance.foreign-account");
    }

    @Test
    void payIntoAccount_archived_throws() {
        when(firmAccounts.isFirmAccount(1, 99)).thenReturn(true);
        when(treasury.getAccountById(99)).thenReturn(account(99, true, false));
        assertThatThrownBy(() -> svc.payIntoAccount(1, 99, player, BigDecimal.ONE))
                .isInstanceOf(ConflictException.class)
                .extracting(e -> ((KeyedException) e).messageKey())
                .isEqualTo("business.finance.archived");
    }

    @Test
    void payIntoAccount_succeeds() {
        when(firms.getFirmById(1)).thenReturn(firm(1, 100));
        when(firmAccounts.isFirmAccount(1, 99)).thenReturn(true);
        when(treasury.getAccountById(99)).thenReturn(account(99, false, false));
        Account personal = new Account(); personal.setAccountId(7);
        when(treasury.resolveOrCreatePersonal(player)).thenReturn(personal);
        when(treasury.hasFunds(7, BigDecimal.ONE)).thenReturn(true);
        when(treasury.transfer(any())).thenReturn(66L);
        assertThat(svc.payIntoAccount(1, 99, player, BigDecimal.ONE)).isEqualTo(66L);
    }

    // ---------- payPlayer / payPlayerFromAccount (business -> player) ----------

    private final UUID target = UUID.randomUUID();

    @Test
    void payPlayer_noAccess_throws() {
        when(firms.getFirmById(1)).thenReturn(firm(1, 100));
        Account targetPersonal = new Account(); targetPersonal.setAccountId(7);
        when(treasury.resolveOrCreatePersonal(target)).thenReturn(targetPersonal);
        when(treasury.getAccountById(100)).thenReturn(account(100, false, false));
        when(treasury.canAccessAccount(player, 100)).thenReturn(false);
        assertThatThrownBy(() -> svc.payPlayer(1, target, player, BigDecimal.ONE))
                .isInstanceOf(NoPermissionException.class)
                .extracting(e -> ((KeyedException) e).messageKey())
                .isEqualTo("business.general.no-permission");
    }

    @Test
    void payPlayer_insufficientBusinessFunds_throws() {
        when(firms.getFirmById(1)).thenReturn(firm(1, 100));
        Account targetPersonal = new Account(); targetPersonal.setAccountId(7);
        when(treasury.resolveOrCreatePersonal(target)).thenReturn(targetPersonal);
        when(treasury.getAccountById(100)).thenReturn(account(100, false, false));
        when(treasury.canAccessAccount(player, 100)).thenReturn(true);
        when(treasury.hasFunds(100, BigDecimal.ONE)).thenReturn(false);
        assertThatThrownBy(() -> svc.payPlayer(1, target, player, BigDecimal.ONE))
                .isInstanceOf(ExceedsLimitException.class)
                .extracting(e -> ((KeyedException) e).messageKey())
                .isEqualTo("business.finance.insufficient-business");
    }

    @Test
    void payPlayer_succeeds() {
        when(firms.getFirmById(1)).thenReturn(firm(1, 100));
        Account targetPersonal = new Account(); targetPersonal.setAccountId(7);
        when(treasury.resolveOrCreatePersonal(target)).thenReturn(targetPersonal);
        when(treasury.getAccountById(100)).thenReturn(account(100, false, false));
        when(treasury.canAccessAccount(player, 100)).thenReturn(true);
        when(treasury.hasFunds(100, BigDecimal.TEN)).thenReturn(true);
        when(treasury.transfer(any())).thenReturn(77L);

        assertThat(svc.payPlayer(1, target, player, BigDecimal.TEN)).isEqualTo(77L);

        ArgumentCaptor<TransferRequest> req = ArgumentCaptor.forClass(TransferRequest.class);
        verify(treasury).transfer(req.capture());
        assertThat(req.getValue().fromAccountId()).isEqualTo(100);
        assertThat(req.getValue().toAccountId()).isEqualTo(7);
        assertThat(req.getValue().initiator()).isEqualTo(player);
    }

    @Test
    void payPlayer_authorizerRequired_isAuthorizer_succeeds() {
        when(firms.getFirmById(1)).thenReturn(firm(1, 100));
        Account targetPersonal = new Account(); targetPersonal.setAccountId(7);
        when(treasury.resolveOrCreatePersonal(target)).thenReturn(targetPersonal);
        when(treasury.getAccountById(100)).thenReturn(account(100, false, true));
        when(treasury.canAccessAccount(player, 100)).thenReturn(true);
        when(treasury.hasFunds(100, BigDecimal.ONE)).thenReturn(true);
        when(treasury.getAuthorizers(100)).thenReturn(List.of(
                new AccountMember(100, player, player, Instant.EPOCH)));
        when(treasury.transfer(any())).thenReturn(78L);

        assertThat(svc.payPlayer(1, target, player, BigDecimal.ONE)).isEqualTo(78L);

        ArgumentCaptor<TransferRequest> req = ArgumentCaptor.forClass(TransferRequest.class);
        verify(treasury).transfer(req.capture());
        assertThat(req.getValue().authorizer()).isEqualTo(player);
    }

    @Test
    void payPlayer_authorizerRequired_notAuthorizer_throws() {
        when(firms.getFirmById(1)).thenReturn(firm(1, 100));
        Account targetPersonal = new Account(); targetPersonal.setAccountId(7);
        when(treasury.resolveOrCreatePersonal(target)).thenReturn(targetPersonal);
        when(treasury.getAccountById(100)).thenReturn(account(100, false, true));
        when(treasury.canAccessAccount(player, 100)).thenReturn(true);
        when(treasury.hasFunds(100, BigDecimal.ONE)).thenReturn(true);
        when(treasury.getAuthorizers(100)).thenReturn(List.of());
        assertThatThrownBy(() -> svc.payPlayer(1, target, player, BigDecimal.ONE))
                .isInstanceOf(NoPermissionException.class)
                .extracting(e -> ((KeyedException) e).messageKey())
                .isEqualTo("business.finance.not-authorizer");
    }

    @Test
    void payPlayerFromAccount_rejectsForeignAccount() {
        when(firmAccounts.isFirmAccount(1, 99)).thenReturn(false);
        assertThatThrownBy(() -> svc.payPlayerFromAccount(1, 99, target, player, BigDecimal.ONE))
                .isInstanceOf(BadCommandException.class)
                .extracting(e -> ((KeyedException) e).messageKey())
                .isEqualTo("business.finance.foreign-account");
    }

    @Test
    void payPlayerFromAccount_succeeds() {
        when(firms.getFirmById(1)).thenReturn(firm(1, 100));
        when(firmAccounts.isFirmAccount(1, 99)).thenReturn(true);
        Account targetPersonal = new Account(); targetPersonal.setAccountId(7);
        when(treasury.resolveOrCreatePersonal(target)).thenReturn(targetPersonal);
        when(treasury.getAccountById(99)).thenReturn(account(99, false, false));
        when(treasury.canAccessAccount(player, 99)).thenReturn(true);
        when(treasury.hasFunds(99, BigDecimal.ONE)).thenReturn(true);
        when(treasury.transfer(any())).thenReturn(79L);
        assertThat(svc.payPlayerFromAccount(1, 99, target, player, BigDecimal.ONE)).isEqualTo(79L);
    }

    // ---------- payFirm / payFirmFromAccount (business -> business) ----------

    @Test
    void payFirm_succeeds() {
        when(firms.getFirmById(1)).thenReturn(firm(1, 100));
        when(firms.getFirmById(2)).thenReturn(firm(2, 200));
        when(treasury.getAccountById(100)).thenReturn(account(100, false, false));
        when(treasury.canAccessAccount(player, 100)).thenReturn(true);
        when(treasury.hasFunds(100, BigDecimal.TEN)).thenReturn(true);
        when(treasury.transfer(any())).thenReturn(80L);

        assertThat(svc.payFirm(1, 2, player, BigDecimal.TEN)).isEqualTo(80L);

        ArgumentCaptor<TransferRequest> req = ArgumentCaptor.forClass(TransferRequest.class);
        verify(treasury).transfer(req.capture());
        assertThat(req.getValue().fromAccountId()).isEqualTo(100);
        assertThat(req.getValue().toAccountId()).isEqualTo(200);
    }

    @Test
    void payFirm_sameAccount_throws() {
        when(firms.getFirmById(1)).thenReturn(firm(1, 100));
        when(firms.getFirmById(2)).thenReturn(firm(2, 100));
        assertThatThrownBy(() -> svc.payFirm(1, 2, player, BigDecimal.ONE))
                .isInstanceOf(BadCommandException.class)
                .extracting(e -> ((KeyedException) e).messageKey())
                .isEqualTo("business.finance.pay.same-firm");
    }

    @Test
    void payFirm_usesDefaultReasonFromFirmNames() {
        when(firms.getFirmById(1)).thenReturn(namedFirm(1, 100, "Acme"));
        when(firms.getFirmById(2)).thenReturn(namedFirm(2, 200, "Globex"));
        when(treasury.getAccountById(100)).thenReturn(account(100, false, false));
        when(treasury.canAccessAccount(player, 100)).thenReturn(true);
        when(treasury.hasFunds(100, BigDecimal.TEN)).thenReturn(true);
        when(treasury.transfer(any())).thenReturn(80L);

        svc.payFirm(1, 2, player, BigDecimal.TEN);

        ArgumentCaptor<TransferRequest> req = ArgumentCaptor.forClass(TransferRequest.class);
        verify(treasury).transfer(req.capture());
        assertThat(req.getValue().message()).isEqualTo("Business payment: Acme -> Globex");
    }

    @Test
    void payFirm_withMemo_appendsSanitizedMemoToReason() {
        when(firms.getFirmById(1)).thenReturn(namedFirm(1, 100, "Acme"));
        when(firms.getFirmById(2)).thenReturn(namedFirm(2, 200, "Globex"));
        when(treasury.getAccountById(100)).thenReturn(account(100, false, false));
        when(treasury.canAccessAccount(player, 100)).thenReturn(true);
        when(treasury.hasFunds(100, BigDecimal.TEN)).thenReturn(true);
        when(treasury.transfer(any())).thenReturn(80L);

        assertThat(svc.payFirm(1, 2, player, BigDecimal.TEN, "  invoice   42 ")).isEqualTo(80L);

        ArgumentCaptor<TransferRequest> req = ArgumentCaptor.forClass(TransferRequest.class);
        verify(treasury).transfer(req.capture());
        // Memo is whitespace-collapsed and trimmed, then appended after the firm names.
        assertThat(req.getValue().message()).isEqualTo("Business payment: Acme -> Globex: invoice 42");
    }

    @Test
    void payFirm_withBlankMemo_fallsBackToDefaultReason() {
        when(firms.getFirmById(1)).thenReturn(namedFirm(1, 100, "Acme"));
        when(firms.getFirmById(2)).thenReturn(namedFirm(2, 200, "Globex"));
        when(treasury.getAccountById(100)).thenReturn(account(100, false, false));
        when(treasury.canAccessAccount(player, 100)).thenReturn(true);
        when(treasury.hasFunds(100, BigDecimal.TEN)).thenReturn(true);
        when(treasury.transfer(any())).thenReturn(80L);

        svc.payFirm(1, 2, player, BigDecimal.TEN, "   ");

        ArgumentCaptor<TransferRequest> req = ArgumentCaptor.forClass(TransferRequest.class);
        verify(treasury).transfer(req.capture());
        assertThat(req.getValue().message()).isEqualTo("Business payment: Acme -> Globex");
    }

    @Test
    void payFirmFromAccount_rejectsForeignAccount() {
        when(firmAccounts.isFirmAccount(1, 99)).thenReturn(false);
        assertThatThrownBy(() -> svc.payFirmFromAccount(1, 99, 2, player, BigDecimal.ONE))
                .isInstanceOf(BadCommandException.class)
                .extracting(e -> ((KeyedException) e).messageKey())
                .isEqualTo("business.finance.foreign-account");
    }

    @Test
    void payFirmFromAccount_sameAccount_throws() {
        when(firmAccounts.isFirmAccount(1, 100)).thenReturn(true);
        when(firms.getFirmById(2)).thenReturn(firm(2, 100));
        assertThatThrownBy(() -> svc.payFirmFromAccount(1, 100, 2, player, BigDecimal.ONE))
                .isInstanceOf(BadCommandException.class)
                .extracting(e -> ((KeyedException) e).messageKey())
                .isEqualTo("business.finance.pay.same-firm");
    }

    @Test
    void payFirmFromAccount_succeeds() {
        when(firms.getFirmById(1)).thenReturn(firm(1, 100));
        when(firms.getFirmById(2)).thenReturn(firm(2, 200));
        when(firmAccounts.isFirmAccount(1, 99)).thenReturn(true);
        when(treasury.getAccountById(99)).thenReturn(account(99, false, false));
        when(treasury.canAccessAccount(player, 99)).thenReturn(true);
        when(treasury.hasFunds(99, BigDecimal.ONE)).thenReturn(true);
        when(treasury.transfer(any())).thenReturn(81L);
        assertThat(svc.payFirmFromAccount(1, 99, 2, player, BigDecimal.ONE)).isEqualTo(81L);
    }
}
