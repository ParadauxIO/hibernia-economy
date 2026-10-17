package io.paradaux.business.commands;

import com.google.inject.Inject;
import io.paradaux.hibernia.framework.commander.annotations.*;
import io.paradaux.hibernia.framework.commander.spi.CommandHandler;
import io.paradaux.hibernia.framework.i18n.Message;
import io.paradaux.business.Business;
import io.paradaux.business.model.config.BalanceTaxConfiguration;
import io.paradaux.business.model.config.FirmConfiguration;
import org.bukkit.command.CommandSender;

@Command({"db", "democracybusiness", "business", "firm", "company"})
public class ReloadCommand implements CommandHandler {

    private final Business business;
    private final Message message;
    private final FirmConfiguration firmConfig;
    private final BalanceTaxConfiguration balanceTaxConfig;

    @Inject
    public ReloadCommand(Business business, Message message,
                         FirmConfiguration firmConfig, BalanceTaxConfiguration balanceTaxConfig) {
        this.business = business;
        this.message = message;
        this.firmConfig = firmConfig;
        this.balanceTaxConfig = balanceTaxConfig;
    }

    /**
     * Reloads {@code messages.properties} and {@code config.yml} at runtime and
     * refreshes the live config objects services read from: firm ownership limit
     * / creation cooldown ({@link FirmConfiguration}) and the corporate
     * balance-tax brackets ({@link BalanceTaxConfiguration}).
     *
     * <p>Not in scope (constructed once per JVM): the DB pool, Guice injector,
     * scheduled jobs, and listeners — those still need a server restart (or a
     * plugin manager that re-enables the plugin).
     */
    @Route("reload")
    @Permission("business.admin.reload")
    @Async // config reload does disk I/O — keep it off the server thread (ADT-56).
    @Description("Admin: Reload messages.properties and config.yml (firm limits, balance-tax brackets).")
    public void reload(@Sender CommandSender sender) {
        try {
            business.reloadConfig();      // re-read config.yml from disk
            message.reload();             // re-read messages.properties
            firmConfig.reload();          // refresh firm.owned-limit / create-cooldown
            balanceTaxConfig.reload();    // refresh tax.balance.* brackets
        } catch (RuntimeException e) {
            business.getLogger().warning("Config reload failed: " + e);
            message.send(sender, "business.admin.reload.failed", "error", e.getMessage());
            return;
        }
        message.send(sender, "business.admin.reload.success");
    }
}
