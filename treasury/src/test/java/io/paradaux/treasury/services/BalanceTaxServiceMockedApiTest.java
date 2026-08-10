package io.paradaux.treasury.services;

import io.paradaux.treasury.api.TaxApi;
import io.paradaux.treasury.mappers.AccountMapper;
import io.paradaux.treasury.model.config.BalanceTaxConfiguration;
import io.paradaux.treasury.model.economy.Account;
import io.paradaux.treasury.model.economy.AccountBalance;
import io.paradaux.treasury.model.tax.TaxCollection;
import io.paradaux.treasury.model.tax.TaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link BalanceTaxService} branches that are awkward to trigger
 * end-to-end: {@code Skipped}/{@code Failed} result handling, and the missing
 * balance row short-circuit. The previous-login epoch is supplied directly (the
 * player directory owns the login clock), so these never touch login storage.
 */
@ExtendWith(MockitoExtension.class)
class BalanceTaxServiceMockedApiTest {

    @Mock TaxApi taxApi;
    @Mock AccountMapper accountMapper;

    private BalanceTaxService svc;

    private static BalanceTaxConfiguration enabledConfig() {
        NavigableMap<BigDecimal, BigDecimal> brackets = new TreeMap<>();
        brackets.put(new BigDecimal("0.00"),       BigDecimal.ZERO);
        brackets.put(new BigDecimal("100000.00"),  new BigDecimal("0.01"));
        return BalanceTaxConfiguration.forTesting(true, "DCGovernment", brackets);
    }

    @BeforeEach
    void setUp() {
        svc = new io.paradaux.treasury.services.impl.BalanceTaxServiceImpl(enabledConfig(), taxApi, accountMapper);
    }

    @Test
    void missingBalanceRow_shortCircuitsBeforeCollect() {
        UUID player = UUID.randomUUID();
        long now = 1_700_000_000L;
        when(accountMapper.findPersonalAccountId(player)).thenReturn(42);
        when(accountMapper.readBalance(42)).thenReturn(null);

        svc.collect(player, now - 86_400, now);

        verify(taxApi, never()).collectTax(any());
    }

    @Test
    void nullBalanceField_shortCircuits() {
        UUID player = UUID.randomUUID();
        long now = 1_700_000_000L;
        when(accountMapper.findPersonalAccountId(player)).thenReturn(42);
        AccountBalance ab = new AccountBalance();
        ab.setBalance(null);
        when(accountMapper.readBalance(42)).thenReturn(ab);

        svc.collect(player, now - 86_400, now);

        verify(taxApi, never()).collectTax(any());
    }

    @Test
    void firstLogin_nullPrevious_shortCircuits() {
        UUID player = UUID.randomUUID();
        long now = 1_700_000_000L;

        svc.collect(player, null, now);

        verify(taxApi, never()).collectTax(any());
    }

    @Test
    void apiReturnsSkipped_logsAndContinues() {
        UUID player = UUID.randomUUID();
        long now = 1_700_000_000L;
        long lastLogin = now - 7L * 24 * 3600 / 2;
        when(accountMapper.findPersonalAccountId(player)).thenReturn(42);
        AccountBalance ab = new AccountBalance();
        ab.setBalance(new BigDecimal("100000.00"));
        when(accountMapper.readBalance(42)).thenReturn(ab);

        Account dest = new Account();
        dest.setAccountId(99);
        when(accountMapper.findGovernmentAccountByName("DCGovernment")).thenReturn(dest);

        when(taxApi.collectTax(any(TaxCollection.class)))
                .thenReturn(new TaxResult.Skipped("dedup"));

        svc.collect(player, lastLogin, now);

        verify(taxApi).collectTax(any(TaxCollection.class));
    }

    @Test
    void apiReturnsFailed_logsAndContinues() {
        UUID player = UUID.randomUUID();
        long now = 1_700_000_000L;
        long lastLogin = now - 7L * 24 * 3600 / 2;
        when(accountMapper.findPersonalAccountId(player)).thenReturn(42);
        AccountBalance ab = new AccountBalance();
        ab.setBalance(new BigDecimal("100000.00"));
        when(accountMapper.readBalance(42)).thenReturn(ab);

        Account dest = new Account();
        dest.setAccountId(99);
        when(accountMapper.findGovernmentAccountByName("DCGovernment")).thenReturn(dest);

        when(taxApi.collectTax(any(TaxCollection.class)))
                .thenReturn(new TaxResult.Failed("boom"));

        svc.collect(player, lastLogin, now);

        verify(taxApi).collectTax(any(TaxCollection.class));
    }

