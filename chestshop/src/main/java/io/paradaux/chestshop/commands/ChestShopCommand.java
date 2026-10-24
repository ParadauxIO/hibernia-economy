package io.paradaux.chestshop.commands;

import com.google.inject.Inject;
import com.google.inject.Provider;
import io.paradaux.hibernia.framework.commander.CommandManager;
import io.paradaux.hibernia.framework.commander.HelpGenerator;
import io.paradaux.hibernia.framework.commander.annotations.Command;
import io.paradaux.hibernia.framework.commander.annotations.Description;
import io.paradaux.hibernia.framework.commander.annotations.OptionalArg;
import io.paradaux.hibernia.framework.commander.annotations.Route;
import io.paradaux.hibernia.framework.commander.annotations.Sender;
import io.paradaux.hibernia.framework.commander.spi.CommandHandler;
import org.bukkit.command.CommandSender;

/**
 * The unified {@code /chestshop} (alias {@code /cs}) command-tree root: paginated
 * help. Subcommands (access, notify, bypass, give, metrics, version, reload) are
 * contributed by their own handlers under the same command root.
 */
@Command({"chestshop", "cs"})
public class ChestShopCommand implements CommandHandler {

    /** Provider breaks the CommandManager → Set&lt;CommandHandler&gt; → this-class cycle. */
    @Inject private Provider<CommandManager> commandManager;

    @Route("")
    @Description("Show ChestShop command help")
    public void root(@Sender CommandSender sender) {
        sendHelp(sender, 1);
    }

    @Route("help [page]")
    @Description("Show ChestShop command help")
    public void help(@Sender CommandSender sender,
                     @OptionalArg(value = "page", defaultValue = "1") int page) {
        sendHelp(sender, page);
    }

    private void sendHelp(CommandSender sender, int page) {
        sender.sendMessage(new HelpGenerator(commandManager.get()).render(sender, "chestshop", page));
    }
}
