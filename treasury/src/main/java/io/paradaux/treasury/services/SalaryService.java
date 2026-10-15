package io.paradaux.treasury.services;

import io.paradaux.treasury.model.salary.SalaryPayment;

import java.util.List;

/**
 * Government salary payouts driven by LuckPerms group membership.
 *
 * <p>Split into two phases so threading can be handled safely: {@link #planPayroll()}
 * reads Bukkit + LuckPerms (must run on the main thread), and {@link #payout(List)}
 * does the ledger transfers (safe to run async).
 */
public interface SalaryService {

    /** Carries the funding account across the main-thread/async boundary. */
    record Payroll(List<SalaryPayment> payments, String governmentAccount) {
        public Payroll { payments = List.copyOf(payments); }
    }

    Payroll preparePayroll();

    int payoutPayroll(Payroll payroll);


    /**
     * Resolve the salary owed to each currently-online player (highest-paying
     * matching group). MUST run on the server main thread — it reads the online
     * player list and LuckPerms.
     *
     * @return one payment per online, salaried player (never {@code null})
     */
    List<SalaryPayment> planPayroll();

    /**
     * Pay out a previously-computed plan by transferring from the configured
     * government account to each player's personal account. Safe to run off the
     * main thread.
     *
     * @param payments the plan from {@link #planPayroll()}
     * @return the number of salaries successfully paid
     */
    int payout(List<SalaryPayment> payments);
}
