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
     * Builds the per-account tax collections for a single firm. Tax amounts
     * are settled at the currency precision (2dp) and the rounding remainder
     * is folded into the largest-balance account so the per-account sum
     * matches the total tax exactly.
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
        BigDecimal allocated = BigDecimal.ZERO;
        Integer largestAccountId = null;
        BigDecimal largestBalance = BigDecimal.ZERO;
        Map<Integer, BigDecimal> perAccountTax = new LinkedHashMap<>();

        for (Map.Entry<Integer, BigDecimal> entry : accountBalances.entrySet()) {
            BigDecimal proportion = entry.getValue().divide(totalBalance, 10, RoundingMode.HALF_UP);
            BigDecimal accountTax = totalTax.multiply(proportion).setScale(2, RoundingMode.HALF_UP);
            perAccountTax.put(entry.getKey(), accountTax);
            allocated = allocated.add(accountTax);
            if (entry.getValue().compareTo(largestBalance) > 0) {
                largestBalance = entry.getValue();
                largestAccountId = entry.getKey();
            }
        }

        // Adjust the largest-balance account so the sum matches totalTax exactly.
        BigDecimal drift = totalTax.subtract(allocated);
        if (drift.signum() != 0 && largestAccountId != null) {
            perAccountTax.merge(largestAccountId, drift, BigDecimal::add);
        }

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
