package io.paradaux.chestshop.commands;

import com.google.inject.Inject;
import io.paradaux.chestshop.services.MetricsService;
import io.paradaux.chestshop.services.AccountService;
import io.paradaux.hibernia.framework.commander.annotations.Command;
import io.paradaux.hibernia.framework.commander.annotations.Description;
import io.paradaux.hibernia.framework.commander.annotations.Route;
import io.paradaux.hibernia.framework.commander.annotations.Sender;
import io.paradaux.hibernia.framework.commander.spi.CommandHandler;
import io.paradaux.hibernia.framework.i18n.Message;
import org.bukkit.command.CommandSender;

/**
 * @author Acrobot
 */
@Command({"chestshop", "cs"})
@io.paradaux.hibernia.framework.commander.annotations.Permission("ChestShop.admin")
public class MetricsCommand implements CommandHandler {

    private final AccountService accounts;
    private final Message message;

    private final io.paradaux.chestshop.services.MetricsService metrics;

    @Inject
    public MetricsCommand(AccountService accounts, Message message, MetricsService metrics) {
        this.metrics = metrics;
        this.accounts = accounts;
        this.message = message;
    }

    @Route("metrics")
    @Description("Show aggregate ChestShop transaction metrics")
    public void metrics(@Sender CommandSender sender) {
        message.send(sender, "chestshop.METRICS", "prefix", "",
                "accounts", String.valueOf(accounts.getAccountCount()),
                "totalTransactions", String.valueOf(metrics.getTotalTransactions()),
                "buyTransactions", String.valueOf(metrics.getBuyTransactions()),
                "sellTransactions", String.valueOf(metrics.getSellTransactions()),
                "totalItems", String.valueOf(metrics.getTotalItemsCount()),
                "boughtItems", String.valueOf(metrics.getBoughtItemsCount()),
                "soldItems", String.valueOf(metrics.getSoldItemsCount())
        );
    }
}
