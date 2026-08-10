package io.paradaux.business.services;

import io.paradaux.treasury.event.TaxCycleEvent;

import java.math.BigDecimal;

/**
 * Owns the weekly corporate balance-tax computation: which firms are taxed, how
 * much each of their accounts contributes, and submitting the batch to Treasury.
 *
 * <p>The per-account allocation splits a firm's total tax proportionally across
 * its positive-balance accounts, settles each at currency precision (2dp), and
 * folds the rounding remainder into the largest-balance account so the
 * per-account sum matches the firm total exactly.
 */
public interface FirmBalanceTaxService {

    /**
     * Runs the weekly balance-tax cycle across all active firms and collects the
     * computed tax as a single batch.
     *
     * @param event the firing {@link TaxCycleEvent} (provides the tax API and period)
     * @return a tally of the batch outcome for the caller to log
     */
    BalanceTaxCycleResult runWeeklyCycle(TaxCycleEvent event);

    /**
     * Estimates the weekly balance tax a firm would owe on its current aggregate
     * balance, using the same rate lookup and 2dp collection rounding as the live
     * cycle (plugin-architecture/0006). Keeps the economy arithmetic in the service
     * so command handlers only render.
     *
     * @param firmId the firm to estimate for
     * @return the firm's total balance, the applicable weekly rate, and the
     *         estimated tax at currency precision
     */
    WeeklyTaxEstimate estimateWeeklyTax(Integer firmId);

    /** Outcome tally of a balance-tax batch. */
    record BalanceTaxCycleResult(long collected, long skipped, long failed) {}

    /**
     * A firm's estimated weekly balance tax: its total balance, the applicable
     * weekly rate (as a fraction, e.g. {@code 0.02} for 2%), and the tax due.
     */
    record WeeklyTaxEstimate(BigDecimal totalBalance, BigDecimal rate, BigDecimal estimatedTax) {}
}
