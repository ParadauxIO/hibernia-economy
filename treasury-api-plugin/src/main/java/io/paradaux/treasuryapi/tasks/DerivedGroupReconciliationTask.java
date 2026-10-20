package io.paradaux.treasuryapi.tasks;

import com.google.inject.Inject;
import io.paradaux.treasuryapi.TreasuryAPI;
import io.paradaux.treasuryapi.services.DerivedGroupReconciliation;
import org.bukkit.scheduler.BukkitRunnable;

public class DerivedGroupReconciliationTask extends BukkitRunnable {
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
        runTaskTimerAsynchronously(plugin, 1L, Math.max(1L, Math.min(intervalSeconds, 1800L)) * 20L);
    }
}
