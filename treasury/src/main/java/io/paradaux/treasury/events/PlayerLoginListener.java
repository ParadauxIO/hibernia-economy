package io.paradaux.treasury.events;

import com.google.inject.Inject;
import io.paradaux.treasury.Treasury;
import io.paradaux.treasury.model.config.BalanceTaxConfiguration;
import io.paradaux.treasury.services.BalanceTaxService;
import io.paradaux.treasury.services.PlayerDirectoryService;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * The single per-login flow (PAR-35). On every join it, in order:
 *
 * <ol>
 *   <li>records the player in the unified directory ({@code economy_players}) —
 *       current name + last-login epoch — and captures the <em>previous</em> login
 *       epoch the directory just replaced; and</li>
 *   <li>if balance tax is enabled, collects the prorated personal balance tax for
 *       the period between that previous login and now.</li>
 * </ol>
 *
 * <p>Folding both steps into one ordered task is what makes
 * {@code economy_players.last_login_epoch} a single source of truth: the directory
 * is the sole writer of the login clock and the sole reader of the value it
 * replaces, so the tax proration can't race the directory write (which it would if
 * they were two independent listeners). The directory upkeep is unconditional — the
 * name → UUID resolver other commands rely on must stay complete whether or not tax
 * is enabled. The work runs async so it never blocks the main thread.
 */
public class PlayerLoginListener implements Listener {

    private final Treasury plugin;
    private final PlayerDirectoryService playerDirectory;
    private final BalanceTaxService balanceTaxService;
    private final BalanceTaxConfiguration taxConfig;

    @Inject
    public PlayerLoginListener(Treasury plugin,
                               PlayerDirectoryService playerDirectory,
                               BalanceTaxService balanceTaxService,
                               BalanceTaxConfiguration taxConfig) {
        this.plugin            = plugin;
        this.playerDirectory   = playerDirectory;
        this.balanceTaxService = balanceTaxService;
        this.taxConfig         = taxConfig;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        UUID playerUuid = event.getPlayer().getUniqueId();
        String name = event.getPlayer().getName();
        // Captured synchronously so the proration window is precise regardless of
        // async scheduling delay.
        long loginEpochSecs = Instant.now().getEpochSecond();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Long previousLoginEpoch = playerDirectory.recordLogin(playerUuid, name, loginEpochSecs);

                if (taxConfig.isEnabled()) {
                    balanceTaxService.collect(playerUuid, previousLoginEpoch, loginEpochSecs);
                }
            } catch (RuntimeException e) {
                // Best-effort upkeep: never let a write failure surface as an uncaught
                // async scheduler error. Log so a persistent problem stays visible.
                plugin.getLogger().warning("Failed to process login for "
                        + name + " (" + playerUuid + "): " + e);
            }
        });
    }
}
