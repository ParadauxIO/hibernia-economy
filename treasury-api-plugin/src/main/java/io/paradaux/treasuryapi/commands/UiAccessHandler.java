package io.paradaux.treasuryapi.commands;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.hibernia.framework.exceptions.NotFoundException;
import io.paradaux.hibernia.framework.i18n.Message;
import io.paradaux.treasuryapi.services.ExplorerUiService;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * Thin command layer for the explorer-UI access commands: {@code /treasuryapi ui
 * user add|remove|list} (role grants), {@code /treasuryapi ui link} (account
 * linking) and {@code /treasuryapi ui sync} (self-sync). Normalises input
 * (resolving player names to UUIDs and guarding against never-seen accounts) and
 * renders success; all persistence + Keycloak orchestration lives in
 * {@link ExplorerUiService}, which throws the framework's semantic exceptions on
 * invalid state (rendered by the command dispatcher).
 */
@Singleton
public class UiAccessHandler {

    private final ExplorerUiService service;
    private final Message message;

    @Inject
    public UiAccessHandler(ExplorerUiService service, Message message) {
        this.service = service;
        this.message = message;
    }

    /**
     * On-demand self-sync: reconcile the calling player's LuckPerms-sourced explorer
     * group memberships now, instead of waiting for the cron. The service throws a
     * semantic exception when syncing is unavailable or fails.
     */
    public void doSelfSync(Player player) {
        service.selfSync(player.getUniqueId());
        message.send(player, "treasuryapi.ui.sync.done");
    }

    public void doUserAdd(CommandSender sender, String role, String playerName) {
        UUID targetUuid = requireSeenPlayer(playerName);
        UUID by = (sender instanceof Player p) ? p.getUniqueId() : null;
        String granted = service.grantRole(targetUuid, role, by);
        message.send(sender, "treasuryapi.ui.user.added", "role", granted, "player", playerName);
    }

    public void doUserRemove(CommandSender sender, String role, String playerName) {
        UUID target = requireSeenPlayer(playerName);
        if (service.revokeRole(target, role)) {
            message.send(sender, "treasuryapi.ui.user.removed",
                    "role", role.toLowerCase(java.util.Locale.ROOT), "player", playerName);
        } else {
            message.send(sender, "treasuryapi.ui.user.not-granted",
                    "role", role.toLowerCase(java.util.Locale.ROOT), "player", playerName);
        }
    }

    public void doUserList(CommandSender sender, String playerName) {
        UUID target = requireSeenPlayer(playerName);
        List<String> roles = service.listRoles(target);
        if (roles.isEmpty()) {
            message.send(sender, "treasuryapi.ui.user.no-roles", "player", playerName);
        } else {
            message.send(sender, "treasuryapi.ui.user.list", "player", playerName, "roles", String.join(", ", roles));
        }
    }

    public void doLink(Player sender, String code) {
        ExplorerUiService.LinkOutcome outcome =
                service.redeemLinkCode(code, sender.getUniqueId(), sender.getName());
        if (outcome == ExplorerUiService.LinkOutcome.LINKED_KEYCLOAK_PENDING) {
            message.send(sender, "treasuryapi.ui.link.partial");
        } else {
            message.send(sender, "treasuryapi.ui.link.success");
        }
    }

    /**
     * Resolve a player name to a UUID, refusing a name nobody on this server has ever
     * owned. {@code getOfflinePlayer(name)} fabricates a deterministic offline UUID for
     * any string, so a typo would otherwise operate on a UUID nobody owns (ADT-38) —
     * applied to add/remove/list alike (treasury-api-plugin/behaviour/0002).
     */
    @SuppressWarnings("deprecation") // offline-safe name→profile; blocking call runs on an async command thread
    private UUID requireSeenPlayer(String name) {
        OfflinePlayer target = Bukkit.getOfflinePlayer(name);
        if (!target.hasPlayedBefore() && !target.isOnline()) {
            throw new NotFoundException("treasuryapi.ui.user.unknown-player", "player", name);
        }
        return target.getUniqueId();
    }
}
