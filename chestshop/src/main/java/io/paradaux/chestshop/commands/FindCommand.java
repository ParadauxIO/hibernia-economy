package io.paradaux.chestshop.commands;

import com.google.inject.Inject;
import io.paradaux.chestshop.dialogs.FindDialog;
import io.paradaux.chestshop.dialogs.FindState;
import io.paradaux.chestshop.services.ItemCodeService;
import io.paradaux.chestshop.services.ItemService;
import io.paradaux.chestshop.utils.MaterialUtil;
import io.paradaux.hibernia.framework.commander.annotations.Command;
import io.paradaux.hibernia.framework.commander.annotations.GreedyArg;
import io.paradaux.hibernia.framework.commander.annotations.Permission;
import io.paradaux.hibernia.framework.commander.annotations.Route;
import io.paradaux.hibernia.framework.commander.annotations.Sender;
import io.paradaux.hibernia.framework.commander.spi.CommandHandler;
import io.paradaux.hibernia.framework.i18n.Message;
import io.paradaux.hibernia.framework.usher.DialogManager;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * {@code /find} — search the live shop registry for shops trading an item and
 * open the Usher find dialog. With no argument it uses the item in the player's
 * main hand; {@code /find <itemCode>} resolves a typed code (custom-aware).
 *
 * <p>Item search only. The former {@code /find toggle …} display controls and
 * {@code /find resync} admin rebuild moved onto {@code /chestshop} — see
 * {@link ShopToggleCommand} and {@link ResyncCommand}. (PAR-322)
 */
@Command("find")
@Permission("ChestShop.find")
public final class FindCommand implements CommandHandler {

    private final DialogManager dialogs;
    private final ItemService items;
    private final ItemCodeService itemCodes;
    private final Message message;

    @Inject
    public FindCommand(DialogManager dialogs, ItemService items, ItemCodeService itemCodes, Message message) {
        this.dialogs = dialogs;
        this.items = items;
        this.itemCodes = itemCodes;
        this.message = message;
    }

    @Route("")
    public void heldItem(@Sender CommandSender sender) {
        if (!(sender instanceof Player player)) {
            return;
        }
        ItemStack item = player.getInventory().getItemInMainHand().asOne();
        if (item == null || item.getType().isAir()) {
            message.send(player, "find.need-item");
            return;
        }
        openFind(player, item, canonicalKey(item), displayName(item));
    }

    @Route("<item>")
    public void byCode(@Sender CommandSender sender,
                       @GreedyArg(value = "item", sanitize = false) String code) {
        if (!(sender instanceof Player player)) {
            return;
        }
        ItemStack item = items.parse(code);
        if (item == null || item.getType().isAir()) {
            message.send(player, "find.unknown-item", "code", code);
            return;
        }
        openFind(player, item, canonicalKey(item), code);
    }

    private void openFind(Player player, ItemStack item, String itemKey, String displayName) {
        Location loc = player.getLocation();
        FindState state = new FindState(item, itemKey, displayName,
                player.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), true);
        dialogs.open(player, FindDialog.class, state);
    }

    /**
     * The canonical (custom-aware) sign code stored on {@code chestshop_shop.item_key} —
     * mirrors {@code MarketService.itemKey} so a search matches what the tracker wrote.
     */
    private String canonicalKey(ItemStack item) {
        try {
            String code = items.getName(item, 0);
            if (code != null && !code.isBlank()) {
                return code;
            }
        } catch (RuntimeException ignored) {
            // getName re-parses the code and throws if it doesn't round-trip; fall back.
        }
        return itemCodes.encode(item, MaterialUtil.MAXIMUM_SIGN_WIDTH);
    }

    private String displayName(ItemStack item) {
        net.kyori.adventure.text.Component display =
                item.hasItemMeta() && item.getItemMeta() != null ? item.getItemMeta().displayName() : null;
        if (display != null) {
            String name = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(display);
            if (!name.isBlank()) {
                return name.trim();
            }
        }
        return canonicalKey(item);
    }
}
