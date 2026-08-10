package io.paradaux.treasury.services.impl;

import io.paradaux.treasury.mappers.AccountMapper;
import io.paradaux.treasury.mappers.LedgerMapper;
import io.paradaux.treasury.model.config.EconomyConfiguration;
import io.paradaux.treasury.model.config.GovernmentConfiguration;
import io.paradaux.treasury.model.economy.AccountBalance;
import io.paradaux.treasury.model.economy.LedgerPosting;
import io.paradaux.treasury.model.economy.LedgerTxn;
import io.paradaux.treasury.services.AccountService;
import io.paradaux.treasury.services.MembershipService;
import io.paradaux.treasury.services.cache.PersonalAccountCache;
import io.paradaux.treasury.services.cache.PluginSystemAccountCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for {@link LedgerServiceImpl#sweepAll}: the sweep must move the amount
 * read UNDER the {@code FOR UPDATE} lock ({@link AccountMapper#lockBalances}), not any
 * stale snapshot, so a firm-disband drain conserves value exactly
 * (business/behaviour/0003).
 */
@ExtendWith(MockitoExtension.class)
class LedgerServiceSweepAllTest {

    @Mock AccountMapper accountMapper;
    @Mock MembershipService membershipService;
    @Mock LedgerMapper ledgerMapper;
    @Mock AccountService accountService;
    @Mock PluginSystemAccountCache systemAccountCache;
    @Mock PersonalAccountCache personalAccountCache;
    @Mock EconomyConfiguration economyConfig;
    @Mock GovernmentConfiguration govConfig;

    private LedgerServiceImpl newService() {
        return new LedgerServiceImpl(accountMapper, membershipService, ledgerMapper,
                accountService, systemAccountCache, personalAccountCache, economyConfig, govConfig);
    }

    private void stubTxnInsert(long txnId) {
        doAnswer(inv -> {
            ((LedgerTxn) inv.getArgument(0)).setTxnId(txnId);
            return 1;
        }).when(ledgerMapper).insertTxnEntity(any());
    }

    @Test
    void sweepAll_movesTheLockedBalance_notAStaleSnapshot() {
        // A concurrent credit lands after any pre-lock snapshot, so the FOR UPDATE read
        // sees 300.00 even though a stale read-only snapshot would report 250.00. The
        // sweep must move the LOCKED 300.00 so no residual is stranded in the source.
        // A stale non-locking snapshot would report 250.00; sweepAll must ignore it
        // entirely and use the locked value. lenient() because "never consulted" is the
        // assertion — the stub exists only to prove it is NOT the source of the amount.
        org.mockito.Mockito.lenient().when(accountService.getBalanceReadOnly(10))
                .thenReturn(new BigDecimal("250.00"));
        when(accountMapper.lockBalances(List.of(10, 99)))
                .thenReturn(List.of(new AccountBalance(10, new BigDecimal("300.00"), 7L),
                                    new AccountBalance(99, new BigDecimal("5.00"), 3L)));
        stubTxnInsert(4242L);

        UUID initiator = UUID.randomUUID();
        OptionalLong txn = newService().sweepAll(10, 99, "Firm disbanded", initiator, "BusinessPlugin");

        assertThat(txn).hasValue(4242L);

        // Ledger provenance is the caller-supplied source plugin, not Treasury-Core, so a
        // disband drain keeps the same plugin_system its ordinary transfers carry.
        ArgumentCaptor<LedgerTxn> txnCaptor = ArgumentCaptor.forClass(LedgerTxn.class);
        verify(ledgerMapper).insertTxnEntity(txnCaptor.capture());
        assertThat(txnCaptor.getValue().getPluginSystem()).isEqualTo("BusinessPlugin");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LedgerPosting>> captor = ArgumentCaptor.forClass(List.class);
        verify(ledgerMapper).insertPostings(captor.capture());
        List<LedgerPosting> postings = captor.getValue();

        // Two balanced legs that net to zero: debit source, credit destination.
        assertThat(postings).hasSize(2);
        BigDecimal sum = postings.stream().map(LedgerPosting::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo(BigDecimal.ZERO);

        LedgerPosting debit  = postings.stream().filter(p -> p.accountId() == 10).findFirst().orElseThrow();
        LedgerPosting credit = postings.stream().filter(p -> p.accountId() == 99).findFirst().orElseThrow();
        // The moved amount is the LOCKED balance (300.00), never the stale 250.00 snapshot.
        assertThat(debit.amount()).isEqualByComparingTo("-300.00");
        assertThat(credit.amount()).isEqualByComparingTo("300.00");
    }

    @Test
    void sweepAll_locksBothRowsInAscendingAccountIdOrder() {
        // Deadlock-safety: both balance rows are locked in one round-trip, ascending id,
        // identical to the transfer path — regardless of sweep direction.
        when(accountMapper.lockBalances(List.of(10, 99)))
                .thenReturn(List.of(new AccountBalance(10, BigDecimal.ZERO, 1L),
                                    new AccountBalance(99, new BigDecimal("40.00"), 1L)));
        stubTxnInsert(1L);

        // Sweep from the HIGHER id (99) to the LOWER id (10): lock order must still be
        // the ascending [10, 99], not from-then-to.
        newService().sweepAll(99, 10, "Firm disbanded", UUID.randomUUID(), "BusinessPlugin");

        verify(accountMapper).lockBalances(List.of(10, 99));
    }

    @Test
    void sweepAll_zeroLockedBalance_isNoOp_emitsNoLedger() {
        when(accountMapper.lockBalances(List.of(10, 99)))
                .thenReturn(List.of(new AccountBalance(10, BigDecimal.ZERO, 1L),
                                    new AccountBalance(99, BigDecimal.ZERO, 1L)));

        OptionalLong txn = newService().sweepAll(10, 99, "Firm disbanded", UUID.randomUUID(), "BusinessPlugin");

        assertThat(txn).isEmpty();
        verify(ledgerMapper, never()).insertTxnEntity(any());
        verify(ledgerMapper, never()).insertPostings(any());
    }

    @Test
    void sweepAll_negativeLockedBalance_isNoOp() {
        when(accountMapper.lockBalances(List.of(10, 99)))
                .thenReturn(List.of(new AccountBalance(10, new BigDecimal("-5.00"), 1L),
                                    new AccountBalance(99, BigDecimal.ZERO, 1L)));

        assertThat(newService().sweepAll(10, 99, "memo", UUID.randomUUID(), "BusinessPlugin")).isEmpty();
        verify(ledgerMapper, never()).insertPostings(any());
    }

    @Test
    void sweepAll_sameAccount_throwsWithoutTouchingTheLedger() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> newService().sweepAll(10, 10, "memo", UUID.randomUUID(), "BusinessPlugin"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(accountMapper, never()).lockBalances(any());
        verify(ledgerMapper, never()).insertPostings(any());
    }

    @Test
    void sweepAll_missingSourceBalanceRow_throws() {
        // Destination row present, source row absent — a corrupt/missing balance must not
        // silently sweep nothing; it surfaces as an error.
        when(accountMapper.lockBalances(List.of(10, 99)))
                .thenReturn(List.of(new AccountBalance(99, BigDecimal.ZERO, 1L)));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> newService().sweepAll(10, 99, "memo", UUID.randomUUID(), "BusinessPlugin"))
                .isInstanceOf(IllegalStateException.class);
        verify(ledgerMapper, never()).insertPostings(any());
    }
}
