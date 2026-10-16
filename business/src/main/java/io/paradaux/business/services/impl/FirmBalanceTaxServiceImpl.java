package io.paradaux.business.services.impl;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.business.Business;
import io.paradaux.business.model.Firm;
import io.paradaux.business.model.config.BalanceTaxConfiguration;
import io.paradaux.business.services.FirmAccountService;
import io.paradaux.business.services.FirmBalanceTaxService;
import io.paradaux.business.services.FirmPropertyService;
import io.paradaux.business.services.FirmService;
import io.paradaux.business.services.FirmTransactionService;
import io.paradaux.treasury.api.TreasuryApi;
import io.paradaux.treasury.event.TaxCycleEvent;
import io.paradaux.treasury.model.economy.Account;
import io.paradaux.treasury.model.tax.TaxCollection;
import io.paradaux.treasury.model.tax.TaxResult;
import io.paradaux.treasury.utils.Idempotency;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

@Singleton
public class FirmBalanceTaxServiceImpl implements FirmBalanceTaxService {

    private static final UUID SYSTEM_UUID = new UUID(0L, 0L);
    private static final String PLUGIN_SYSTEM = "business";
    private static final String TAX_TYPE = "balance_tax";
    private static final String EXEMPT_KEY = "balance-tax.exempt";

    private final BalanceTaxConfiguration config;
    private final FirmService firmService;
    private final FirmPropertyService firmPropertyService;
    private final FirmAccountService firmAccountService;
    private final FirmTransactionService firmTransactionService;
    private final TreasuryApi treasuryApi;
    private final Logger logger;

    @Inject
    public FirmBalanceTaxServiceImpl(BalanceTaxConfiguration config,
                                     FirmService firmService,
                                     FirmPropertyService firmPropertyService,
                                     FirmAccountService firmAccountService,
                                     FirmTransactionService firmTransactionService,
                                     TreasuryApi treasuryApi,
                                     Business plugin) {
        this.config = config;
        this.firmService = firmService;
        this.firmPropertyService = firmPropertyService;
        this.firmAccountService = firmAccountService;
        this.firmTransactionService = firmTransactionService;
        this.treasuryApi = treasuryApi;
        this.logger = plugin.getLogger();
    }

    @Override
    public WeeklyTaxEstimate estimateWeeklyTax(Integer firmId) {
        BigDecimal totalBalance = firmTransactionService.getAggregateBalance(firmId);
        BigDecimal rate = config.getWeeklyRate(totalBalance);
        // Same 2dp HALF_UP rounding the live collection path settles at, so the
        // estimate matches what would actually be collected (plugin-architecture/0006).
        BigDecimal estimatedTax = totalBalance.multiply(rate).setScale(2, RoundingMode.HALF_UP);
        return new WeeklyTaxEstimate(totalBalance, rate, estimatedTax);
    }

    @Override
    public BalanceTaxCycleResult runWeeklyCycle(TaxCycleEvent event) {
        List<Firm> firms = firmService.listAllActiveFirms();
        Integer destinationAccountId = resolveDestinationAccountId(config.getGovernmentAccount());

        List<TaxCollection> allCollections = new ArrayList<>();

        for (Firm firm : firms) {
            try {
                allCollections.addAll(buildFirmCollections(firm, destinationAccountId, event));
            } catch (Exception e) {
                // One firm's failure (Treasury blip, missing account row, etc.)
                // shouldn't kill the entire weekly cycle.
                logger.warning("Skipping firm " + firm.getFirmId() + " (\"" + firm.getDisplayName()
                    + "\") in balance tax cycle: " + e.getMessage());
            }
        }

        if (allCollections.isEmpty()) {
            return new BalanceTaxCycleResult(0, 0, 0);
        }

        List<TaxResult> results = event.getTaxApi().collectBatch(allCollections);

        long collected = 0, skipped = 0, failed = 0;
        for (TaxResult result : results) {
            if (result.isSuccess()) {
                collected++;
            } else if (result.wasSkipped()) {
                skipped++;
            } else {
                failed++;
                if (result instanceof TaxResult.Failed f) {
                    logger.warning("Corporate balance tax collection failure: " + f.errorMessage());
                }
            }
        }
        return new BalanceTaxCycleResult(collected, skipped, failed);
    }

