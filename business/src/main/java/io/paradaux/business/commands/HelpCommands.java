package io.paradaux.business.commands;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import io.paradaux.hibernia.framework.commander.CommandManager;
import io.paradaux.hibernia.framework.commander.HelpGenerator;
import io.paradaux.hibernia.framework.commander.annotations.*;
import io.paradaux.hibernia.framework.commander.spi.CommandHandler;
import org.bukkit.command.CommandSender;

/**
 * Auto-generated help for the firm/business command tree.
 *
 * <p>Replaces the previous eight hand-maintained {@code business.help.*} pages
 * (PAR-262): the framework's {@link HelpGenerator} renders one paginated listing
 * straight from {@link CommandManager#routeIndex()}, using each route's
 * {@code @Description}. New routes appear in help automatically — no message-key
 * upkeep.
 *
 * <p>The route index keys every route under the command's first alias
 * (lower-cased): {@code "db"}. That is the root the generator filters on.
 */
@Singleton
@Command({"db", "democracybusiness", "business", "firm", "company"})
public class HelpCommands implements CommandHandler {

    /** The canonical root the route index is keyed under (first @Command alias, lower-cased). */
    private static final String ROOT = "db";

    // Provider breaks the construction cycle: CommandManager injects the
    // Set<CommandHandler> (which includes this class), so a direct CommandManager
    // dependency is circular and Guice can't proxy the concrete class. Mirrors the
    // Treasury commands' pattern. Resolved lazily at help-render time.
    private final Provider<CommandManager> commandManager;

    @Inject
    public HelpCommands(Provider<CommandManager> commandManager) {
        this.commandManager = commandManager;
    }

    @Route("")
    @Description("Show command help")
    @Permission("business.help")
    public void root(@Sender CommandSender sender) {
        sendHelp(sender, 1);
    }

    @Route("help")
    @Description("Show command help")
    @Permission("business.help")
    public void help(@Sender CommandSender sender) {
        sendHelp(sender, 1);
    }

    @Route("help <page>")
    @Description("Show command help for the given page")
    @Permission("business.help")
    public void helpPage(@Sender CommandSender sender, @Arg("page") int page) {
        sendHelp(sender, page);
    }

    private void sendHelp(CommandSender sender, int page) {
        sender.sendMessage(new HelpGenerator(commandManager.get()).render(sender, ROOT, page));
    }
}
