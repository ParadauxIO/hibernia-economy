package io.paradaux.treasury.services.impl;

import com.google.inject.Inject;
import com.google.inject.Provider;
import io.paradaux.treasury.mappers.AccountMapper;
import io.paradaux.treasury.mappers.IncomeTaxMapper;
import io.paradaux.treasury.model.config.ProgressiveIncomeTaxConfiguration;
import io.paradaux.treasury.model.economy.Account;
import io.paradaux.treasury.model.economy.AccountType;
import io.paradaux.treasury.model.economy.TransferRequest;
import io.paradaux.treasury.services.EconomyNotifier;
import io.paradaux.treasury.services.IncomeTaxService;
import io.paradaux.treasury.services.LedgerService;
import io.paradaux.treasury.services.TaxCycleRegistry;
import io.paradaux.treasury.utils.AccountFactory;
import io.paradaux.treasury.utils.Idempotency;
import io.paradaux.treasury.utils.Money;
import io.paradaux.treasury.utils.TreasuryConstants;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.Nullable;

@Slf4j
public class IncomeTaxServiceImpl implements IncomeTaxService {

    /** Tax type on the ledger, in tax-cycle statistics and in the player's "tax collected" message. */
    public static final String TAX_TYPE = "progressive-income-tax";

    private final ProgressiveIncomeTaxConfiguration config;
    private final IncomeTaxMapper incomeTaxMapper;
    private final AccountMapper accountMapper;
    /** A provider because the ledger calls this service: a direct dependency would be circular. */
    private final Provider<LedgerService> ledgerService;

    private final TaxCycleRegistry cycleRegistry;
    private final EconomyNotifier notifier;
    private final Clock clock;

    /** Cached destination; re-resolved when the configured account name changes. */
    private volatile @Nullable Destination destination;

    private record Destination(String name, int id) {}

    @Inject
    public IncomeTaxServiceImpl(
            ProgressiveIncomeTaxConfiguration config,
            IncomeTaxMapper incomeTaxMapper,
            AccountMapper accountMapper,
            Provider<LedgerService> ledgerService,
            TaxCycleRegistry cycleRegistry,
            EconomyNotifier notifier) {
        this(config, incomeTaxMapper, accountMapper, ledgerService, cycleRegistry, notifier, Clock.systemUTC());
    }

    IncomeTaxServiceImpl(
            ProgressiveIncomeTaxConfiguration config,
            IncomeTaxMapper incomeTaxMapper,
            AccountMapper accountMapper,
            Provider<LedgerService> ledgerService,
            TaxCycleRegistry cycleRegistry,
            EconomyNotifier notifier,
            Clock clock) {
        this.config = config;
        this.incomeTaxMapper = incomeTaxMapper;
        this.accountMapper = accountMapper;
        this.ledgerService = ledgerService;
        this.cycleRegistry = cycleRegistry;
        this.notifier = notifier;
        this.clock = clock;
    }

    @Override
    public OptionalLong onIncome(
            Account from, Account to, BigDecimal amount, @Nullable String pluginSystem, long paymentTxnId) {
        ProgressiveIncomeTaxConfiguration.Snapshot cfg = config.snapshot();
        if (!cfg.isActive() || !isTaxableIncome(cfg, from, to, pluginSystem)) {
            return OptionalLong.empty();
        }

        UUID owner = to.getOwnerUuid();
        long periodStart = cfg.period().periodStart(clock.instant()).toEpochMilli();
        BigDecimal received = Money.normalize(amount);

        // Lock the running total for the rest of the payment's transaction so two
        // concurrent payments to one player can't both be taxed against the same total.
        incomeTaxMapper.lockPeriod(owner, periodStart);
        BigDecimal before = Objects.requireNonNullElse(incomeTaxMapper.readIncome(owner, periodStart), BigDecimal.ZERO);
        BigDecimal tax = cfg.brackets().taxOnIncrement(before, received);
        incomeTaxMapper.addIncome(owner, periodStart, received, tax);

        if (tax.compareTo(Money.MINIMUM_AMOUNT) < 0) {
            return OptionalLong.empty();
        }

        int destinationId = resolveDestination(cfg.governmentAccount());
        // adminTransfer: this is the treasury collecting, not the player authorising a
        // payment. The tax never exceeds what was just credited, so it cannot overdraw.
        long taxTxnId = ledgerService
                .get()
                .adminTransfer(new TransferRequest(
                        to.getAccountId(),
                        destinationId,
                        tax,
                        "Income tax on " + received + " (period income " + before.add(received) + ")",
                        TreasuryConstants.VIRTUAL_TREASURY_INITIATOR,
                        null,
                        TreasuryConstants.TREASURY_PLUGIN_NAME,
                        Idempotency.sha256("progressive-income-tax:" + paymentTxnId)));
        cycleRegistry.recordCollected(destinationId, TAX_TYPE, tax);
        notifier.notifyTaxCollected(to.getAccountId(), TAX_TYPE, tax);
        log.debug(
                "Income tax {} on payment txn={} to {} (period income {} -> {})",
                tax,
                paymentTxnId,
                owner,
                before,
                before.add(received));
        return OptionalLong.of(taxTxnId);
    }

    /**
     * Income is money a player receives from someone else: not a move between accounts
     * they own, and not from an exempt source account or plugin.
     */
    private static boolean isTaxableIncome(
            ProgressiveIncomeTaxConfiguration.Snapshot cfg, Account from, Account to, @Nullable String pluginSystem) {
        if (to.getAccountType() != AccountType.PERSONAL || to.getOwnerUuid() == null) {
            return false;
        }
        if (to.getOwnerUuid().equals(from.getOwnerUuid())) {
            return false;
        }
        if (from.getAccountType() != AccountType.PERSONAL && cfg.isExemptSourceAccount(from.getDisplayName())) {
            return false;
        }
        return !cfg.isExemptPlugin(pluginSystem);
    }

    private int resolveDestination(String accountName) {
        Destination cached = destination;
        if (cached != null && cached.name().equals(accountName)) {
            return cached.id();
        }
        synchronized (this) {
            cached = destination;
            if (cached == null || !cached.name().equals(accountName)) {
                Account account = accountMapper.findGovernmentAccountByName(accountName);
                if (account == null) {
                    // Primitive GOVERNMENT account: overdraft on, -1 = unlimited credit (sink).
                    // Not cached: it is created inside the payment's transaction, which may
                    // still roll back; the next payment will find the committed row.
                    account = AccountFactory.primitiveGovernment(accountName);
                    accountMapper.insertAccount(account);
                    accountMapper.seedBalance(account.getAccountId());
                    log.warn("Income tax government account '{}' did not exist — created it", accountName);
                    return account.getAccountId();
                }
                cached = new Destination(accountName, account.getAccountId());
                destination = cached;
            }
            return cached.id();
        }
    }
}
