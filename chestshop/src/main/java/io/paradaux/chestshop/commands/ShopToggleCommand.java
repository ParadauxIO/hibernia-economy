package io.paradaux.chestshop.commands;

import com.google.inject.Inject;
import io.paradaux.chestshop.services.ItemCodeService;
import io.paradaux.chestshop.services.MarketService;
import io.paradaux.chestshop.services.PreviewService;
import io.paradaux.chestshop.services.SignService;
import io.paradaux.chestshop.utils.Permissions;
import io.paradaux.hibernia.framework.commander.annotations.Arg;
import io.paradaux.hibernia.framework.commander.annotations.Command;
import io.paradaux.hibernia.framework.commander.annotations.Description;
import io.paradaux.hibernia.framework.commander.annotations.Route;
import io.paradaux.hibernia.framework.commander.annotations.Sender;
import io.paradaux.hibernia.framework.commander.spi.CommandHandler;
import io.paradaux.hibernia.framework.i18n.Message;
import org.bukkit.Location;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * {@code /chestshop toggle …} — the owner-facing shop-display controls, split off
 * {@code /find} (which is now item-search only): whether a shop appears in
 * {@code /find} ({@code visibility}), whether its floating item preview shows for
 * everyone ({@code hologram}), and whether <em>this</em> player sees holograms at
 * all ({@code preview}). Its own handler on the {@code /chestshop} root, matching
 * the bypass/give/metrics/notify subcommand idiom. (PAR-322)
 */
@Command({"chestshop", "cs"})
public final class ShopToggleCommand implements CommandHandler {

    private final MarketService marketService;
    private final SignService signService;
    private final ItemCodeService itemCodes;
    private final PreviewService previews;
    private final Message message;

    @Inject
    public ShopToggleCommand(MarketService marketService, SignService signService,
                             ItemCodeService itemCodes, PreviewService previews, Message message) {
        this.marketService = marketService;
        this.signService = signService;
        this.itemCodes = itemCodes;
        this.previews = previews;
        this.message = message;
    }

    /**
     * {@code /chestshop toggle visibility <true|false>} — hide or show the shop the
     * player is looking at from {@code /find} results. Owner (or admin) only.
     */
    @Route("toggle visibility <visible>")
    @Description("Show or hide the shop you're looking at in /find")
    public void toggleVisibility(@Sender CommandSender sender, @Arg("visible") boolean visible) {
        if (!(sender instanceof Player player)) {
            return;
        }
        Sign sign = targetShopSign(player);
        if (sign == null) {
            return; // a reason was already sent
        }
        if (!marketService.enabled()) {
            message.send(player, "find.no-search");
            return;
        }
        Location l = sign.getLocation();
        marketService.market().setShopVisibility(l.getWorld().getName(),
                l.getBlockX(), l.getBlockY(), l.getBlockZ(), visible);
        message.send(player, "shop.visibility-set", "value", visible ? "shown" : "hidden");
    }

    /**
     * {@code /chestshop toggle hologram <true|false>} — show or hide the floating item
     * preview above the shop the player is looking at. Owner (or admin) only.
     */
    @Route("toggle hologram <visible>")
    @Description("Show or hide the floating item preview above the shop you're looking at")
    public void toggleHologram(@Sender CommandSender sender, @Arg("visible") boolean visible) {
        if (!(sender instanceof Player player)) {
            return;
        }
        Sign sign = targetShopSign(player);
        if (sign == null) {
            return;
        }
        if (!marketService.enabled()) {
            message.send(player, "find.no-search");
            return;
        }
        Location l = sign.getLocation();
        String world = l.getWorld().getName();
        marketService.market().setShopHologram(world, l.getBlockX(), l.getBlockY(), l.getBlockZ(), visible);
        if (visible) {
            String itemCode = SignService.getItem(sign);
            ItemStack item = itemCode != null ? itemCodes.decode(itemCode) : null;
            if (item != null) {
                previews.render(world, l.getBlockX(), l.getBlockY(), l.getBlockZ(), item);
            }
        } else {
            previews.destroy(world, l.getBlockX(), l.getBlockY(), l.getBlockZ());
        }
        message.send(player, "shop.hologram-set", "value", visible ? "shown" : "hidden");
    }

    /**
     * {@code /chestshop toggle preview <true|false>} — whether this player sees shop
     * holograms at all. Persisted per-player.
     */
    @Route("toggle preview <visible>")
    @Description("Show or hide shop item previews for yourself")
    public void togglePreview(@Sender CommandSender sender, @Arg("visible") boolean visible) {
        if (!(sender instanceof Player player)) {
            return;
        }
        if (!marketService.enabled()) {
            message.send(player, "find.no-search");
            return;
        }
        marketService.market().setPreviewPreference(player.getUniqueId(), visible);
        previews.applyPreference(player, visible);
        message.send(player, "shop.preview-set", "value", visible ? "shown" : "hidden");
    }

    /** The valid, accessible ChestShop sign the player is looking at, or null (with a message sent). */
    private Sign targetShopSign(Player player) {
        Block block = player.getTargetBlockExact(5);
        if (block == null || !Tag.SIGNS.isTagged(block.getType()) || !(block.getState(false) instanceof Sign sign)) {
            message.send(player, "shop.sign-target");
            return null;
        }
        if (!signService.isValid(sign)) {
            message.send(player, "shop.sign-target");
            return null;
        }
        String owner = SignService.getOwner(sign);
        if (!player.getName().equalsIgnoreCase(owner) && !player.hasPermission(Permissions.ADMIN)) {
            message.send(player, "shop.sign-no-access");
            return null;
        }
        return sign;
    }
}
