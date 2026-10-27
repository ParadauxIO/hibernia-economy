package io.paradaux.treasuryapi.tasks;

import com.google.inject.Inject;
import io.paradaux.treasuryapi.TreasuryAPI;
import io.paradaux.treasuryapi.services.DerivedGroupReconciliation;
import java.util.concurrent.TimeUnit;

public class DerivedGroupReconciliationTask implements Runnable {
    private final TreasuryAPI plugin;
    private final DerivedGroupReconciliation reconciliation;

    @Inject
    public DerivedGroupReconciliationTask(TreasuryAPI plugin, DerivedGroupReconciliation reconciliation) {
        this.plugin = plugin;
        this.reconciliation = reconciliation;
    }

    @Override
    public void run() {
        try {
            reconciliation.reconcile();
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Explorer derived-group reconciliation failed: " + e.getMessage());
        }
    }

    public void schedule(long intervalSeconds) {
        // Match the real-time DB lease even while Minecraft ticks are delayed.
        long periodSeconds = Math.max(1L, Math.min(intervalSeconds, 1800L));
        plugin.getServer()
                .getAsyncScheduler()
                .runAtFixedRate(plugin, task -> run(), 1L, periodSeconds, TimeUnit.SECONDS);
    }
}
