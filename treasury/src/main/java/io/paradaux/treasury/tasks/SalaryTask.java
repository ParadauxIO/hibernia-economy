package io.paradaux.treasury.tasks;

import io.paradaux.treasury.Treasury;
import io.paradaux.treasury.services.SalaryService;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitRunnable;


/**
 * Repeating government-salary payout.
 *
 * <p>Runs on the main thread (so reading the online-player list and LuckPerms is
 * safe), computes the payment plan, then hands the actual ledger transfers to an
 * async task so the DB work never stalls the server tick. The government account
 * is an unlimited faucet, so payouts don't depend on its balance.
 */
public class SalaryTask extends BukkitRunnable {

    private final Treasury plugin;
    private final SalaryService salaryService;

    public SalaryTask(Treasury plugin, SalaryService salaryService) {
        this.plugin = plugin;
        this.salaryService = salaryService;
    }

    @Override
    public void run() {
        SalaryService.Payroll plan = salaryService.preparePayroll(); // main thread
        if (plan.payments().isEmpty()) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> salaryService.payoutPayroll(plan));
    }

    /**
     * Schedule the recurring payout. Period (and initial delay) is the configured
     * interval converted from seconds to Bukkit ticks (20 ticks/second).
     */
    public void schedule(long intervalSeconds) {
        long periodTicks = Math.max(1L, intervalSeconds * 20L);
        runTaskTimer(plugin, periodTicks, periodTicks);
    }
}
