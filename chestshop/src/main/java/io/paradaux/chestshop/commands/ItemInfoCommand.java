package io.paradaux.chestshop.commands;
import lombok.extern.slf4j.Slf4j;

import com.google.inject.Inject;
import io.paradaux.chestshop.utils.MaterialUtil;
import io.paradaux.chestshop.ChestShop;
import io.paradaux.chestshop.services.InfoService;
import io.paradaux.chestshop.model.ItemInfoLines;
import io.paradaux.chestshop.services.ItemService;
import io.paradaux.hibernia.framework.commander.annotations.Command;
import io.paradaux.hibernia.framework.commander.annotations.GreedyArg;
import io.paradaux.hibernia.framework.commander.annotations.Route;
import io.paradaux.hibernia.framework.commander.annotations.Sender;
import io.paradaux.hibernia.framework.commander.spi.CommandHandler;
import io.paradaux.hibernia.framework.i18n.Message;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.HumanEntity;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

/**
 * @author Acrobot
 */
@Command({"iteminfo", "iinfo"})
@io.paradaux.hibernia.framework.commander.annotations.Permission("ChestShop.iteminfo")
@Slf4j
public class ItemInfoCommand implements CommandHandler {

    private final InfoService info;
    private final ItemService items;
    private final Message message;

    @Inject
    public ItemInfoCommand(InfoService info, ItemService items, Message message) {
        this.info = info;
        this.items = items;
        this.message = message;
    }

    @Route("")
    public void heldItem(@Sender CommandSender sender) {
        if (!(sender instanceof HumanEntity)) {
            return;
        }

        ItemStack item = ((HumanEntity) sender).getItemInHand();
        showItemInfo(sender, item);
    }

    @Route("<item>")
    public void parsedItem(@Sender CommandSender sender,
                           @GreedyArg(value = "item", sanitize = false) String item) {
        showItemInfo(sender, items.parse(item));
    }

    private void showItemInfo(CommandSender sender, ItemStack item) {
        if (MaterialUtil.isEmpty(item)) {
            return;
        }

        message.send(sender, "chestshop.iteminfo", "prefix", "");
        if (!info.sendItemName(sender, item, "chestshop.iteminfo_fullname")) return;

        try {
            message.send(sender, "chestshop.iteminfo_shopname", "prefix", "", "item", items.getSignName(item));
        } catch (IllegalArgumentException e) {
            message.send(sender, "chestshop.ITEM_NAME_ERROR");
            log.error("Error while generating shop sign item name", e);
            return;
        }

        ItemInfoLines lines = info.collectItemInfo(sender, item);
        for (Map.Entry<String, Component> entry : lines.getMessages()) {
            sender.sendMessage(entry.getValue());
        }
    }
}