    /**
     * Splits {@code totalTax} across the accounts in proportion to their balances,
     * in whole cents, by the largest-remainder (Hamilton) method: every account gets
     * the floor of its exact quota {@code totalTax · balance / totalBalance}, and the
     * cents left over go one each to the accounts with the largest fractional
     * remainders (ties: larger balance first, then account order).
     *
     * <p>So Σ shares == totalTax exactly, every share is its quota rounded down or up
     * by less than a cent, and no share is negative. The previous scheme — round
     * each share HALF_UP, then fold the whole drift into the largest account — broke
     * all three: with ten equal 5.00 accounts at 0.1% (total 0.05) every share rounded
     * up to 0.01, the −0.05 drift drove the largest account to −0.04, that share was
     * skipped as non-positive, and the firm was charged 0.09 for a 0.05 tax. Found by
     * {@code FirmBalanceTaxAllocationProperties}.
     */
    static Map<Integer, BigDecimal> allocateLargestRemainder(BigDecimal totalTax,
                                                             Map<Integer, BigDecimal> balances,
                                                             BigDecimal totalBalance) {
        BigDecimal totalCents = totalTax.movePointRight(2);
        record Quota(Integer accountId, BigDecimal balance, BigDecimal floorCents, BigDecimal remainder, int order) {}

        List<Quota> quotas = new ArrayList<>();
        BigDecimal allocatedCents = BigDecimal.ZERO;
        int order = 0;
        for (Map.Entry<Integer, BigDecimal> entry : balances.entrySet()) {
            // Exact rational quota totalCents·b/B, kept as integer part + remainder over B
            // so remainders compare exactly (no intermediate rounding at all).
            BigDecimal numerator = totalCents.multiply(entry.getValue());
            BigDecimal floor = numerator.divideToIntegralValue(totalBalance);
            BigDecimal remainder = numerator.subtract(floor.multiply(totalBalance));
            quotas.add(new Quota(entry.getKey(), entry.getValue(), floor, remainder, order++));
            allocatedCents = allocatedCents.add(floor);
        }

        int leftover = totalCents.subtract(allocatedCents).intValueExact(); // 0 <= leftover < accounts
        List<Quota> byRemainder = new ArrayList<>(quotas);
        byRemainder.sort(java.util.Comparator.comparing(Quota::remainder).reversed()
                .thenComparing(Quota::balance, java.util.Comparator.reverseOrder())
                .thenComparingInt(Quota::order));
        java.util.Set<Integer> bumped = new java.util.HashSet<>();
        for (int i = 0; i < leftover; i++) bumped.add(byRemainder.get(i).accountId());

        Map<Integer, BigDecimal> shares = new LinkedHashMap<>();
        for (Quota q : quotas) {
            BigDecimal cents = bumped.contains(q.accountId()) ? q.floorCents().add(BigDecimal.ONE) : q.floorCents();
            shares.put(q.accountId(), cents.movePointLeft(2).setScale(2, RoundingMode.UNNECESSARY));
        }
        return shares;
    }

    /**
     * Builds the per-account tax collections for a single firm: the firm's tax is
     * computed once on its total positive balance (2dp HALF_UP, the same rounding
     * {@link #estimateWeeklyTax} shows) and apportioned with
     * {@link #allocateLargestRemainder}, so the per-account charges sum to it exactly.
     */
    private List<TaxCollection> buildFirmCollections(Firm firm, Integer destinationAccountId, TaxCycleEvent event) {
        if (firmPropertyService.getBoolean(firm.getFirmId(), EXEMPT_KEY).orElse(false)) return List.of();

        List<Integer> accountIds = firmAccountService.listAccountIds(firm.getFirmId());
        if (accountIds.isEmpty()) return List.of();

        // One batch balance read per firm instead of one IPC per account (ADT-36).
        Map<Integer, BigDecimal> balancesById = treasuryApi.getBalancesByIds(accountIds);
        Map<Integer, BigDecimal> accountBalances = new LinkedHashMap<>();
        BigDecimal totalBalance = BigDecimal.ZERO;
        for (Integer accountId : accountIds) {
            BigDecimal balance = balancesById.getOrDefault(accountId, BigDecimal.ZERO);
            if (balance.compareTo(BigDecimal.ZERO) > 0) {
                accountBalances.put(accountId, balance);
                totalBalance = totalBalance.add(balance);
            }
        }

        if (totalBalance.compareTo(BigDecimal.ZERO) <= 0) return List.of();

        BigDecimal rate = config.getWeeklyRate(totalBalance);
        if (rate.compareTo(BigDecimal.ZERO) == 0) return List.of();

        BigDecimal totalTax = totalBalance.multiply(rate).setScale(2, RoundingMode.HALF_UP);
        if (totalTax.compareTo(BigDecimal.ZERO) <= 0) return List.of();

        List<TaxCollection> collections = new ArrayList<>();
        Map<Integer, BigDecimal> perAccountTax = allocateLargestRemainder(totalTax, accountBalances, totalBalance);

        for (Map.Entry<Integer, BigDecimal> entry : perAccountTax.entrySet()) {
            BigDecimal accountTax = entry.getValue();
            if (accountTax.compareTo(BigDecimal.ZERO) <= 0) continue;

            byte[] dedupKey = Idempotency.sha256(
                "business:balance_tax:" + firm.getFirmId() + ":" + entry.getKey()
                    + ":" + event.getPeriodStart().toEpochMilli()
            );

            TaxCollection collection = destinationAccountId != null
                ? TaxCollection.toAccount(entry.getKey(), destinationAccountId, accountTax, TAX_TYPE,
                    "Weekly Corporate Balance Tax", SYSTEM_UUID, PLUGIN_SYSTEM, dedupKey)
                : TaxCollection.toDefaultAccount(entry.getKey(), accountTax, TAX_TYPE,
                    "Weekly Corporate Balance Tax", SYSTEM_UUID, PLUGIN_SYSTEM, dedupKey);

            collections.add(collection);
        }
        return collections;
    }

    private @Nullable Integer resolveDestinationAccountId(String accountName) {
        Account account = treasuryApi.getGovernmentAccountByName(accountName);
        if (account == null) {
            logger.warning("Configured corporate tax destination '" + accountName
                + "' not found in Treasury — falling back to Treasury's default tax account");
            return null;
        }
        return account.getAccountId();
    }
}
