package io.paradaux.chestshop.utils;

import org.bukkit.command.CommandSender;

import java.util.Locale;

/**
 * ChestShop permission nodes + the pure permission helpers the framework's command
 * {@code @Permission} annotation can't express: the per-material buy/sell/create nodes
 * (built by concatenation), the raw {@link #hasNode} check, the set-false rule, and the
 * {@link #isGated} classifier for the elevated staff nodes. The bypass-aware check that
 * consults a player's opt-out state lives on the {@code AdminBypassService} service (PAR-305).
 *
 * @author Acrobot
 */
public final class Permissions {

    public static final String SHOP_CREATION_BUY = "ChestShop.shop.create.buy";
    public static final String SHOP_CREATION_BUY_ID = "ChestShop.shop.create.buy.";
    public static final String SHOP_CREATION_SELL = "ChestShop.shop.create.sell";
    public static final String SHOP_CREATION_SELL_ID = "ChestShop.shop.create.sell.";
    public static final String SHOP_CREATION = "ChestShop.shop.create";
    public static final String SHOP_CREATION_ID = "ChestShop.shop.create.";

    public static final String BUY = "ChestShop.shop.buy";
    public static final String BUY_ID = "ChestShop.shop.buy.";
    public static final String SELL = "ChestShop.shop.sell";
    public static final String SELL_ID = "ChestShop.shop.sell.";

    public static final String ADMIN = "ChestShop.admin";
    public static final String ADMIN_SHOP = "ChestShop.adminshop";
    public static final String MOD = "ChestShop.mod";
    public static final String OTHER_NAME = "ChestShop.name";
    public static final String OTHER_NAME_CREATE = "ChestShop.othername.create";
    public static final String OTHER_NAME_DESTROY = "ChestShop.othername.destroy";
    public static final String OTHER_NAME_ACCESS = "ChestShop.othername.access";
    public static final String GROUP = "ChestShop.group.";

    public static final String NOFEE = "ChestShop.nofee";
    public static final String NO_BUY_TAX = "ChestShop.notax.buy";
    public static final String NO_SELL_TAX = "ChestShop.notax.sell";

    public static final String NOTIFY_TOGGLE = "ChestShop.toggle";
    public static final String ITEMINFO = "ChestShop.iteminfo";
    public static final String SHOPINFO = "ChestShop.shopinfo";

    private Permissions() {}

    /** Raw permission check (case-insensitive), with no admin-bypass demotion. */
    public static boolean hasNode(CommandSender sender, String node) {
        return sender.hasPermission(node) || sender.hasPermission(node.toLowerCase(Locale.ROOT));
    }

    /**
     * Whether a permission node is one of the elevated "staff" powers that an opted-out
     * admin should lose. Basic player nodes (shop.create/buy/sell, iteminfo, shopinfo, the
     * toggles, the command itself) are never gated.
     */
    public static boolean isGated(String node) {
        String n = node.toLowerCase(Locale.ROOT);
        return n.startsWith("chestshop.admin")      // admin + adminshop
                || n.startsWith("chestshop.mod")
                || n.startsWith("chestshop.name")
                || n.startsWith("chestshop.othername")
                || n.startsWith("chestshop.nofee")
                || n.startsWith("chestshop.notax")
                || n.startsWith("chestshop.nolimit")
                || n.startsWith("chestshop.group");
    }

    public static boolean hasPermissionSetFalse(CommandSender sender, String permission) {
        return (sender.isPermissionSet(permission) && !sender.hasPermission(permission))
                || (sender.isPermissionSet(permission.toLowerCase(Locale.ROOT)) && !sender.hasPermission(permission.toLowerCase(Locale.ROOT)));
    }
}
