package io.paradaux.treasury.api.impl;

import io.paradaux.treasury.mappers.AccountMapper;
import io.paradaux.treasury.model.config.SourceIncomeTaxConfiguration;
import io.paradaux.treasury.model.config.TaxCycleConfiguration;
import io.paradaux.treasury.model.economy.Account;
import io.paradaux.treasury.model.economy.TransferRequest;
import io.paradaux.treasury.model.tax.TaxCollection;
import io.paradaux.treasury.model.tax.TaxCycleType;
import io.paradaux.treasury.model.tax.TaxResult;
import io.paradaux.treasury.services.EconomyNotifier;
import io.paradaux.treasury.services.LedgerService;
import io.paradaux.treasury.services.TaxCycleRegistry;
import io.paradaux.treasury.testsupport.TestConfigs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The tax engine's money decisions in isolation: the one-cent floor (inclusive),
 * half-even rounding of rate taxes, where the money goes, and that every outcome
 * is counted in the cycle report. Ledger behaviour itself is covered by the ITs.
 */
@ExtendWith(MockitoExtension.class)
class TaxApiImplTest {

    private static final UUID SYSTEM = UUID.randomUUID();
    private static final int DEFAULT_TAX_ACCOUNT = 77;

    @Mock LedgerService ledger;
    @Mock AccountMapper accounts;
    @Mock TaxCycleRegistry registry;
    @Mock EconomyNotifier notifier;

    private TaxApiImpl api(SourceIncomeTaxConfiguration sit, TaxCycleConfiguration cycles) {
        return new TaxApiImpl(ledger, accounts, TestConfigs.government(), cycles, sit, registry, notifier);
    }

    private TaxApiImpl api() {
        return api(SourceIncomeTaxConfiguration.forTesting(false, BigDecimal.ZERO, "DCGovernment", Map.of()),
                TestConfigs.taxCyclesAllDisabled());
    }

    private static Account account(int id) {
        Account a = new Account();
        a.setAccountId(id);
        return a;
    }

    private TransferRequest capturedTransfer() {
        ArgumentCaptor<TransferRequest> req = ArgumentCaptor.forClass(TransferRequest.class);
        verify(ledger).transfer(req.capture());
        return req.getValue();
    }

    // ---- collectTax ----

    @Test
    void collectTax_exactlyOneCent_isCollected_countedAndNotified() {
        when(ledger.transfer(any())).thenReturn(900L);
        TaxApiImpl api = api();

        TaxResult result = api.collectTax(TaxCollection.toAccount(
                5, 6, new BigDecimal("0.01"), "income", "desc", SYSTEM, "plugin", null));

        assertThat(result).isEqualTo(new TaxResult.Collected(900L, new BigDecimal("0.01"), 6));
        TransferRequest req = capturedTransfer();
        assertThat(req.fromAccountId()).isEqualTo(5);
        assertThat(req.toAccountId()).isEqualTo(6);
        assertThat(req.amount()).isEqualByComparingTo("0.01");
        assertThat(req.initiator()).isEqualTo(SYSTEM);
        assertThat(req.authorizer()).isNull();
        assertThat(req.pluginSystem()).isEqualTo("plugin");
        verify(registry).recordCollected(6, "income", new BigDecimal("0.01"));
        verify(notifier).notifyTaxCollected(5, "income", new BigDecimal("0.01"));
        verify(registry, never()).recordSkipped();
        verify(registry, never()).recordFailed();
    }

    @Test
    void collectTax_belowOneCent_isSkippedAndCounted_withoutTouchingTheLedger() {
        TaxResult result = api().collectTax(TaxCollection.toAccount(
                5, 6, new BigDecimal("0.009"), "income", "desc", SYSTEM, null, null));

        assertThat(result).isInstanceOf(TaxResult.Skipped.class);
        assertThat(((TaxResult.Skipped) result).reason()).contains("0.009").contains("below the $0.01 minimum");
        verify(registry).recordSkipped();
        verifyNoInteractions(ledger, notifier);
    }

    @Test
    void collectTax_withoutDestination_goesToTheDefaultTaxAccount_resolvedOnce() {
        when(accounts.findGovernmentAccountByName("DCGovernment")).thenReturn(account(DEFAULT_TAX_ACCOUNT));
        when(ledger.transfer(any())).thenReturn(1L, 2L);
        TaxApiImpl api = api();

        api.collectTax(TaxCollection.toDefaultAccount(5, new BigDecimal("1.00"), "t", "d", SYSTEM, null, null));
        TaxResult second = api.collectTax(
                TaxCollection.toDefaultAccount(5, new BigDecimal("2.00"), "t", "d", SYSTEM, null, null));

        assertThat(((TaxResult.Collected) second).destinationAccountId()).isEqualTo(DEFAULT_TAX_ACCOUNT);
        assertThat(api.getDefaultTaxAccountId()).isEqualTo(DEFAULT_TAX_ACCOUNT);
        verify(accounts, times(1)).findGovernmentAccountByName("DCGovernment");
    }

