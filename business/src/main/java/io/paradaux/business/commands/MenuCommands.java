package io.paradaux.business.commands;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.business.commands.resolvers.FirmName;
import io.paradaux.business.dialogs.FirmMenu;
import io.paradaux.business.services.*;
import io.paradaux.hibernia.framework.commander.annotations.*;
import io.paradaux.hibernia.framework.commander.spi.CommandHandler;
import io.paradaux.hibernia.framework.i18n.Message;
import io.paradaux.hibernia.framework.usher.DialogManager;
import java.util.List;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Loads database-backed menu data off-thread; only opens the UI on the server thread. */
@Singleton
@Command({"db", "democracybusiness", "business", "firm", "company"})
public final class MenuCommands implements CommandHandler {
    private final FirmService firms;
    private final FirmTransactionService transactions;
    private final FirmAccountService accounts;
    private final FirmStaffService staff;
    private final FirmPlayerService players;
    private final DialogManager dialogs;
    private final JavaPlugin plugin;
    private final Message message;

    @Inject
    public MenuCommands(
            FirmService firms,
            FirmTransactionService transactions,
            FirmAccountService accounts,
            FirmStaffService staff,
            FirmPlayerService players,
            DialogManager dialogs,
            JavaPlugin plugin,
            Message message) {
        this.firms = firms;
        this.transactions = transactions;
        this.accounts = accounts;
        this.staff = staff;
        this.players = players;
        this.dialogs = dialogs;
        this.plugin = plugin;
        this.message = message;
    }

    @Route("menu")
    @Permission("business.menu")
    @Description("Choose one of your firms to manage")
    @Async
    public void menu(@Sender Player player) {
        var choices = firms.listOwnedOrMemberFirms(player.getUniqueId()).stream()
                .map(f -> new FirmMenu.Choice(f.getFirmId().toString(), f.getDisplayName()))
                .toList();
        show(player, new FirmMenu.State(choices));
    }

    @Route("menu <firm>")
    @Permission("business.menu")
    @Description("Open a firm's overview, staff, accounts and money actions")
    @Async
    public void menu(@Sender Player player, @Arg("firm") FirmName ref) {
        var firm = firms.getFirmByNameOrId(ref.value());
        if (firm == null) {
            message.send(player, "business.firm.not-found", "firm", ref.value());
            return;
        }
        if (!player.hasPermission("business.info")) {
            message.send(player, "business.general.no-permission");
            return;
        }
        String balance = player.hasPermission("business.finance")
                ? transactions.getFormattedAggregateBalance(firm.getFirmId())
                : "—";
        var accountLines = player.hasPermission("business.account.list")
                ? accounts.listAccounts(firm.getFirmId()).stream()
                        .map(a -> a.getAccountId() + " · " + a.getDisplayName()
                                + (a.getAccountId().equals(firm.getDefaultAccountId()) ? " ★" : ""))
                        .toList()
                : List.<String>of();
        var staffLines = player.hasPermission("business.staff.list")
                ? staff.getCurrentEmployees(firm.getFirmId()).stream()
                        .map(e -> players.findByUuid(e.getPlayerUuid())
                                        .map(p -> p.getCurrentName())
                                        .orElse(e.getPlayerUuid())
                                + " · " + e.getRoleName())
                        .toList()
                : List.<String>of();
        show(player, new FirmMenu.State(firm.getFirmId(), firm.getDisplayName(), balance, accountLines, staffLines));
    }

    private void show(Player player, FirmMenu.State state) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && player.hasPermission("business.menu")) dialogs.open(player, FirmMenu.class, state);
        });
    }
}
