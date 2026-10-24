package io.paradaux.treasury.commands;

import com.google.inject.Inject;
import io.paradaux.hibernia.framework.commander.annotations.*;
import io.paradaux.hibernia.framework.commander.spi.CommandHandler;
import io.paradaux.hibernia.framework.i18n.Message;
import io.paradaux.treasury.model.Page;
import io.paradaux.treasury.model.economy.BalanceEntry;
import io.paradaux.treasury.services.AccountService;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;

@Command({"baltop"})
@Permission("treasury.baltop")
public class BaltopCommand implements CommandHandler {

    private static final int PAGE_SIZE = 10;

    private final AccountService accountService;
    private final Message message;

    @Inject
    public BaltopCommand(AccountService accountService, Message message) {
        this.accountService = accountService;
        this.message = message;
    }

    @Route("help")
    @Description("Show /baltop help")
    public void help(@Sender CommandSender sender) {
        message.send(sender, "treasury.help.balance");
    }

    @Route("")
    @Async
    @Description("View top balances")
    public void baltop(@Sender CommandSender sender) {
        showBaltop(sender, 1);
    }

    @Route("<page>")
    @Async
    @Description("View top balances")
    public void baltopPage(@Sender CommandSender sender, @Arg("page") int page) {
        showBaltop(sender, page);
    }

    private void showBaltop(CommandSender sender, int page) {
        if (page < 1) page = 1;
        long requestedOffset = (long) (page - 1) * PAGE_SIZE;
        if (requestedOffset > Integer.MAX_VALUE) {
            message.send(sender, "treasury.baltop.empty");
            return;
        }
        int offset = (int) requestedOffset;

        Page<BalanceEntry> result = accountService.getTopBalances(offset, PAGE_SIZE);

        if (result.items().isEmpty()) {
            message.send(sender, "treasury.baltop.empty");
            return;
        }

        message.send(
                sender,
                "treasury.baltop.header",
                "page",
                String.valueOf(result.pageNumber()),
                "pages",
                String.valueOf(result.totalPages()));

        for (int i = 0; i < result.items().size(); i++) {
            BalanceEntry entry = result.items().get(i);
            String playerName = resolvePlayerName(entry.getOwnerUuid(), entry.getDisplayName());
            message.send(
                    sender,
                    "treasury.baltop.entry",
                    "rank",
                    String.valueOf(offset + i + 1),
                    "player",
                    playerName,
                    "balance",
                    accountService.formatAmount(entry.getBalance()));
        }

        if (result.hasMore()) {
            message.send(sender, "treasury.baltop.footer", "next", String.valueOf(page + 1));
        }
    }

    private static String resolvePlayerName(UUID uuid, String fallback) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
        String name = player.getName();
        return name != null ? name : fallback;
    }
}