    @Test
    void defaultTaxAccount_missing_isCreatedOnDemand() {
        when(accounts.findGovernmentAccountByName("DCGovernment")).thenReturn(null);
        org.mockito.Mockito.doAnswer(inv -> {
            ((Account) inv.getArgument(0)).setAccountId(91);
            return null;
        }).when(accounts).insertAccount(any());

        assertThat(api().getDefaultTaxAccountId()).isEqualTo(91);
        verify(accounts).seedBalance(91);
    }

    @Test
    void collectTax_ledgerFailure_isReportedAsFailed_andCounted_notCollected() {
        when(ledger.transfer(any()))
                .thenThrow(new IllegalStateException("Insufficient funds"))
                .thenThrow(new IllegalStateException());
        TaxApiImpl api = api();
        TaxCollection c = TaxCollection.toAccount(5, 6, new BigDecimal("3.00"), "t", "d", SYSTEM, null, null);

        assertThat(api.collectTax(c)).isEqualTo(new TaxResult.Failed("Insufficient funds"));
        assertThat(api.collectTax(c)).isEqualTo(new TaxResult.Failed("Transfer failed"));
        verify(registry, times(2)).recordFailed();
        verify(registry, never()).recordCollected(org.mockito.ArgumentMatchers.anyInt(), anyString(), any());
        verifyNoInteractions(notifier);
    }

    // ---- collectRateTax ----

    @ParameterizedTest
    @CsvSource({
            // base, rate, expected tax ("skip" = below one cent)
            "0.50, 0.02, 0.01",     // exactly one cent: collected
            "0.25, 0.02, skip",     // 0.005 -> half-even 0.00
            "0.75, 0.02, 0.02",     // 0.015 -> half-even 0.02
            "1.25, 0.02, 0.02",     // 0.025 -> half-even 0.02 (not 0.03)
            "100.00, 0.05, 5.00",
    })
    void collectRateTax_roundsHalfEven_andAppliesTheOneCentFloorInclusively(String base, String rate, String expected) {
        TaxApiImpl api = api();
        boolean skipped = expected.equals("skip");
        if (!skipped) when(ledger.transfer(any())).thenReturn(1L);

        TaxResult result = api.collectRateTax(5, 6, new BigDecimal(base), new BigDecimal(rate),
                "sales", "d", SYSTEM, null, null);

        if (skipped) {
            assertThat(result).isInstanceOf(TaxResult.Skipped.class);
            verifyNoInteractions(ledger);
        } else {
            assertThat(((TaxResult.Collected) result).amountCharged()).isEqualByComparingTo(expected);
            assertThat(capturedTransfer().amount()).isEqualByComparingTo(expected);
        }
    }

    @Test
    void collectRateTax_toDefaultAccount_appliesTheSameFloor() {
        TaxResult result = api().collectRateTax(5, new BigDecimal("0.24"), new BigDecimal("0.02"),
                "sales", "d", SYSTEM, null, null);
        assertThat(result).isInstanceOf(TaxResult.Skipped.class);
        verifyNoInteractions(ledger);
    }

    // ---- source income tax ----

    @Test
    void sourceIncomeTax_disabled_isSkipped() {
        TaxResult r = api(SourceIncomeTaxConfiguration.forTesting(false, new BigDecimal("0.10"), "Gov", Map.of()),
                TestConfigs.taxCyclesAllDisabled()).applySourceIncomeTax(UUID.randomUUID(), new BigDecimal("100"), "Jobs");
        assertThat(r).isEqualTo(new TaxResult.Skipped("Source income tax is disabled"));
        verifyNoInteractions(ledger, accounts);
    }

    @Test
    void sourceIncomeTax_zeroEffectiveRateForThePlugin_isSkipped() {
        TaxResult r = api(SourceIncomeTaxConfiguration.forTesting(true, new BigDecimal("0.10"), "Gov",
                Map.of("Jobs", BigDecimal.ZERO)), TestConfigs.taxCyclesAllDisabled())
                .applySourceIncomeTax(UUID.randomUUID(), new BigDecimal("100"), "Jobs");
        assertThat(r).isInstanceOf(TaxResult.Skipped.class);
        verifyNoInteractions(ledger, accounts);
    }

