package io.paradaux.treasury.services;

import io.paradaux.treasury.model.economy.Account;
import java.math.BigDecimal;
import java.util.OptionalLong;
import org.jetbrains.annotations.Nullable;

/**
 * The progressive income tax: taxes money arriving in a player's PERSONAL account
 * at the marginal rate(s) its period income reaches. See
 * {@link io.paradaux.treasury.model.config.ProgressiveIncomeTaxConfiguration}.
 */
public interface IncomeTaxService {

    /**
     * Records a payment as income and collects the tax it owes. Called by the ledger
     * inside the payment's own transaction, after a new ledger txn credited a PERSONAL
     * account, so the payment, the running total and the tax commit or roll back
     * together.
     *
     * @param from         the account the money came from
     * @param to           the PERSONAL account that received it
     * @param amount       the amount received
     * @param pluginSystem the plugin that moved the money, if any
     * @param paymentTxnId the ledger txn of the payment
     * @return the tax txn id, or empty when the payment is not taxable income or owes
     *         less than the $0.01 minimum
     */
    OptionalLong onIncome(
            Account from, Account to, BigDecimal amount, @Nullable String pluginSystem, long paymentTxnId);
}
