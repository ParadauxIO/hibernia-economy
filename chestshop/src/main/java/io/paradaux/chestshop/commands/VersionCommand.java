package io.paradaux.chestshop.commands;

import com.google.inject.Inject;
import io.paradaux.chestshop.ChestShop;
import io.paradaux.chestshop.services.ItemService;
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
public class VersionCommand implements CommandHandler {

    private final ItemService items;
    private final Message message;

    @Inject
    public VersionCommand(ItemService items, Message message) {
        this.items = items;
        this.message = message;
    }

    @Route("version")
    @Description("Show the ChestShop plugin version")
    public void version(@Sender CommandSender sender) {
        message.send(sender, "chestshop.VERSION", "name", ChestShop.getPluginName(), "version", ChestShop.getVersion());
    }

    @Route("reload")
    @Description("Reload the ChestShop configuration")
    public void reload(@Sender CommandSender sender) {
        // Was the ChestShopReloadEvent fan-out: reload the config and the item
        // aliases (ItemAliasModule). Both run directly now.
        ChestShop.getPlugin().loadConfig();
        items.reloadAliases();
        message.send(sender, "chestshop.CONFIG_RELOADED");
    }
}
