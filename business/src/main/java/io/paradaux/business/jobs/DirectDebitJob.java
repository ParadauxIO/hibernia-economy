package io.paradaux.business.jobs;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.business.Business;
import io.paradaux.business.services.DirectDebitService;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.Bukkit;

/**
 * Drives direct debits (PAR-557): expires lapsed proposals, settles reservations
 * left by a crash, and collects due recurring mandates. Due times live in the
 * database, so restarts neither skip nor repeat a period. Does nothing while the
 * {@code business.direct_debits} flag is off.
 */
@Slf4j
@Singleton
public class DirectDebitJob implements Runnable {

    // Every minute (60 * 20 ticks).
    private static final long INTERVAL_TICKS = 60L * 20L;

    private final Business business;
    private final DirectDebitService debits;

    @Inject
    public DirectDebitJob(Business business, DirectDebitService debits) {
        this.business = business;
        this.debits = debits;
    }

    @Override
    public void run() {
        try {
            DirectDebitService.JobResult result = debits.runJob();
            if (!result.isEmpty()) {
                log.info(
                        "[Business] Direct debits: {} collected, {} failed, {} recovered, {} proposals expired.",
                        result.collected(),
                        result.failed(),
                        result.recovered(),
                        result.expired());
            }
        } catch (RuntimeException e) {
            log.warn("[Business] Direct debit job failed: {}", e.getMessage(), e);
        }
    }

    /** Register the repeating task. Call this once during plugin startup. */
    public void schedule() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(business, this, INTERVAL_TICKS, INTERVAL_TICKS);
    }
}
