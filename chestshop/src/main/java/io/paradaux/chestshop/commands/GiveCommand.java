package io.paradaux.chestshop.commands;

import io.paradaux.chestshop.services.GiveService;
import com.google.inject.Inject;
import io.paradaux.chestshop.services.InventoryService;
import io.paradaux.chestshop.services.ItemService;
import io.paradaux.hibernia.framework.commander.annotations.Arg;
import io.paradaux.hibernia.framework.commander.annotations.Command;
import io.paradaux.hibernia.framework.commander.annotations.Description;
import io.paradaux.hibernia.framework.commander.annotations.OptionalArg;
import io.paradaux.hibernia.framework.commander.annotations.Route;
import io.paradaux.hibernia.framework.commander.annotations.Sender;
import io.paradaux.hibernia.framework.commander.spi.CommandHandler;
import io.paradaux.hibernia.framework.i18n.Message;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * {@code /chestshop give <item-code> [quantity] [player]} — give a player an item by its
 * ChestShop item code (PAR-323).
 *
 * <p>The framework resolves the typed arguments for us: the required item code, an optional
 * integer quantity (default 1), and an optional target player (default: the sender, resolved
 * from the local player cache by the built-in {@code OfflinePlayer} resolver). The handler no
 * longer captures a greedy tail and hand-parses positional tokens — the give core itself lives
 * in {@link GiveService}.</p>
 *
 * <p>The optional tail is ordered {@code [quantity] [player]} because Brigadier can't tell a
 * bare player name from a bare quantity when they sit at the same slot; a fixed order removes
 * the ambiguity. Item codes therefore use their single-token underscore form
 * ({@code REDSTONE_BLOCK}), which {@link ItemService#parse} resolves identically to the old
 * space-separated form. {@code sanitize = false} keeps code punctuation such as {@code :}/{@code #}.</p>
 *
 * @author Acrobot
 */
@Command({"chestshop", "cs"})
@io.paradaux.hibernia.framework.commander.annotations.Permission("ChestShop.admin")
public class GiveCommand implements CommandHandler {

    private final ItemService items;
    private final GiveService give;
    private final Message message;
    private final InventoryService inventoryService;

    @Inject
    public GiveCommand(ItemService items, GiveService give, Message message, InventoryService inventoryService) {
        this.items = items;
        this.give = give;
        this.message = message;
        this.inventoryService = inventoryService;
    }

    @Route("give <code> [quantity] [player]")
    @Description("Give a player an item by its ChestShop item code")
    public void give(@Sender CommandSender sender,
                     @Arg(value = "code", sanitize = false) String code,
                     @OptionalArg(value = "quantity", defaultValue = "1") int quantity,
                     @OptionalArg(value = "player", defaultValue = OptionalArg.SENDER) OfflinePlayer player) {
        Player receiver = player == null ? null : player.getPlayer();

        if (receiver == null) {
            message.send(sender, "chestshop.PLAYER_NOT_FOUND");
            return;
        }

        if (quantity < 1) {
            message.send(sender, "chestshop.INVALID_QUANTITY");
            return;
        }

        ItemStack item = give.resolveGift(code, quantity);

        if (item == null) {
            message.send(sender, "chestshop.INCORRECT_ITEM_ID");
            return;
        }

        inventoryService.add(item, receiver.getInventory());
        message.send(sender, "chestshop.ITEM_GIVEN", "prefix", "", "item", items.getName(item), "player", receiver.getName());
    }
}
