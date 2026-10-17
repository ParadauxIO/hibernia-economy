package io.paradaux.business.jobs;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import io.paradaux.business.Business;
import io.paradaux.business.services.FirmRequestService;
import org.bukkit.Bukkit;

@Slf4j
@Singleton
public class ExpireRequestsJob implements Runnable {

    // Runs every 30 minutes (30 * 60 * 20 ticks = 36000 ticks)
    private static final long JOB_INTERNAL_TICKS = 30L * 60L * 20L;

    private final Business business;
    private final FirmRequestService requests;

    @Inject
    public ExpireRequestsJob(Business business, FirmRequestService requests) {
        this.business = business;
        this.requests = requests;
    }

    @Override
    public void run() {
        // Go through the service, not the mapper — only services may touch mappers
        // (plugin-architecture/0005).
        FirmRequestService.ExpiryResult result = requests.expireStale();

        if (result.transfers() > 0 || result.invites() > 0) {
            log.info("[Business] Expired {} transfers and {} invites.", result.transfers(), result.invites());
        }
    }

    /**
     * Register the repeating task. Call this once during plugin startup.
     */
    public void schedule() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(business, this, 0, JOB_INTERNAL_TICKS);
    }
}
