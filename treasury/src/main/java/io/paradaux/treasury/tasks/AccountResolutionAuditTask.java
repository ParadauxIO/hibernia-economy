package io.paradaux.treasury.tasks;

import io.paradaux.treasury.services.AccountResolutionAuditService;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

/** Startup + daily warning also surfaces collisions introduced by player renames. */
@Slf4j
public class AccountResolutionAuditTask extends BukkitRunnable {
    private final AccountResolutionAuditService audit;

    public AccountResolutionAuditTask(AccountResolutionAuditService audit) {
        this.audit = audit;
    }

    public void schedule(Plugin plugin) {
        runTaskTimerAsynchronously(plugin, 1L, 24L * 60 * 60 * 20);
    }

    @Override
    public void run() {
        try {
            var collisions = audit.findCollisions();
            log.info("Account-resolution audit: {} active player/government collision(s)", collisions.size());
            for (var collision : collisions) {
                log.warn(
                        "Account-resolution collision: name={}, governmentId={}, playerUuid={}, personalId={}, redirectId={}. Review wiki/account-resolution-audit.md; no funds moved.",
                        collision.getGovernmentName(),
                        collision.getGovernmentId(),
                        collision.getPlayerUuid(),
                        collision.getPersonalId(),
                        collision.getRedirectId());
            }
        } catch (RuntimeException failure) {
            log.error("Account-resolution audit failed; collision status is unknown (will retry tomorrow)", failure);
        }
    }
}
