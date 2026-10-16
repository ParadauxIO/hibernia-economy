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

    // ---------- buildFirmCollections allocation edges (mutation-hardening) ----------

    @SuppressWarnings("unchecked")
    private List<TaxCollection> capturedBatch() {
        ArgumentCaptor<List<TaxCollection>> batch = ArgumentCaptor.forClass(List.class);
        verify(taxApi).collectBatch(batch.capture());
        return batch.getValue();
    }

    private static Map<Integer, BigDecimal> amountsBySource(List<TaxCollection> collections) {
        Map<Integer, BigDecimal> out = new java.util.LinkedHashMap<>();
        for (TaxCollection c : collections) {
            out.put(c.sourceAccountId(), c.amount());
        }
        return out;
    }

    private static BigDecimal sum(List<TaxCollection> collections) {
        return collections.stream().map(TaxCollection::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void negativeAndZeroBalances_areExcludedFromTheTaxableTotal_andFromCollections() {
        // An overdrawn account must not reduce the firm's taxable total (and so its rate
        // bracket), and neither it nor a zero account may be charged.
        when(firmService.listAllActiveFirms()).thenReturn(List.of(firm(1)));
        when(firmAccountService.listAccountIds(1)).thenReturn(List.of(10, 11, 12));
        when(treasury.getBalancesByIds(List.of(10, 11, 12))).thenReturn(Map.of(
                10, new BigDecimal("-40.00"), 11, new BigDecimal("100.00"), 12, BigDecimal.ZERO));
        // Total is 100.00 (the positive balance only) — not 60.00.
        when(config.getWeeklyRate(new BigDecimal("100.00"))).thenReturn(new BigDecimal("0.05"));
        when(taxApi.collectBatch(anyList())).thenReturn(List.of(
                new TaxResult.Collected(1L, new BigDecimal("5.00"), 500)));

        BalanceTaxCycleResult result = svc.runWeeklyCycle(event);

        assertThat(result).isEqualTo(new BalanceTaxCycleResult(1, 0, 0));
        List<TaxCollection> collections = capturedBatch();
        assertThat(collections).hasSize(1);
        assertThat(collections.get(0).sourceAccountId()).isEqualTo(11);
        assertThat(collections.get(0).amount()).isEqualByComparingTo("5.00");
    }

    @Test
    void onlyNegativeBalances_neverLooksUpARate_andCollectsNothing() {
        when(firmService.listAllActiveFirms()).thenReturn(List.of(firm(1)));
        when(firmAccountService.listAccountIds(1)).thenReturn(List.of(10, 11));
        when(treasury.getBalancesByIds(List.of(10, 11)))
                .thenReturn(Map.of(10, new BigDecimal("-5.00"), 11, BigDecimal.ZERO));

        BalanceTaxCycleResult result = svc.runWeeklyCycle(event);

        assertThat(result).isEqualTo(new BalanceTaxCycleResult(0, 0, 0));
        // A non-positive total short-circuits before the rate bracket lookup.
        verify(config, never()).getWeeklyRate(org.mockito.ArgumentMatchers.any());
        verify(taxApi, never()).collectBatch(anyList());
    }

    @Test
    void firmWithNoAccounts_doesNotQueryTreasuryBalances() {
        when(firmService.listAllActiveFirms()).thenReturn(List.of(firm(1)));
        when(firmAccountService.listAccountIds(1)).thenReturn(List.of());

        assertThat(svc.runWeeklyCycle(event)).isEqualTo(new BalanceTaxCycleResult(0, 0, 0));
        verify(treasury, never()).getBalancesByIds(anyList());
        verify(config, never()).getWeeklyRate(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void largestBalanceTie_firstAccountAbsorbsPositiveDrift() {
        // Three equal 100.00 accounts, total 300.00 × 0.00033 = 0.099 → 0.10.
        // Each share is 0.10 × 0.3333333333 = 0.0333 → 0.03; allocated 0.09, drift +0.01.
        // On a balance tie the FIRST account in listing order is "largest" and takes it.
        when(firmService.listAllActiveFirms()).thenReturn(List.of(firm(1)));
        when(firmAccountService.listAccountIds(1)).thenReturn(List.of(10, 11, 12));
        when(treasury.getBalancesByIds(List.of(10, 11, 12))).thenReturn(Map.of(
                10, new BigDecimal("100.00"), 11, new BigDecimal("100.00"), 12, new BigDecimal("100.00")));
        when(config.getWeeklyRate(new BigDecimal("300.00"))).thenReturn(new BigDecimal("0.00033"));
        when(taxApi.collectBatch(anyList())).thenReturn(List.of(
                new TaxResult.Collected(1L, new BigDecimal("0.04"), 500),
                new TaxResult.Collected(2L, new BigDecimal("0.03"), 500),
                new TaxResult.Collected(3L, new BigDecimal("0.03"), 500)));

        svc.runWeeklyCycle(event);

        List<TaxCollection> collections = capturedBatch();
        Map<Integer, BigDecimal> amounts = amountsBySource(collections);
        assertThat(amounts).containsOnlyKeys(10, 11, 12);
        assertThat(amounts.get(10)).isEqualByComparingTo("0.04");
        assertThat(amounts.get(11)).isEqualByComparingTo("0.03");
        assertThat(amounts.get(12)).isEqualByComparingTo("0.03");
        assertThat(sum(collections)).isEqualByComparingTo("0.10");
    }

    @Test
    void negativeDrift_reducesLargestToZero_whichIsDroppedRatherThanAbortingTheFirm() {
        // Three equal 1.00 accounts, total 3.00 × 0.0067 = 0.0201 → 0.02.
        // Each share rounds UP: 0.02 × 0.3333333333 = 0.00667 → 0.01; allocated 0.03,
        // drift −0.01 folded into the first (tie-winning) account → 0.00. A 0.00
        // TaxCollection would throw, so that account must simply be skipped; the
        // other two are still charged and the firm total stays exact.
        when(firmService.listAllActiveFirms()).thenReturn(List.of(firm(1)));
        when(firmAccountService.listAccountIds(1)).thenReturn(List.of(10, 11, 12));
        when(treasury.getBalancesByIds(List.of(10, 11, 12))).thenReturn(Map.of(
                10, new BigDecimal("1.00"), 11, new BigDecimal("1.00"), 12, new BigDecimal("1.00")));
        when(config.getWeeklyRate(new BigDecimal("3.00"))).thenReturn(new BigDecimal("0.0067"));
        when(taxApi.collectBatch(anyList())).thenReturn(List.of(
                new TaxResult.Collected(1L, new BigDecimal("0.01"), 500),
                new TaxResult.Collected(2L, new BigDecimal("0.01"), 500)));

        BalanceTaxCycleResult result = svc.runWeeklyCycle(event);

        assertThat(result).isEqualTo(new BalanceTaxCycleResult(2, 0, 0));
        List<TaxCollection> collections = capturedBatch();
        Map<Integer, BigDecimal> amounts = amountsBySource(collections);
        assertThat(amounts).containsOnlyKeys(11, 12);
        assertThat(amounts.get(11)).isEqualByComparingTo("0.01");
        assertThat(amounts.get(12)).isEqualByComparingTo("0.01");
        assertThat(sum(collections)).isEqualByComparingTo("0.02");
    }

    @Test
    void thirds_driftLandsOnTheStrictlyLargestAccount_andSumIsExact() {
        // 33.33 / 33.33 / 33.34 (total 100.00) × 0.0005 = 0.05.
        // Shares: 0.05 × 0.3333 = 0.016665 → 0.02, 0.02, 0.05 × 0.3334 = 0.01667 → 0.02.
        // allocated 0.06, drift −0.01 → the strictly largest (33.34, id 12) → 0.01.
        when(firmService.listAllActiveFirms()).thenReturn(List.of(firm(1)));
        when(firmAccountService.listAccountIds(1)).thenReturn(List.of(10, 11, 12));
        when(treasury.getBalancesByIds(List.of(10, 11, 12))).thenReturn(Map.of(
                10, new BigDecimal("33.33"), 11, new BigDecimal("33.33"), 12, new BigDecimal("33.34")));
        when(config.getWeeklyRate(new BigDecimal("100.00"))).thenReturn(new BigDecimal("0.0005"));
        when(taxApi.collectBatch(anyList())).thenReturn(List.of(
                new TaxResult.Collected(1L, new BigDecimal("0.02"), 500),
                new TaxResult.Collected(2L, new BigDecimal("0.02"), 500),
                new TaxResult.Collected(3L, new BigDecimal("0.01"), 500)));

        svc.runWeeklyCycle(event);

        List<TaxCollection> collections = capturedBatch();
        Map<Integer, BigDecimal> amounts = amountsBySource(collections);
        assertThat(amounts.get(10)).isEqualByComparingTo("0.02");
        assertThat(amounts.get(11)).isEqualByComparingTo("0.02");
        assertThat(amounts.get(12)).isEqualByComparingTo("0.01");
        assertThat(sum(collections)).isEqualByComparingTo("0.05");
    }

    @Test
    void manySmallEqualAccounts_negativeDriftLargerThanTheLargestShare_overchargesTheFirm() {
        // Ten equal 1.00 accounts, total 10.00 × 0.005 = 0.05 total tax.
        // Each share is 0.05 × 0.1 = 0.005 → 0.01 (HALF_UP), so allocated = 0.10 and
        // drift = −0.05. Folding −0.05 into the first (tie-winning) account gives −0.04,
        // which the `<= 0` guard drops — but the other nine still pay 0.01 each.
        // BUG?: the firm is charged 0.09 against a computed tax of 0.05; the "per-account
        // sum matches totalTax exactly" invariant breaks whenever |drift| exceeds the
        // largest share (only reachable with many accounts and a few-cent tax). Pinned
        // as current behaviour; the fix would spread negative drift across accounts.
        List<Integer> ids = List.of(10, 11, 12, 13, 14, 15, 16, 17, 18, 19);
        Map<Integer, BigDecimal> balances = new java.util.LinkedHashMap<>();
        ids.forEach(id -> balances.put(id, new BigDecimal("1.00")));
        when(firmService.listAllActiveFirms()).thenReturn(List.of(firm(1)));
        when(firmAccountService.listAccountIds(1)).thenReturn(ids);
        when(treasury.getBalancesByIds(ids)).thenReturn(balances);
        when(config.getWeeklyRate(new BigDecimal("10.00"))).thenReturn(new BigDecimal("0.005"));
        when(taxApi.collectBatch(anyList())).thenReturn(java.util.Collections.nCopies(9,
                new TaxResult.Collected(1L, new BigDecimal("0.01"), 500)));

        BalanceTaxCycleResult result = svc.runWeeklyCycle(event);

        assertThat(result).isEqualTo(new BalanceTaxCycleResult(9, 0, 0));
        List<TaxCollection> collections = capturedBatch();
        assertThat(collections).extracting(TaxCollection::sourceAccountId)
                .containsExactly(11, 12, 13, 14, 15, 16, 17, 18, 19);
        assertThat(collections).allSatisfy(c -> assertThat(c.amount()).isEqualByComparingTo("0.01"));
        assertThat(sum(collections)).isEqualByComparingTo("0.09"); // vs. computed total tax 0.05
    }

    @Test
    void largestAccountInTheMiddle_absorbsDrift_notTheLastOne() {
        // 50 / 101 / 50 (total 201) × 0.0333 = 6.69; shares 1.66 / 3.36 / 1.66 → drift +0.01
        // goes to the middle account (101), proving "largest" is chosen by balance,
        // not by position.
        when(firmService.listAllActiveFirms()).thenReturn(List.of(firm(1)));
        when(firmAccountService.listAccountIds(1)).thenReturn(List.of(10, 11, 12));
        when(treasury.getBalancesByIds(List.of(10, 11, 12))).thenReturn(Map.of(
                10, new BigDecimal("50.00"), 11, new BigDecimal("101.00"), 12, new BigDecimal("50.00")));
        when(config.getWeeklyRate(new BigDecimal("201.00"))).thenReturn(new BigDecimal("0.0333"));
        when(taxApi.collectBatch(anyList())).thenReturn(List.of(
                new TaxResult.Collected(1L, new BigDecimal("1.66"), 500),
                new TaxResult.Collected(2L, new BigDecimal("3.37"), 500),
                new TaxResult.Collected(3L, new BigDecimal("1.66"), 500)));

        svc.runWeeklyCycle(event);

        Map<Integer, BigDecimal> amounts = amountsBySource(capturedBatch());
        assertThat(amounts.get(10)).isEqualByComparingTo("1.66");
        assertThat(amounts.get(11)).isEqualByComparingTo("3.37");
        assertThat(amounts.get(12)).isEqualByComparingTo("1.66");
    }

    @Test
    void tinyAccountWhoseShareRoundsToZero_isNotCharged_andDoesNotAbortTheFirm() {
        // 1000.00 + 0.01, total 1000.01 × 0.01 = 10.0001 → 10.00.
        // The 0.01 account's share is 0.0000999… → 0.00: it must produce NO collection
        // (a 0.00 TaxCollection throws and would drop the whole firm from the cycle).
        when(firmService.listAllActiveFirms()).thenReturn(List.of(firm(1)));
        when(firmAccountService.listAccountIds(1)).thenReturn(List.of(10, 11));
        when(treasury.getBalancesByIds(List.of(10, 11))).thenReturn(Map.of(
                10, new BigDecimal("1000.00"), 11, new BigDecimal("0.01")));
        when(config.getWeeklyRate(new BigDecimal("1000.01"))).thenReturn(new BigDecimal("0.01"));
        when(taxApi.collectBatch(anyList())).thenReturn(List.of(
                new TaxResult.Collected(1L, new BigDecimal("10.00"), 500)));

        BalanceTaxCycleResult result = svc.runWeeklyCycle(event);

        assertThat(result).isEqualTo(new BalanceTaxCycleResult(1, 0, 0));
        List<TaxCollection> collections = capturedBatch();
        assertThat(collections).hasSize(1);
        assertThat(collections.get(0).sourceAccountId()).isEqualTo(10);
        assertThat(collections.get(0).amount()).isEqualByComparingTo("10.00");
    }

    @Test
    void totalTaxRoundingToZero_collectsNothing() {
        // 0.01 × 0.04 = 0.0004 → 0.00 at 2dp: nothing to collect, no batch call.
        when(firmService.listAllActiveFirms()).thenReturn(List.of(firm(1)));
        when(firmAccountService.listAccountIds(1)).thenReturn(List.of(10));
        when(treasury.getBalancesByIds(List.of(10))).thenReturn(Map.of(10, new BigDecimal("0.01")));
        when(config.getWeeklyRate(new BigDecimal("0.01"))).thenReturn(new BigDecimal("0.04"));

        assertThat(svc.runWeeklyCycle(event)).isEqualTo(new BalanceTaxCycleResult(0, 0, 0));
        verify(taxApi, never()).collectBatch(anyList());
    }

    @Test
    void collections_carryTaxMetadata_andAPerFirmAccountPeriodDedupKey() {
        Instant periodStart = Instant.parse("2025-01-01T00:00:00Z");
        when(firmService.listAllActiveFirms()).thenReturn(List.of(firm(1), firm(2)));
        when(firmAccountService.listAccountIds(1)).thenReturn(List.of(10, 11));
        when(firmAccountService.listAccountIds(2)).thenReturn(List.of(10));
        when(treasury.getBalancesByIds(List.of(10, 11))).thenReturn(Map.of(
                10, new BigDecimal("100.00"), 11, new BigDecimal("100.00")));
        when(treasury.getBalancesByIds(List.of(10))).thenReturn(Map.of(10, new BigDecimal("100.00")));
        when(config.getWeeklyRate(new BigDecimal("200.00"))).thenReturn(new BigDecimal("0.05"));
        when(config.getWeeklyRate(new BigDecimal("100.00"))).thenReturn(new BigDecimal("0.05"));
        when(taxApi.collectBatch(anyList())).thenReturn(List.of(
                new TaxResult.Collected(1L, new BigDecimal("5.00"), 500),
                new TaxResult.Collected(2L, new BigDecimal("5.00"), 500),
                new TaxResult.Collected(3L, new BigDecimal("5.00"), 500)));

        BalanceTaxCycleResult result = svc.runWeeklyCycle(event);

        assertThat(result).isEqualTo(new BalanceTaxCycleResult(3, 0, 0));
        List<TaxCollection> collections = capturedBatch();
        assertThat(collections).hasSize(3);
        for (TaxCollection c : collections) {
            assertThat(c.amount()).isEqualByComparingTo("5.00");
            assertThat(c.destinationAccountId()).isEqualTo(500);
            assertThat(c.taxType()).isEqualTo("balance_tax");
            assertThat(c.description()).isEqualTo("Weekly Corporate Balance Tax");
            assertThat(c.initiator()).isEqualTo(new java.util.UUID(0L, 0L));
            assertThat(c.pluginSystem()).isEqualTo("business");
        }
        long epoch = periodStart.toEpochMilli();
        // Key binds firm id, account id and cycle period start, so the same account id
        // under two firms (or two different weeks) never dedups against each other.
        assertThat(collections.get(0).dedupKey()).isEqualTo(
                io.paradaux.treasury.utils.Idempotency.sha256("business:balance_tax:1:10:" + epoch));
        assertThat(collections.get(1).dedupKey()).isEqualTo(
                io.paradaux.treasury.utils.Idempotency.sha256("business:balance_tax:1:11:" + epoch));
        assertThat(collections.get(2).dedupKey()).isEqualTo(
                io.paradaux.treasury.utils.Idempotency.sha256("business:balance_tax:2:10:" + epoch));
        assertThat(collections.get(0).dedupKey()).isNotEqualTo(collections.get(2).dedupKey());
    }

    @Test
    void dedupKey_changesWithThePeriodStart() {
        when(event.getPeriodStart()).thenReturn(Instant.parse("2025-01-08T00:00:00Z"));
        when(firmService.listAllActiveFirms()).thenReturn(List.of(firm(1)));
        when(firmAccountService.listAccountIds(1)).thenReturn(List.of(10));
        when(treasury.getBalancesByIds(List.of(10))).thenReturn(Map.of(10, new BigDecimal("100.00")));
        when(config.getWeeklyRate(new BigDecimal("100.00"))).thenReturn(new BigDecimal("0.05"));
        when(taxApi.collectBatch(anyList())).thenReturn(List.of(
                new TaxResult.Collected(1L, new BigDecimal("5.00"), 500)));

        svc.runWeeklyCycle(event);

        byte[] key = capturedBatch().get(0).dedupKey();
        assertThat(key).isEqualTo(io.paradaux.treasury.utils.Idempotency.sha256(
                "business:balance_tax:1:10:" + Instant.parse("2025-01-08T00:00:00Z").toEpochMilli()));
        assertThat(key).isNotEqualTo(io.paradaux.treasury.utils.Idempotency.sha256(
                "business:balance_tax:1:10:" + Instant.parse("2025-01-01T00:00:00Z").toEpochMilli()));
    }

    @Test
    void exemptFirm_isSkipped_butOtherFirmsAreStillTaxed() {
        when(firmService.listAllActiveFirms()).thenReturn(List.of(firm(1), firm(2)));
        when(firmPropertyService.getBoolean(1, "balance-tax.exempt")).thenReturn(Optional.of(true));
        // Firm 2 has no exemption property at all → defaults to taxable.
        when(firmPropertyService.getBoolean(2, "balance-tax.exempt")).thenReturn(Optional.empty());
        when(firmAccountService.listAccountIds(2)).thenReturn(List.of(20));
        when(treasury.getBalancesByIds(List.of(20))).thenReturn(Map.of(20, new BigDecimal("100.00")));
        when(config.getWeeklyRate(new BigDecimal("100.00"))).thenReturn(new BigDecimal("0.05"));
        when(taxApi.collectBatch(anyList())).thenReturn(List.of(
                new TaxResult.Collected(1L, new BigDecimal("5.00"), 500)));

        assertThat(svc.runWeeklyCycle(event)).isEqualTo(new BalanceTaxCycleResult(1, 0, 0));
        List<TaxCollection> collections = capturedBatch();
        assertThat(collections).extracting(TaxCollection::sourceAccountId).containsExactly(20);
        verify(firmAccountService, never()).listAccountIds(1);
    }

    @Test
    void failedResults_areCountedAndLoggedAsWarnings() {
        java.util.List<java.util.logging.LogRecord> records = new java.util.ArrayList<>();
        Logger captured = Logger.getAnonymousLogger();
        captured.setUseParentHandlers(false);
        captured.addHandler(new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord r) { records.add(r); }
            @Override public void flush() { }
            @Override public void close() { }
        });
        when(plugin.getLogger()).thenReturn(captured);
        FirmBalanceTaxServiceImpl logging = new FirmBalanceTaxServiceImpl(config, firmService,
                firmPropertyService, firmAccountService, firmTransactionService, treasury, plugin);

        when(firmService.listAllActiveFirms()).thenReturn(List.of(firm(1)));
        when(firmAccountService.listAccountIds(1)).thenReturn(List.of(10, 11));
        when(treasury.getBalancesByIds(List.of(10, 11))).thenReturn(Map.of(
                10, new BigDecimal("100.00"), 11, new BigDecimal("100.00")));
        when(config.getWeeklyRate(new BigDecimal("200.00"))).thenReturn(new BigDecimal("0.05"));
        when(taxApi.collectBatch(anyList())).thenReturn(List.of(
                new TaxResult.Failed("insufficient funds on #10"),
                new TaxResult.Failed("account #11 frozen")));

        BalanceTaxCycleResult result = logging.runWeeklyCycle(event);

        assertThat(result).isEqualTo(new BalanceTaxCycleResult(0, 0, 2));
        assertThat(records)
                .filteredOn(r -> r.getLevel() == java.util.logging.Level.WARNING)
                .extracting(java.util.logging.LogRecord::getMessage)
                .containsExactly(
                        "Corporate balance tax collection failure: insufficient funds on #10",
                        "Corporate balance tax collection failure: account #11 frozen");
    }
}
