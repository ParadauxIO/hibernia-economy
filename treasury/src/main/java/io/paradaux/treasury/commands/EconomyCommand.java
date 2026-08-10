package io.paradaux.treasury.commands;

import com.google.inject.Inject;
import io.paradaux.hibernia.framework.commander.annotations.*;
import io.paradaux.hibernia.framework.commander.spi.CommandHandler;
import io.paradaux.hibernia.framework.i18n.Message;
import io.paradaux.treasury.model.economy.EconomySummary;
import io.paradaux.treasury.services.AccountService;
import org.bukkit.command.CommandSender;

/**
 * {@code /economy} — top-level money-supply snapshot: total balances held by
 * players, businesses and governments plus the grand total, mirroring the
 * explorer's total-supply view. Public, read-only.
 */
@Command({"economy"})
@Permission("treasury.economy")
public class EconomyCommand implements CommandHandler {

    private final AccountService accountService;
    private final Message message;

    @Inject
    public EconomyCommand(AccountService accountService, Message message) {
        this.accountService = accountService;
        this.message = message;
    }

    @Route("help")
    @Description("Show /economy help")
    public void help(@Sender CommandSender sender) {
        message.send(sender, "treasury.help.economy");
    }

    @Route("")
    @Async
    @Description("Show top-level economy statistics")
    public void economy(@Sender CommandSender sender) {
        EconomySummary summary = accountService.getEconomySummary();

        message.send(sender, "treasury.economy.header");
        message.send(sender, "treasury.economy.personal",
                "amount", accountService.formatAmount(summary.getPersonal()));
        message.send(sender, "treasury.economy.business",
                "amount", accountService.formatAmount(summary.getBusiness()));
        message.send(sender, "treasury.economy.government",
                "amount", accountService.formatAmount(summary.getGovernment()));
        message.send(sender, "treasury.economy.total",
                "amount", accountService.formatAmount(summary.getTotal()));
    }
}
