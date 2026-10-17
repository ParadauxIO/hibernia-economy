package io.paradaux.treasury.events;

import com.google.inject.Inject;
import io.paradaux.treasury.services.cache.OnlinePlayerRoster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Keeps {@link OnlinePlayerRoster} in step with who is actually online, so the
 * {@code /pay} tab-completer can read the roster off the (non-main) suggestion
 * thread without ever touching live Bukkit state.
 *
 * <p>Both handlers run on the main thread (Bukkit dispatches events there), which is
 * the only thread that writes the roster — reads on the suggestion thread stay
 * lock-free.
 */
public class OnlinePlayerRosterListener implements Listener {

    private final OnlinePlayerRoster roster;

    @Inject
    public OnlinePlayerRosterListener(OnlinePlayerRoster roster) {
        this.roster = roster;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        roster.add(player.getUniqueId(), player.getName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        roster.remove(event.getPlayer().getUniqueId());
    }
}
