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

    @Test
    void reloadDuringCollectionKeepsRateAndDestinationTogetherAndRefreshesCacheNextTime() {
        var config = enabledConfig();
        svc = new io.paradaux.treasury.services.impl.BalanceTaxServiceImpl(config, taxApi, accountMapper);
        UUID player = UUID.randomUUID();
        when(accountMapper.findPersonalAccountId(player)).thenReturn(42);
        AccountBalance balance = new AccountBalance();
        balance.setBalance(new BigDecimal("100000"));
        var replacement = BalanceTaxConfiguration.forTesting(true, "NewGovernment",
                new TreeMap<>(java.util.Map.of(BigDecimal.ZERO, new BigDecimal("0.02")))).snapshot();
        when(accountMapper.readBalance(42)).thenAnswer(invocation -> {
            config.publish(replacement);
            return balance;
        });
        Account oldGovernment = new Account();
        oldGovernment.setAccountId(7);
        Account newGovernment = new Account();
        newGovernment.setAccountId(8);
        when(accountMapper.findGovernmentAccountByName("DCGovernment")).thenReturn(oldGovernment);
        when(accountMapper.findGovernmentAccountByName("NewGovernment")).thenReturn(newGovernment);
        when(taxApi.collectTax(any())).thenReturn(new TaxResult.Skipped("test"));
        svc.collect(player, 0L, 604800L);
        svc.collect(player, 604800L, 1209600L);
        var captured = ArgumentCaptor.forClass(TaxCollection.class);
        verify(taxApi, org.mockito.Mockito.times(2)).collectTax(captured.capture());
        assertThat(captured.getAllValues().get(0).destinationAccountId()).isEqualTo(7);
        assertThat(captured.getAllValues().get(0).amount()).isEqualByComparingTo("1000");
        assertThat(captured.getAllValues().get(1).destinationAccountId()).isEqualTo(8);
        assertThat(captured.getAllValues().get(1).amount()).isEqualByComparingTo("2000");
    }

    // ---- the taxed amount itself: proration, rounding, the one-cent floor ----

    private TaxCollection collectedFor(UUID player, String balance, long previous, long now) {
        when(accountMapper.findPersonalAccountId(player)).thenReturn(42);
        AccountBalance ab = new AccountBalance();
        ab.setBalance(new BigDecimal(balance));
        when(accountMapper.readBalance(42)).thenReturn(ab);
        Account dest = new Account();
        dest.setAccountId(99);
        // lenient: the destination is cached after the first collection.
        org.mockito.Mockito.lenient().when(accountMapper.findGovernmentAccountByName("DCGovernment")).thenReturn(dest);
        when(taxApi.collectTax(any(TaxCollection.class)))
                .thenReturn(new TaxResult.Collected(1L, BigDecimal.ONE, 99));

        svc.collect(player, previous, now);

        ArgumentCaptor<TaxCollection> captor = ArgumentCaptor.forClass(TaxCollection.class);
        verify(taxApi).collectTax(captor.capture());
        return captor.getValue();
    }

    @Test
    void halfAWeek_atOnePercentPerWeek_chargesExactlyHalfAPercent_fromThePlayersAccount() {
        UUID player = UUID.randomUUID();
        long now = 1_700_000_000L;
        long previous = now - 7L * 24 * 3600 / 2; // 3d 12h

        TaxCollection c = collectedFor(player, "100000.00", previous, now);

        assertThat(c.amount()).isEqualTo(new BigDecimal("500.00"));
        assertThat(c.sourceAccountId()).isEqualTo(42);
        assertThat(c.destinationAccountId()).isEqualTo(99);
        assertThat(c.taxType()).isEqualTo("personal-balance-tax");
        assertThat(c.description()).isEqualTo("Personal balance tax (3d 12h @ 1%/wk)");
        // One collection per (player, login): a replayed login event can't tax twice.
        assertThat(c.dedupKey()).isEqualTo(io.paradaux.treasury.utils.Idempotency.sha256(
                "balance-tax:" + player + ":" + now));
    }

    @Test
    void sixSeconds_roundsHalfEvenUpToExactlyOneCent_andIsCollected() {
        // 100000 * 1% * 6/604800 = 0.00992 -> 0.01: exactly the minimum, which is
        // collected (the floor is "below one cent", not "one cent or less").
        UUID player = UUID.randomUUID();
        long now = 1_700_000_000L;

        TaxCollection c = collectedFor(player, "100000.00", now - 6, now);

        assertThat(c.amount()).isEqualTo(new BigDecimal("0.01"));
        assertThat(c.description()).isEqualTo("Personal balance tax (0h 0m @ 1%/wk)");
    }

    @Test
    void oneSecondElapsed_isTaxed_zeroSecondsIsNot() {
        UUID player = UUID.randomUUID();
        long now = 1_700_000_000L;

        svc.collect(player, now, now); // same-second reconnect
        verify(taxApi, never()).collectTax(any());

        // 1_000_000 * 1% / 604800 = 0.01653... -> 0.02
        TaxCollection c = collectedFor(player, "1000000.00", now - 1, now);
        assertThat(c.amount()).isEqualTo(new BigDecimal("0.02"));
    }

    @Test
    void subDayPeriod_isDescribedInHoursAndMinutes_multiDayInDaysAndHours() {
        UUID player = UUID.randomUUID();
        long now = 1_700_000_000L;

        TaxCollection hours = collectedFor(player, "100000.00", now - (5 * 3600 + 30 * 60), now);
        assertThat(hours.description()).startsWith("Personal balance tax (5h 30m @");

        org.mockito.Mockito.clearInvocations(taxApi);
        TaxCollection days = collectedFor(player, "100000.00", now - (2 * 86400 + 3 * 3600 + 59 * 60), now);
        assertThat(days.description()).startsWith("Personal balance tax (2d 3h @");
    }

    @Test
    void disabled_readsNothing_andCollectsNothing() {
        BalanceTaxService disabled = new io.paradaux.treasury.services.impl.BalanceTaxServiceImpl(
                BalanceTaxConfiguration.forTesting(false, "DCGovernment", new TreeMap<>(java.util.Map.of(
                        BigDecimal.ZERO, new BigDecimal("0.05")))), taxApi, accountMapper);

        disabled.collect(UUID.randomUUID(), 1_000L, 1_000_000L);

        org.mockito.Mockito.verifyNoInteractions(taxApi, accountMapper);
    }
}