    @Test
    void sourceIncomeTax_collectsRateTimesDeposit_fromThePlayer_intoTheConfiguredAccount() {
        UUID player = UUID.randomUUID();
        when(accounts.findPersonalAccountId(player)).thenReturn(12);
        when(accounts.findGovernmentAccountByName("IncomeTax")).thenReturn(account(55));
        when(ledger.transfer(any())).thenReturn(1L);
        TaxApiImpl api = api(SourceIncomeTaxConfiguration.forTesting(true, new BigDecimal("0.10"), "IncomeTax",
                Map.of("Realty", new BigDecimal("0.0001"))), TestConfigs.taxCyclesAllDisabled());

        TaxResult r = api.applySourceIncomeTax(player, new BigDecimal("100.00"), "Jobs");

        assertThat(r).isEqualTo(new TaxResult.Collected(1L, new BigDecimal("10.00"), 55));
        TransferRequest req = capturedTransfer();
        assertThat(req.fromAccountId()).isEqualTo(12);
        assertThat(req.toAccountId()).isEqualTo(55);
        verify(registry).recordCollected(55, "source-income-tax", new BigDecimal("10.00"));
    }

    @Test
    void sourceIncomeTax_belowOneCent_isSkipped_exactlyOneCentIsCollected() {
        UUID player = UUID.randomUUID();
        when(accounts.findPersonalAccountId(player)).thenReturn(12);
        when(accounts.findGovernmentAccountByName("IncomeTax")).thenReturn(account(55));
        when(ledger.transfer(any())).thenReturn(1L);
        TaxApiImpl api = api(SourceIncomeTaxConfiguration.forTesting(true, new BigDecimal("0.01"), "IncomeTax",
                Map.of()), TestConfigs.taxCyclesAllDisabled());

        assertThat(api.applySourceIncomeTax(player, new BigDecimal("0.49"), "Jobs"))
                .isInstanceOf(TaxResult.Skipped.class);           // 0.0049 -> 0.00
        assertThat(api.applySourceIncomeTax(player, new BigDecimal("1.00"), "Jobs"))
                .isInstanceOf(TaxResult.Collected.class);         // exactly 0.01
        verify(ledger, times(1)).transfer(any());
    }

    @Test
    void sourceIncomeTax_playerWithoutAccount_isSkipped() {
        UUID player = UUID.randomUUID();
        when(accounts.findPersonalAccountId(player)).thenReturn(null);
        TaxResult r = api(SourceIncomeTaxConfiguration.forTesting(true, new BigDecimal("0.10"), "Gov", Map.of()),
                TestConfigs.taxCyclesAllDisabled()).applySourceIncomeTax(player, new BigDecimal("100"), "Jobs");
        assertThat(r).isInstanceOf(TaxResult.Skipped.class);
        verifyNoInteractions(ledger);
    }

    @Test
    void sourceIncomeTax_missingConfiguredAccount_fallsBackToTheDefaultTaxAccount() {
        UUID player = UUID.randomUUID();
        when(accounts.findPersonalAccountId(player)).thenReturn(12);
        when(accounts.findGovernmentAccountByName("Nope")).thenReturn(null);
        when(accounts.findGovernmentAccountByName("DCGovernment")).thenReturn(account(DEFAULT_TAX_ACCOUNT));
        when(ledger.transfer(any())).thenReturn(1L);

        TaxResult r = api(SourceIncomeTaxConfiguration.forTesting(true, new BigDecimal("0.10"), "Nope", Map.of()),
                TestConfigs.taxCyclesAllDisabled()).applySourceIncomeTax(player, new BigDecimal("10"), "Jobs");

        assertThat(((TaxResult.Collected) r).destinationAccountId()).isEqualTo(DEFAULT_TAX_ACCOUNT);
    }

    // ---- cycle introspection ----

    @Test
    void isCycleEnabled_readsTheMatchingFlag() {
        TaxCycleConfiguration cycles = mock(TaxCycleConfiguration.class);
        when(cycles.isDailyEnabled()).thenReturn(true);
        when(cycles.isWeeklyEnabled()).thenReturn(false);
        when(cycles.isMonthlyEnabled()).thenReturn(true);
        TaxApiImpl api = api(SourceIncomeTaxConfiguration.forTesting(false, BigDecimal.ZERO, "x", Map.of()), cycles);

        assertThat(api.isCycleEnabled(TaxCycleType.DAILY)).isTrue();
        assertThat(api.isCycleEnabled(TaxCycleType.WEEKLY)).isFalse();
        assertThat(api.isCycleEnabled(TaxCycleType.MONTHLY)).isTrue();
    }

    @Test
    void nextFireTime_comesFromTheRegistry() {
        Instant next = Instant.parse("2026-01-01T03:00:00Z");
        when(registry.getNextFireTime(TaxCycleType.WEEKLY)).thenReturn(Optional.of(next));
        assertThat(api().getNextFireTime(TaxCycleType.WEEKLY)).contains(next);
    }
}
