package io.paradaux.treasury.api.impl;

import com.google.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import io.paradaux.treasury.api.TaxApi;
import io.paradaux.treasury.mappers.AccountMapper;
import io.paradaux.treasury.model.config.GovernmentConfiguration;
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
import io.paradaux.treasury.utils.AccountFactory;
import io.paradaux.treasury.utils.Money;
import io.paradaux.treasury.utils.TreasuryConstants;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * Production implementation of {@link TaxApi}.
 *
 * <p>All money movement delegates to {@link LedgerService#transfer} so every tax
 * collection appears in the double-entry audit trail automatically. Cycle-related
 * state (next-fire times, registered participants, the active accumulator session)
 * lives on {@link TaxCycleRegistry}; this class only handles collection.
 */
@Slf4j
public class TaxApiImpl implements TaxApi {

    private final LedgerService ledgerService;
    private final AccountMapper accountMapper;
    private final GovernmentConfiguration govConfig;
    private final TaxCycleConfiguration cycleConfig;
    private final SourceIncomeTaxConfiguration sourceIncomeTaxConfig;
    private final TaxCycleRegistry cycleRegistry;
    private final EconomyNotifier notifier;

    /** Lazily resolved and cached ID of the default tax-collection account. */
    private volatile Integer defaultTaxAccountId;

    /** Lazily resolved and cached ID of the source income tax destination account. */
    private volatile Integer sourceIncomeTaxAccountId;

    @Inject
    public TaxApiImpl(LedgerService ledgerService,
                      AccountMapper accountMapper,
                      GovernmentConfiguration govConfig,
                      TaxCycleConfiguration cycleConfig,
                      SourceIncomeTaxConfiguration sourceIncomeTaxConfig,
                      TaxCycleRegistry cycleRegistry,
                      EconomyNotifier notifier) {
        this.ledgerService = ledgerService;
        this.accountMapper = accountMapper;
        this.govConfig = govConfig;
        this.cycleConfig = cycleConfig;
        this.sourceIncomeTaxConfig = sourceIncomeTaxConfig;
        this.cycleRegistry = cycleRegistry;
        this.notifier = notifier;
    }

    // ---- Core collection ----

    @Override
    public TaxResult collectTax(TaxCollection collection) {
        BigDecimal amount = collection.amount();

        if (amount == null || amount.compareTo(Money.MINIMUM_AMOUNT) < 0) {
            cycleRegistry.recordSkipped();
            return new TaxResult.Skipped("Amount " + amount + " is below the $0.01 minimum");
        }

        int destinationId = resolveDestination(collection.destinationAccountId());

        TaxResult result;
        try {
            long txnId = ledgerService.transfer(new TransferRequest(
                    collection.sourceAccountId(),
                    destinationId,
                    amount,
                    collection.description(),
                    collection.initiator(),
                    null,
                    collection.pluginSystem(),
                    collection.dedupKey()
            ));
            result = new TaxResult.Collected(txnId, amount, destinationId);
            cycleRegistry.recordCollected(destinationId, collection.taxType(), amount);
            notifier.notifyTaxCollected(collection.sourceAccountId(), collection.taxType(), amount);
            log.debug("Tax collected: type={} amount={} src={} dest={} txn={}",
                    collection.taxType(), amount, collection.sourceAccountId(), destinationId, txnId);
        } catch (Exception e) {
            log.warn("Tax collection failed: type={} src={} amount={} reason={}",
                    collection.taxType(), collection.sourceAccountId(), amount, e.getMessage());
            cycleRegistry.recordFailed();
            result = new TaxResult.Failed(e.getMessage() != null ? e.getMessage() : "Transfer failed");
        }

        return result;
    }

    @Override
    public List<TaxResult> collectBatch(List<TaxCollection> collections) {
        List<TaxResult> results = new ArrayList<>(collections.size());
        for (TaxCollection collection : collections) {
            results.add(collectTax(collection));
        }
        return results;
    }

    // ---- Rate-based convenience ----

    @Override
    public TaxResult collectRateTax(
            int sourceAccountId,
            int destinationAccountId,
            BigDecimal transactionAmount,
            BigDecimal rate,
            String taxType,
            String description,
            UUID initiator,
            @Nullable String pluginSystem,
            byte @Nullable [] dedupKey) {
        BigDecimal tax = TaxApi.computeTax(transactionAmount, rate);
        if (tax.compareTo(Money.MINIMUM_AMOUNT) < 0) {
            return new TaxResult.Skipped("Computed tax " + tax + " is below the $0.01 minimum (rate="
                    + rate + ", base=" + transactionAmount + ")");
        }
        return collectTax(TaxCollection.toAccount(
                sourceAccountId, destinationAccountId, tax, taxType, description, initiator, pluginSystem, dedupKey));
    }

    @Override
    public TaxResult collectRateTax(
            int sourceAccountId,
            BigDecimal transactionAmount,
            BigDecimal rate,
            String taxType,
            String description,
            UUID initiator,
            @Nullable String pluginSystem,
            byte @Nullable [] dedupKey) {
        BigDecimal tax = TaxApi.computeTax(transactionAmount, rate);
        if (tax.compareTo(Money.MINIMUM_AMOUNT) < 0) {
            return new TaxResult.Skipped("Computed tax " + tax + " is below the $0.01 minimum (rate="
                    + rate + ", base=" + transactionAmount + ")");
        }
        return collectTax(TaxCollection.toDefaultAccount(
                sourceAccountId, tax, taxType, description, initiator, pluginSystem, dedupKey));
    }

    // ---- Default tax account ----

    @Override
    public int getDefaultTaxAccountId() {
        if (defaultTaxAccountId == null) {
            synchronized (this) {
                if (defaultTaxAccountId == null) {
                    defaultTaxAccountId = resolveOrCreateTaxAccount();
                }
            }
        }
        return defaultTaxAccountId;
    }

    @Override
    public String getDefaultTaxAccountName() {
        return govConfig.getTaxIncomeAccount();
    }

    // ---- Cycle schedule introspection (delegates to registry) ----

    @Override
    public boolean isCycleEnabled(TaxCycleType cycleType) {
        return switch (cycleType) {
            case DAILY   -> cycleConfig.isDailyEnabled();
            case WEEKLY  -> cycleConfig.isWeeklyEnabled();
            case MONTHLY -> cycleConfig.isMonthlyEnabled();
        };
    }

    @Override
    public Optional<Instant> getNextFireTime(TaxCycleType cycleType) {
        return cycleRegistry.getNextFireTime(cycleType);
    }

    @Override
    public void registerCycleParticipant(String pluginName, TaxCycleType... cycleTypes) {
        cycleRegistry.registerCycleParticipant(pluginName, cycleTypes);
    }

    @Override
    public Set<String> getCycleParticipants(TaxCycleType cycleType) {
        return cycleRegistry.getCycleParticipants(cycleType);
    }

    // ---- Source income tax ----

    @Override
    public TaxResult applySourceIncomeTax(@NotNull UUID playerUuid,
                                          @NotNull BigDecimal depositAmount,
                                          @NotNull String pluginName) {
        if (!sourceIncomeTaxConfig.isEnabled()) {
            return new TaxResult.Skipped("Source income tax is disabled");
        }

        BigDecimal rate = sourceIncomeTaxConfig.getEffectiveRate(pluginName);
        if (rate.compareTo(BigDecimal.ZERO) <= 0) {
            return new TaxResult.Skipped("Effective rate for plugin '" + pluginName + "' is 0");
        }

        Integer playerAccountId = accountMapper.findPersonalAccountId(playerUuid);
        if (playerAccountId == null) {
            return new TaxResult.Skipped("No personal account for player " + playerUuid);
        }

        BigDecimal taxAmount = TaxApi.computeTax(depositAmount, rate);
        if (taxAmount.compareTo(Money.MINIMUM_AMOUNT) < 0) {
            return new TaxResult.Skipped("Computed income tax " + taxAmount
                    + " is below the $0.01 minimum (rate=" + rate + ", deposit=" + depositAmount + ")");
        }

        int destinationId = resolveSourceIncomeTaxDestination();

        TaxCollection collection = TaxCollection.toAccount(
                playerAccountId,
                destinationId,
                taxAmount,
                "source-income-tax",
                "Income tax on deposit from " + pluginName + " ($" + depositAmount + ")",
                TreasuryConstants.VIRTUAL_TREASURY_INITIATOR,
                "treasury",
                null
        );

        return collectTax(collection);
    }

    // ---- Private helpers ----

    private int resolveDestination(@Nullable Integer explicit) {
        return explicit != null ? explicit : getDefaultTaxAccountId();
    }

    private int resolveSourceIncomeTaxDestination() {
        if (sourceIncomeTaxAccountId == null) {
            synchronized (this) {
                if (sourceIncomeTaxAccountId == null) {
                    String accountName = sourceIncomeTaxConfig.getGovernmentAccount();
                    Account account = accountMapper.findGovernmentAccountByName(accountName);
                    if (account != null) {
                        sourceIncomeTaxAccountId = account.getAccountId();
                    } else {
                        log.warn("Source income tax government account '{}' not found — routing to default tax account",
                                accountName);
                        sourceIncomeTaxAccountId = getDefaultTaxAccountId();
                    }
                }
            }
        }
        return sourceIncomeTaxAccountId;
    }

    private int resolveOrCreateTaxAccount() {
        String accountName = getDefaultTaxAccountName();
        Account existing = accountMapper.findGovernmentAccountByName(accountName);
        if (existing != null) return existing.getAccountId();

        // Primitive GOVERNMENT account: overdraft on, -1 = unlimited credit (faucet/sink).
        Account account = AccountFactory.primitiveGovernment(accountName);
        accountMapper.insertAccount(account);
        accountMapper.seedBalance(account.getAccountId());
        log.warn("Default tax-collection account '{}' was missing — created on demand. "
                + "bootstrapGovernmentAccounts() should have created it.", accountName);
        return account.getAccountId();
    }
}
