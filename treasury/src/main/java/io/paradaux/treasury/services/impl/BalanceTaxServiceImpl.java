package io.paradaux.treasury.services.impl;

import com.google.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import io.paradaux.treasury.api.TaxApi;
import io.paradaux.treasury.mappers.AccountMapper;
import io.paradaux.treasury.model.config.BalanceTaxConfiguration;
import io.paradaux.treasury.model.economy.Account;
import io.paradaux.treasury.model.economy.AccountBalance;
import io.paradaux.treasury.model.tax.TaxCollection;
import io.paradaux.treasury.model.tax.TaxResult;
import io.paradaux.treasury.services.BalanceTaxService;
import io.paradaux.treasury.utils.Idempotency;
import io.paradaux.treasury.utils.Money;
import io.paradaux.treasury.utils.TreasuryConstants;
import org.jetbrains.annotations.NotNull;
import org.mybatis.guice.transactional.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * Default {@link BalanceTaxService}: computes and collects the prorated personal
 * balance tax owed since a player's previous login. See {@link BalanceTaxService}
 * for the tax formula and the login-clock ownership contract.
 */
@Slf4j
public class BalanceTaxServiceImpl implements BalanceTaxService {

    /** Seconds in one week, used for proration. */
    private static final BigDecimal SECONDS_PER_WEEK = BigDecimal.valueOf(7L * 24 * 3600);

    private final BalanceTaxConfiguration config;
    private final TaxApi taxApi;
    private final AccountMapper accountMapper;

    /** Lazily cached destination account ID. */
    private volatile Integer destinationAccountId;

    @Inject
    public BalanceTaxServiceImpl(BalanceTaxConfiguration config,
                                 TaxApi taxApi,
                                 AccountMapper accountMapper) {
        this.config        = config;
        this.taxApi        = taxApi;
        this.accountMapper = accountMapper;
    }

    @Override
    @Transactional
    public void collect(@NotNull UUID playerUuid, Long previousLoginEpoch, long loginEpochSecs) {
        if (previousLoginEpoch == null) {
            // First ever login — nothing to prorate against.
            return;
        }

        long secondsElapsed = loginEpochSecs - previousLoginEpoch;
        if (secondsElapsed <= 0) {
            // Clock skew or same-second reconnect — skip.
            return;
        }

        // 3. Resolve personal account.
        Integer accountId = accountMapper.findPersonalAccountId(playerUuid);
        if (accountId == null) {
            // Account hasn't been created yet (will be created by FirstPlayerJoinEvent).
            return;
        }

        // 4. Read current balance (pre-login snapshot is the taxable amount).
        AccountBalance ab = accountMapper.readBalance(accountId);
        if (ab == null) {
            return;
        }
        BigDecimal balance = ab.getBalance();
        if (balance == null || balance.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        // 5. Determine bracket rate.
        BigDecimal weeklyRate = config.getWeeklyRate(balance);
        if (weeklyRate.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        // 6. Prorate over the elapsed period.
        BigDecimal proration = BigDecimal.valueOf(secondsElapsed)
                .divide(SECONDS_PER_WEEK, 10, RoundingMode.HALF_EVEN);

        BigDecimal taxAmount = balance
                .multiply(weeklyRate)
                .multiply(proration)
                .setScale(2, RoundingMode.HALF_EVEN);

        if (taxAmount.compareTo(Money.MINIMUM_AMOUNT) < 0) {
            return;
        }

        // 7. Build and collect. Dedup key = sha256("balance-tax:{uuid}:{loginEpoch}").
        byte[] dedupKey = Idempotency.sha256("balance-tax:" + playerUuid + ":" + loginEpochSecs);
        int destId = resolveDestinationAccountId();

        TaxCollection collection = TaxCollection.toAccount(
                accountId,
                destId,
                taxAmount,
                "personal-balance-tax",
                "Personal balance tax (" + formatPeriod(secondsElapsed) + " @ "
                        + weeklyRate.multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString() + "%/wk)",
                TreasuryConstants.VIRTUAL_TREASURY_INITIATOR,
                "treasury",
                dedupKey
        );

        TaxResult result = taxApi.collectTax(collection);

        if (result instanceof TaxResult.Collected c) {
            log.debug("Personal balance tax ${} collected from {} (txn {}, period {})",
                    c.amountCharged(), playerUuid, c.txnId(), formatPeriod(secondsElapsed));
        } else if (result instanceof TaxResult.Skipped s) {
            log.debug("Personal balance tax skipped for {} (period {}): {}",
                    playerUuid, formatPeriod(secondsElapsed), s.reason());
        } else if (result instanceof TaxResult.Failed f) {
            log.warn("Personal balance tax collection failed for {} (amount=${}, period={}): {}",
                    playerUuid, taxAmount, formatPeriod(secondsElapsed), f.errorMessage());
        }
    }

    // ---- Helpers ----

    private int resolveDestinationAccountId() {
        if (destinationAccountId == null) {
            synchronized (this) {
                if (destinationAccountId == null) {
                    String accountName = config.getGovernmentAccount();
                    Account account = accountMapper.findGovernmentAccountByName(accountName);
                    if (account != null) {
                        destinationAccountId = account.getAccountId();
                    } else {
                        log.warn("Balance tax government account '{}' not found — routing to default tax account",
                                accountName);
                        destinationAccountId = taxApi.getDefaultTaxAccountId();
                    }
                }
            }
        }
        return destinationAccountId;
    }

    /** Formats an elapsed-seconds value as "Xd Yh" for ledger descriptions. */
    private static String formatPeriod(long seconds) {
        long days  = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        if (days > 0) {
            return days + "d " + hours + "h";
        }
        long mins = (seconds % 3600) / 60;
        return hours + "h " + mins + "m";
    }
}