    @Test
    void apiReturnsCollected_logsSuccessBranch() {
        UUID player = UUID.randomUUID();
        long now = 1_700_000_000L;
        long lastLogin = now - 7L * 24 * 3600 / 2;
        when(accountMapper.findPersonalAccountId(player)).thenReturn(42);
        AccountBalance ab = new AccountBalance();
        ab.setBalance(new BigDecimal("100000.00"));
        when(accountMapper.readBalance(42)).thenReturn(ab);

        Account dest = new Account();
        dest.setAccountId(99);
        when(accountMapper.findGovernmentAccountByName("DCGovernment")).thenReturn(dest);

        when(taxApi.collectTax(any(TaxCollection.class)))
                .thenReturn(new TaxResult.Collected(1234L, new BigDecimal("500.00"), 99));

        svc.collect(player, lastLogin, now);

        // The Collected branch runs and the collection was routed to the resolved
        // government destination (99), not the default-tax fallback.
        ArgumentCaptor<TaxCollection> captor = ArgumentCaptor.forClass(TaxCollection.class);
        verify(taxApi).collectTax(captor.capture());
        assertThat(captor.getValue().destinationAccountId()).isEqualTo(99);
    }

    @Test
    void governmentAccountMissing_fallsBackToDefaultTaxAccount() {
        UUID player = UUID.randomUUID();
        long now = 1_700_000_000L;
        long lastLogin = now - 7L * 24 * 3600 / 2;
        when(accountMapper.findPersonalAccountId(player)).thenReturn(42);
        AccountBalance ab = new AccountBalance();
        ab.setBalance(new BigDecimal("100000.00"));
        when(accountMapper.readBalance(42)).thenReturn(ab);

        // Configured government account does not exist → resolveDestinationAccountId
        // must fall back to the default tax account id.
        when(accountMapper.findGovernmentAccountByName("DCGovernment")).thenReturn(null);
        when(taxApi.getDefaultTaxAccountId()).thenReturn(7);
        when(taxApi.collectTax(any(TaxCollection.class)))
                .thenReturn(new TaxResult.Collected(1L, new BigDecimal("500.00"), 7));

        svc.collect(player, lastLogin, now);

        ArgumentCaptor<TaxCollection> captor = ArgumentCaptor.forClass(TaxCollection.class);
        verify(taxApi).collectTax(captor.capture());
        assertThat(captor.getValue().destinationAccountId()).isEqualTo(7);
    }

    @Test
    void clockSkew_nonPositiveElapsed_shortCircuits() {
        UUID player = UUID.randomUUID();
        long now = 1_700_000_000L;

        // previousLoginEpoch in the future → secondsElapsed <= 0 guard.
        svc.collect(player, now + 100, now);

        verify(taxApi, never()).collectTax(any());
    }

    @Test
    void noPersonalAccount_shortCircuits() {
        UUID player = UUID.randomUUID();
        long now = 1_700_000_000L;
        when(accountMapper.findPersonalAccountId(player)).thenReturn(null);

        svc.collect(player, now - 86_400, now);

        verify(taxApi, never()).collectTax(any());
    }

    @Test
    void zeroRateBracket_shortCircuitsBeforeCollect() {
        UUID player = UUID.randomUUID();
        long now = 1_700_000_000L;
        long lastLogin = now - 7L * 24 * 3600 / 2;
        when(accountMapper.findPersonalAccountId(player)).thenReturn(42);
        AccountBalance ab = new AccountBalance();
        // Balance below the 100000 bracket → 0.00 weekly rate → weeklyRate<=0 guard.
        ab.setBalance(new BigDecimal("50.00"));
        when(accountMapper.readBalance(42)).thenReturn(ab);

        svc.collect(player, lastLogin, now);

        verify(taxApi, never()).collectTax(any());
    }

    @Test
    void subMinimumTax_shortCircuits_belowOneCent() {
        UUID player = UUID.randomUUID();
        long now = 1_700_000_000L;
        // Balance is in the 1%/wk bracket but the proration window is one second, so
        // the computed tax rounds below Money.MINIMUM_AMOUNT (0.01) → skip.
        long lastLogin = now - 1;
        when(accountMapper.findPersonalAccountId(player)).thenReturn(42);
        AccountBalance ab = new AccountBalance();
        ab.setBalance(new BigDecimal("100000.00"));
        when(accountMapper.readBalance(42)).thenReturn(ab);

        svc.collect(player, lastLogin, now);

        verify(taxApi, never()).collectTax(any());
    }

    @Test
    void destinationResolvedOnce_cachedAcrossCollections() {
        // resolveDestinationAccountId caches the id; a second collection must not
        // re-query the government account name.
        Account dest = new Account();
        dest.setAccountId(99);
        when(accountMapper.findGovernmentAccountByName("DCGovernment")).thenReturn(dest);
        when(taxApi.collectTax(any(TaxCollection.class)))
                .thenReturn(new TaxResult.Collected(1L, new BigDecimal("500.00"), 99));

        long now = 1_700_000_000L;
        long lastLogin = now - 7L * 24 * 3600 / 2;
        for (int i = 0; i < 2; i++) {
            UUID player = UUID.randomUUID();
            when(accountMapper.findPersonalAccountId(player)).thenReturn(42);
            AccountBalance ab = new AccountBalance();
            ab.setBalance(new BigDecimal("100000.00"));
            when(accountMapper.readBalance(42)).thenReturn(ab);
            svc.collect(player, lastLogin, now);
        }

        verify(accountMapper, org.mockito.Mockito.times(1))
                .findGovernmentAccountByName("DCGovernment");
    }
}
