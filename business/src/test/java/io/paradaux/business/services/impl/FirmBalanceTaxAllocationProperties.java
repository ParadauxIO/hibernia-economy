package io.paradaux.business.services.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.paradaux.business.Business;
import io.paradaux.business.model.Firm;
import io.paradaux.business.model.config.BalanceTaxConfiguration;
import io.paradaux.business.services.FirmAccountService;
import io.paradaux.business.services.FirmPropertyService;
import io.paradaux.business.services.FirmService;
import io.paradaux.business.services.FirmTransactionService;
import io.paradaux.hibernia.testsupport.properties.FixedSeedInCi;
import io.paradaux.treasury.api.TaxApi;
import io.paradaux.treasury.api.TreasuryApi;
import io.paradaux.treasury.event.TaxCycleEvent;
import io.paradaux.treasury.model.tax.TaxCollection;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AddLifecycleHook;

/**
 * Conservation properties of the corporate balance-tax split
 * ({@link FirmBalanceTaxServiceImpl#runWeeklyCycle}): a firm's tax is computed once
 * on its total positive balance and then apportioned across its accounts, so
 *
 * <ul>
 *   <li>Σ per-account charges == the firm's total tax, to the cent — no cent created
 *       or lost by rounding the proportional shares;</li>
 *   <li>no account is charged more than its exact proportional quota rounded up to
 *       the next cent, nor more than it holds, and none is charged a negative amount;</li>
 *   <li>non-positive accounts are never charged;</li>
 *   <li>the {@code /firm} estimate equals what the cycle collects when every account
 *       is in credit (both use the same 2dp HALF_UP rounding on the total).</li>
 * </ul>
 */
@AddLifecycleHook(FixedSeedInCi.class)
class FirmBalanceTaxAllocationProperties {

    private static final int FIRM_ID = 1;

    @Property(tries = 500)
    void sharesSumExactlyToTheFirmTotal_andNeverExceedTheProportionalQuota(
            @ForAll("balances") List<BigDecimal> balances, @ForAll("rates") BigDecimal rate) {
        Map<Integer, BigDecimal> byAccount = byAccountId(balances);
        List<TaxCollection> charged = runCycle(byAccount, rate);

        BigDecimal positiveTotal =
                byAccount.values().stream().filter(b -> b.signum() > 0).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalTax = positiveTotal.multiply(rate).setScale(2, RoundingMode.HALF_UP);

        BigDecimal sum = charged.stream().map(TaxCollection::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum)
                .as("Σ shares vs firm total tax %s on %s", totalTax, byAccount)
                .isEqualByComparingTo(totalTax.signum() > 0 ? totalTax : BigDecimal.ZERO);

        for (TaxCollection c : charged) {
            BigDecimal balance = byAccount.get(c.sourceAccountId());
            assertThat(balance.signum()).as("charged a non-positive account").isPositive();
            assertThat(c.amount().scale()).isLessThanOrEqualTo(2);
            assertThat(c.amount()).isLessThanOrEqualTo(balance);
            // Exact quota = totalTax · b / B; a fair split rounds it at most one cent up.
            BigDecimal quota = totalTax.multiply(balance).divide(positiveTotal, 12, RoundingMode.HALF_UP);
            assertThat(c.amount().subtract(quota))
                    .as("account %d charged %s against quota %s", c.sourceAccountId(), c.amount(), quota)
                    .isLessThan(new BigDecimal("0.01"));
            assertThat(quota.subtract(c.amount())).isLessThan(new BigDecimal("0.01"));
        }
    }

    @Property(tries = 200)
    void estimateMatchesCollection_whenEveryAccountIsInCredit(
            @ForAll("positiveBalances") List<BigDecimal> balances, @ForAll("rates") BigDecimal rate) {
        Map<Integer, BigDecimal> byAccount = byAccountId(balances);
        BigDecimal total = byAccount.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);

        Mocks m = mocks(byAccount, rate);
        when(m.transactions.getAggregateBalance(FIRM_ID)).thenReturn(total);
        BigDecimal estimate = m.service().estimateWeeklyTax(FIRM_ID).estimatedTax();

        BigDecimal collected =
                runCycle(byAccount, rate).stream().map(TaxCollection::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(collected).isEqualByComparingTo(estimate.signum() > 0 ? estimate : BigDecimal.ZERO);
    }

