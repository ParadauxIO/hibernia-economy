package io.paradaux.treasury.commands;

import io.paradaux.treasury.utils.TreasuryConstants;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;

/** Shared {@link CommandSender}/UUID idioms used across the treasury command layer. */
final class CommandSenders {

    private CommandSenders() {
    }

    /**
     * The initiator UUID to attribute an action to: the player's UUID, or the
     * virtual console initiator when run from console / RCON.
     */
    static UUID actorOf(CommandSender sender) {
        return sender instanceof Player p
                ? p.getUniqueId()
                : TreasuryConstants.VIRTUAL_TREASURY_INITIATOR;
    }

    /** A player's current name for the given UUID, falling back to the UUID string. */
    static String resolvePlayerName(UUID uuid) {
        if (uuid == null) return "—";
        OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
        String name = op.getName();
        return name != null ? name : uuid.toString();
    }
}
