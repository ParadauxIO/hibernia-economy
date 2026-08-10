package io.paradaux.business.listeners;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.business.services.OnlineRosterCache;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Maintains {@link OnlineRosterCache} on the main thread so the online-firm
 * tab-completer ({@code OnlineFirmNameResolver}) can read the roster off the
 * suggestion thread without touching live Bukkit state. (business/plugin-architecture/0003)
 */
@Singleton
public class OnlineRosterListener implements Listener {

    private final OnlineRosterCache roster;

    @Inject
    public OnlineRosterListener(OnlineRosterCache roster) {
        this.roster = roster;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        roster.add(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        roster.remove(event.getPlayer().getUniqueId());
    }
}
