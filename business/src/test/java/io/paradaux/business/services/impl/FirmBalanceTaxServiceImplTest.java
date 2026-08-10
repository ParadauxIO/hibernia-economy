package io.paradaux.business.services.impl;

import io.paradaux.business.Business;
import io.paradaux.business.model.Firm;
import io.paradaux.business.model.config.BalanceTaxConfiguration;
import io.paradaux.business.services.FirmAccountService;
import io.paradaux.business.services.FirmBalanceTaxService.BalanceTaxCycleResult;
import io.paradaux.business.services.FirmBalanceTaxService.WeeklyTaxEstimate;
import io.paradaux.business.services.FirmPropertyService;
import io.paradaux.business.services.FirmService;
import io.paradaux.business.services.FirmTransactionService;
import io.paradaux.treasury.api.TaxApi;
import io.paradaux.treasury.api.TreasuryApi;
import io.paradaux.treasury.event.TaxCycleEvent;
import io.paradaux.treasury.model.economy.Account;
import io.paradaux.treasury.model.tax.TaxCollection;
import io.paradaux.treasury.model.tax.TaxResult;
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
import java.util.Optional;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FirmBalanceTaxServiceImplTest {

    @Mock BalanceTaxConfiguration config;
    @Mock FirmService firmService;
    @Mock FirmPropertyService firmPropertyService;
    @Mock FirmAccountService firmAccountService;
    @Mock FirmTransactionService firmTransactionService;
    @Mock TreasuryApi treasury;
    @Mock Business plugin;
    @Mock TaxCycleEvent event;
    @Mock TaxApi taxApi;

    private FirmBalanceTaxServiceImpl svc;

    @BeforeEach
    void setUp() {
        when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
        svc = new FirmBalanceTaxServiceImpl(config, firmService, firmPropertyService,
                firmAccountService, firmTransactionService, treasury, plugin);

        // By default the configured destination account resolves and the event
        // exposes the tax API + a stable period start. Individual tests override.
        lenient().when(config.getGovernmentAccount()).thenReturn("Treasury");
        lenient().when(treasury.getGovernmentAccountByName("Treasury")).thenReturn(govAccount(500));
        lenient().when(event.getTaxApi()).thenReturn(taxApi);
        lenient().when(event.getPeriodStart()).thenReturn(Instant.parse("2025-01-01T00:00:00Z"));
        lenient().when(firmPropertyService.getBoolean(anyInt(), anyString())).thenReturn(Optional.of(false));
    }

    private Firm firm(int id) {
        Firm f = new Firm();
        f.setFirmId(id);
        return f;
    }

    private Account govAccount(int id) {
        Account a = new Account();
        a.setAccountId(id);
        return a;
    }

    @Test
    void noFirms_returnsZeroTally_andDoesNotCallTaxApi() {
        when(firmService.listAllActiveFirms()).thenReturn(List.of());

        BalanceTaxCycleResult result = svc.runWeeklyCycle(event);

        assertThat(result).isEqualTo(new BalanceTaxCycleResult(0, 0, 0));
        verify(taxApi, never()).collectBatch(anyList());
    }

    @Test
    void exemptFirm_isSkipped_producingNoCollections() {
        when(firmService.listAllActiveFirms()).thenReturn(List.of(firm(1)));
        when(firmPropertyService.getBoolean(1, "balance-tax.exempt")).thenReturn(Optional.of(true));

        BalanceTaxCycleResult result = svc.runWeeklyCycle(event);

        assertThat(result).isEqualTo(new BalanceTaxCycleResult(0, 0, 0));
        verify(firmAccountService, never()).listAccountIds(anyInt());
        verify(taxApi, never()).collectBatch(anyList());
    }

    @Test
    void firmWithNoAccounts_producesNoCollections() {
        when(firmService.listAllActiveFirms()).thenReturn(List.of(firm(1)));
        when(firmAccountService.listAccountIds(1)).thenReturn(List.of());

        BalanceTaxCycleResult result = svc.runWeeklyCycle(event);

        assertThat(result).isEqualTo(new BalanceTaxCycleResult(0, 0, 0));
        verify(taxApi, never()).collectBatch(anyList());
    }

    @Test
    void firmWithOnlyZeroBalances_producesNoCollections() {
        when(firmService.listAllActiveFirms()).thenReturn(List.of(firm(1)));
        when(firmAccountService.listAccountIds(1)).thenReturn(List.of(10, 11));
        when(treasury.getBalancesByIds(List.of(10, 11)))
                .thenReturn(Map.of(10, BigDecimal.ZERO, 11, BigDecimal.ZERO));

        BalanceTaxCycleResult result = svc.runWeeklyCycle(event);

        assertThat(result).isEqualTo(new BalanceTaxCycleResult(0, 0, 0));
        verify(taxApi, never()).collectBatch(anyList());
    }

    @Test
    void zeroRate_producesNoCollections() {
        when(firmService.listAllActiveFirms()).thenReturn(List.of(firm(1)));
        when(firmAccountService.listAccountIds(1)).thenReturn(List.of(10));
        when(treasury.getBalancesByIds(List.of(10))).thenReturn(Map.of(10, new BigDecimal("100")));
        when(config.getWeeklyRate(new BigDecimal("100"))).thenReturn(BigDecimal.ZERO);

        BalanceTaxCycleResult result = svc.runWeeklyCycle(event);

        assertThat(result).isEqualTo(new BalanceTaxCycleResult(0, 0, 0));
        verify(taxApi, never()).collectBatch(anyList());
    }

    @Test
    void singleAccount_buildsOneCollection_andTalliesCollected() {
        when(firmService.listAllActiveFirms()).thenReturn(List.of(firm(1)));
        when(firmAccountService.listAccountIds(1)).thenReturn(List.of(10));
        when(treasury.getBalancesByIds(List.of(10))).thenReturn(Map.of(10, new BigDecimal("100.00")));
        when(config.getWeeklyRate(new BigDecimal("100.00"))).thenReturn(new BigDecimal("0.05"));
        when(taxApi.collectBatch(anyList())).thenReturn(List.of(
                new TaxResult.Collected(1L, new BigDecimal("5.00"), 500)));

        BalanceTaxCycleResult result = svc.runWeeklyCycle(event);

        assertThat(result).isEqualTo(new BalanceTaxCycleResult(1, 0, 0));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TaxCollection>> batch = ArgumentCaptor.forClass(List.class);
        verify(taxApi).collectBatch(batch.capture());
        assertThat(batch.getValue()).hasSize(1);
        // Destination is the resolved government account, source is the firm account.
        assertThat(batch.getValue().get(0).destinationAccountId()).isEqualTo(500);
        assertThat(batch.getValue().get(0).sourceAccountId()).isEqualTo(10);
        assertThat(batch.getValue().get(0).amount()).isEqualByComparingTo("5.00");
    }

    @Test
    void multipleAccounts_splitProportionally_withDriftFoldedIntoLargest() {
        when(firmService.listAllActiveFirms()).thenReturn(List.of(firm(1)));
        when(firmAccountService.listAccountIds(1)).thenReturn(List.of(10, 11, 12));
        // Balances (50/50/101, total 201) chosen so the proportional split leaves a
        // genuine +0.01 rounding remainder that must be folded into the LARGEST-balance
        // account. total 201 × 0.0333 = 6.6933 → 6.69 (totalTax).
        //   proportion 50/201  = 0.2487562189 → 6.69 × … = 1.66 each (two small accounts)
        //   proportion 101/201 = 0.5024875622 → 6.69 × … = 3.36 (the large account)
        //   allocated = 1.66 + 1.66 + 3.36 = 6.68, so drift = +0.01.
        // The drift must land on account 12 (balance 101), giving it 3.37.
        when(treasury.getBalancesByIds(List.of(10, 11, 12))).thenReturn(Map.of(
                10, new BigDecimal("50.00"), 11, new BigDecimal("50.00"), 12, new BigDecimal("101.00")));
        when(config.getWeeklyRate(new BigDecimal("201.00"))).thenReturn(new BigDecimal("0.0333"));
        when(taxApi.collectBatch(anyList())).thenReturn(List.of(
                new TaxResult.Collected(1L, new BigDecimal("1.66"), 500),
                new TaxResult.Collected(2L, new BigDecimal("1.66"), 500),
                new TaxResult.Collected(3L, new BigDecimal("3.37"), 500)));

        BalanceTaxCycleResult result = svc.runWeeklyCycle(event);

        assertThat(result).isEqualTo(new BalanceTaxCycleResult(3, 0, 0));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TaxCollection>> batch = ArgumentCaptor.forClass(List.class);
        verify(taxApi).collectBatch(batch.capture());
        List<TaxCollection> collections = batch.getValue();
        assertThat(collections).hasSize(3);

        // The per-account tax must sum to the firm total exactly (drift absorbed).
        BigDecimal sum = collections.stream()
                .map(TaxCollection::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo("6.69");

        // The +0.01 drift must be folded into the LARGEST-balance account (id 12):
        // its share is 3.36 + 0.01 = 3.37, while the two equal small accounts stay at 1.66.
        BigDecimal largestAccountTax = collections.stream()
                .filter(c -> c.sourceAccountId() == 12)
                .map(TaxCollection::amount)
                .findFirst().orElseThrow();
        assertThat(largestAccountTax).isEqualByComparingTo("3.37");
        assertThat(collections.stream()
                .filter(c -> c.sourceAccountId() != 12)
                .map(TaxCollection::amount))
                .allSatisfy(a -> assertThat(a).isEqualByComparingTo("1.66"));
    }

    @Test
    void unresolvedDestination_fallsBackToDefaultAccountCollections() {
        when(config.getGovernmentAccount()).thenReturn("Missing");
        when(treasury.getGovernmentAccountByName("Missing")).thenReturn(null);
        when(firmService.listAllActiveFirms()).thenReturn(List.of(firm(1)));
        when(firmAccountService.listAccountIds(1)).thenReturn(List.of(10));
        when(treasury.getBalancesByIds(List.of(10))).thenReturn(Map.of(10, new BigDecimal("100.00")));
        when(config.getWeeklyRate(new BigDecimal("100.00"))).thenReturn(new BigDecimal("0.05"));
        when(taxApi.collectBatch(anyList())).thenReturn(List.of(
                new TaxResult.Skipped("below minimum")));

        BalanceTaxCycleResult result = svc.runWeeklyCycle(event);

        assertThat(result).isEqualTo(new BalanceTaxCycleResult(0, 1, 0));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TaxCollection>> batch = ArgumentCaptor.forClass(List.class);
        verify(taxApi).collectBatch(batch.capture());
        // toDefaultAccount leaves the destination unset (null → Treasury's default tax account).
        assertThat(batch.getValue().get(0).destinationAccountId()).isNull();
    }

    @Test
    void perFirmException_isSkipped_withoutKillingTheCycle() {
        when(firmService.listAllActiveFirms()).thenReturn(List.of(firm(1), firm(2)));
        // Firm 1 blows up while reading balances; firm 2 succeeds.
        when(firmAccountService.listAccountIds(1)).thenThrow(new RuntimeException("treasury blip"));
        when(firmAccountService.listAccountIds(2)).thenReturn(List.of(20));
        when(treasury.getBalancesByIds(List.of(20))).thenReturn(Map.of(20, new BigDecimal("100.00")));
        when(config.getWeeklyRate(new BigDecimal("100.00"))).thenReturn(new BigDecimal("0.05"));
        when(taxApi.collectBatch(anyList())).thenReturn(List.of(
                new TaxResult.Collected(9L, new BigDecimal("5.00"), 500)));

        BalanceTaxCycleResult result = svc.runWeeklyCycle(event);

        assertThat(result).isEqualTo(new BalanceTaxCycleResult(1, 0, 0));
    }

    @Test
    void mixedResults_areTallied_andFailuresCounted() {
        when(firmService.listAllActiveFirms()).thenReturn(List.of(firm(1)));
        when(firmAccountService.listAccountIds(1)).thenReturn(List.of(10, 11, 12));
        when(treasury.getBalancesByIds(List.of(10, 11, 12))).thenReturn(Map.of(
                10, new BigDecimal("100.00"), 11, new BigDecimal("100.00"), 12, new BigDecimal("100.00")));
        when(config.getWeeklyRate(new BigDecimal("300.00"))).thenReturn(new BigDecimal("0.01"));
        when(taxApi.collectBatch(anyList())).thenReturn(List.of(
                new TaxResult.Collected(1L, new BigDecimal("1.00"), 500),
                new TaxResult.Skipped("dup"),
                new TaxResult.Failed("insufficient funds")));

        BalanceTaxCycleResult result = svc.runWeeklyCycle(event);

        assertThat(result).isEqualTo(new BalanceTaxCycleResult(1, 1, 1));
    }

    // ---------- estimateWeeklyTax (plugin-architecture/0006) ----------

    @Test
    void estimateWeeklyTax_returnsBalanceRateAndRoundedTax() {
        when(firmTransactionService.getAggregateBalance(1)).thenReturn(new BigDecimal("1000.00"));
        when(config.getWeeklyRate(new BigDecimal("1000.00"))).thenReturn(new BigDecimal("0.025"));

        WeeklyTaxEstimate estimate = svc.estimateWeeklyTax(1);

        assertThat(estimate.totalBalance()).isEqualByComparingTo("1000.00");
        assertThat(estimate.rate()).isEqualByComparingTo("0.025");
        // 1000.00 × 0.025 = 25.00, settled at 2dp like the live collection path.
        assertThat(estimate.estimatedTax()).isEqualByComparingTo("25.00");
    }

    @Test
    void estimateWeeklyTax_roundsHalfUpToCurrencyPrecision() {
        when(firmTransactionService.getAggregateBalance(1)).thenReturn(new BigDecimal("333.33"));
        when(config.getWeeklyRate(new BigDecimal("333.33"))).thenReturn(new BigDecimal("0.015"));

        WeeklyTaxEstimate estimate = svc.estimateWeeklyTax(1);

        // 333.33 × 0.015 = 4.99995 → 5.00 at HALF_UP 2dp.
        assertThat(estimate.estimatedTax()).isEqualByComparingTo("5.00");
    }
}
