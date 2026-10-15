package io.paradaux.business.jobs;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.business.Business;
import io.paradaux.business.services.FirmAccountService;
import org.bukkit.Bukkit;

import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.concurrent.atomic.AtomicBoolean;

/** Repairs partial Treasury membership writes at startup and every minute (ADT-91). */
@Singleton
public class ReconcileAccountMembersJob implements Runnable {

    private static final Logger LOG = Logger.getLogger(ReconcileAccountMembersJob.class.getName());
    private static final long INTERVAL_TICKS = 60L * 20L;

    private final Business business;
    private final FirmAccountService accounts;
    private final AtomicBoolean running = new AtomicBoolean();

    @Inject
    public ReconcileAccountMembersJob(Business business, FirmAccountService accounts) {
        this.business = business;
        this.accounts = accounts;
    }

    @Override
    public void run() {
        // Prevent overlapping sweeps if a database outage takes longer than the
        // interval. Per-firm service locks also coordinate with command syncs.
        if (!running.compareAndSet(false, true)) return;
        try {
            accounts.reconcileLinkedAccounts();
        } catch (RuntimeException e) {
            // Includes failure to load the worklist itself; keep the timer alive.
            LOG.log(Level.WARNING, "Membership recovery pass failed; will retry next minute.", e);
        } finally {
            running.set(false);
        }
    }

    public void schedule() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(business, this, 0, INTERVAL_TICKS);
    }
}
