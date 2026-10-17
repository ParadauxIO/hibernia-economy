package io.paradaux.chestshop.listeners;

import com.google.inject.Inject;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import io.paradaux.chestshop.ChestShop;
import io.paradaux.chestshop.model.PlayerSnapshot;
import io.paradaux.chestshop.services.AccountService;
import io.paradaux.chestshop.services.TransactionService;

/**
 * @author Acrobot
 */
public class PlayerConnectListener implements Listener {

    private final AccountService accounts;
    private final TransactionService transactions;

    @Inject
    public PlayerConnectListener(AccountService accounts, TransactionService transactions) {
        this.accounts = accounts;
        this.transactions = transactions;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerConnect(final PlayerJoinEvent event) {
        if (accounts.getUuidVersion() < 0) {
            accounts.setUuidVersion(event.getPlayer().getUniqueId().version());
        }

        final PlayerSnapshot playerDTO = new PlayerSnapshot(event.getPlayer());

        // Register (create or refresh) the player's name↔UUID row on every join, as
        // upstream ChestShop does. Only updating pre-existing rows left brand-new
        // players unresolvable: a sign owned by a player who has joined but never
        // created a shop (so getOrCreateAccount never ran) resolves to "Player not
        // found", breaking that player's shops until they happen to create one.
        ChestShop.runInAsyncThread(() -> accounts.storeUsername(playerDTO));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(final PlayerQuitEvent event) {
        // Drop the player's notification-cooldown rows (owned by TransactionService since
        // the pre-transaction validators were folded into it).
        transactions.clearNotificationCooldowns(event.getPlayer().getUniqueId());
    }
}