    /**
     * The shrunk counterexample the first property found against the original
     * "round every share HALF_UP, then fold the drift into the largest account"
     * allocation: ten equal accounts, a 0.05 total. Each 0.005 share rounded up to
     * 0.01 (Σ 0.10); the −0.05 drift drove the largest account's share to −0.04, which
     * was then skipped as non-positive — so the firm was charged 0.09 for a 0.05 tax.
     */
    @Example
    void tenEqualAccounts_chargeExactlyTheTotal() {
        List<BigDecimal> balances = new ArrayList<>();
        for (int i = 0; i < 10; i++) balances.add(new BigDecimal("5.00"));
        List<TaxCollection> charged = runCycle(byAccountId(balances), new BigDecimal("0.001"));

        assertThat(charged.stream().map(TaxCollection::amount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("0.05");
        assertThat(charged).hasSize(5).allSatisfy(c -> assertThat(c.amount()).isEqualByComparingTo("0.01"));
    }

    // ---- harness ----

    private static Map<Integer, BigDecimal> byAccountId(List<BigDecimal> balances) {
        Map<Integer, BigDecimal> byAccount = new LinkedHashMap<>();
        for (int i = 0; i < balances.size(); i++) byAccount.put(100 + i, balances.get(i));
        return byAccount;
    }

    private static List<TaxCollection> runCycle(Map<Integer, BigDecimal> byAccount, BigDecimal rate) {
        Mocks m = mocks(byAccount, rate);
        List<TaxCollection> captured = new ArrayList<>();
        when(m.taxApi.collectBatch(anyList())).thenAnswer(inv -> {
            captured.addAll(inv.getArgument(0));
            return List.of();
        });
        m.service().runWeeklyCycle(m.event);
        return captured;
    }

    private static Mocks mocks(Map<Integer, BigDecimal> byAccount, BigDecimal rate) {
        Mocks m = new Mocks();
        Firm firm = new Firm();
        firm.setFirmId(FIRM_ID);
        firm.setDisplayName("Prop Co");
        when(m.firms.listAllActiveFirms()).thenReturn(List.of(firm));
        when(m.properties.getBoolean(anyInt(), anyString())).thenReturn(Optional.of(false));
        when(m.accounts.listAccountIds(FIRM_ID)).thenReturn(new ArrayList<>(byAccount.keySet()));
        when(m.treasury.getBalancesByIds(any())).thenReturn(byAccount);
        when(m.config.getWeeklyRate(any())).thenReturn(rate);
        when(m.config.getGovernmentAccount()).thenReturn("Gov");
        when(m.event.getTaxApi()).thenReturn(m.taxApi);
        when(m.event.getPeriodStart()).thenReturn(Instant.parse("2026-01-05T00:00:00Z"));
        Logger quiet = Logger.getLogger("firm-balance-tax-properties");
        quiet.setLevel(Level.OFF);
        when(m.plugin.getLogger()).thenReturn(quiet);
        return m;
    }

    private static final class Mocks {
        final BalanceTaxConfiguration config = mock(BalanceTaxConfiguration.class);
        final FirmService firms = mock(FirmService.class);
        final FirmPropertyService properties = mock(FirmPropertyService.class);
        final FirmAccountService accounts = mock(FirmAccountService.class);
        final FirmTransactionService transactions = mock(FirmTransactionService.class);
        final TreasuryApi treasury = mock(TreasuryApi.class);
        final Business plugin = mock(Business.class);
        final TaxCycleEvent event = mock(TaxCycleEvent.class);
        final TaxApi taxApi = mock(TaxApi.class);

        FirmBalanceTaxServiceImpl service() {
            return new FirmBalanceTaxServiceImpl(config, firms, properties, accounts, transactions, treasury, plugin);
        }
    }

    // ---- generators ----

    /** 1–15 accounts; mostly in credit, with zero and overdrawn accounts mixed in. */
    @Provide
    Arbitrary<List<BigDecimal>> balances() {
        Arbitrary<BigDecimal> balance = Arbitraries.frequencyOf(
                net.jqwik.api.Tuple.of(6, cents(1, 100_000_00L)), // up to 100k
                net.jqwik.api.Tuple.of(2, cents(1, 100L)), // tiny: 0.01 – 1.00
                net.jqwik.api.Tuple.of(1, cents(1, 10_000_000_000_00L)), // up to 10bn
                net.jqwik.api.Tuple.of(1, Arbitraries.of(BigDecimal.ZERO.setScale(2), new BigDecimal("-50.00"))));
        return balance.list().ofMinSize(1).ofMaxSize(15);
    }

    @Provide
    Arbitrary<List<BigDecimal>> positiveBalances() {
        return cents(1, 1_000_000_00L).list().ofMinSize(1).ofMaxSize(15);
    }

    /** Weekly rates from 0.01% to 10% at up to 4 decimal places, plus the 0.001 edge. */
    @Provide
    Arbitrary<BigDecimal> rates() {
        return Arbitraries.frequencyOf(
                net.jqwik.api.Tuple.of(
                        9, Arbitraries.integers().between(1, 1000).map(bp -> BigDecimal.valueOf(bp, 4))),
                net.jqwik.api.Tuple.of(1, Arbitraries.of(new BigDecimal("0.001"), new BigDecimal("0.0125"))));
    }

    private static Arbitrary<BigDecimal> cents(long min, long max) {
        return Arbitraries.longs().between(min, max).map(c -> BigDecimal.valueOf(c, 2));
    }
}
