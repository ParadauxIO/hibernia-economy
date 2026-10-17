package io.paradaux.chestshop.services.impl;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.chestshop.model.PendingTransaction;
import io.paradaux.chestshop.services.AccountService;
import io.paradaux.chestshop.services.AdminBypassService;
import io.paradaux.chestshop.services.RestrictedSignService;
import io.paradaux.chestshop.utils.BlockUtil;
import io.paradaux.chestshop.utils.Permissions;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;

import static io.paradaux.chestshop.model.PendingTransaction.TransactionOutcome.SHOP_IS_RESTRICTED;
import static io.paradaux.chestshop.utils.BlockUtil.getState;
import static io.paradaux.chestshop.utils.Permissions.ADMIN;

/**
 * The {@code [restricted]} access-sign business logic, extracted from {@code RestrictedSignListener}
 * (chestshop/structure/0002). The Bukkit event handlers stay in the listener; this service owns the
 * decisions (resolve the restricted sign, access/destroy/group-permission checks, and the pre-trade
 * gate). The sign-format predicates ({@link #isRestricted}) stay static — they are pure utilities.
 *
 * @author Acrobot
 */
@Singleton
public class RestrictedSignServiceImpl implements RestrictedSignService {
    private static final BlockFace[] SIGN_CONNECTION_FACES = {BlockFace.SELF, BlockFace.UP, BlockFace.EAST, BlockFace.WEST, BlockFace.NORTH, BlockFace.SOUTH};

    private final AccountService accounts;
    private final AdminBypassService adminBypass;

    @Inject
    public RestrictedSignServiceImpl(AccountService accounts, AdminBypassService adminBypass) {
        this.accounts = accounts;
        this.adminBypass = adminBypass;
    }

    @Override
    public void onPreTransaction(PendingTransaction event) {
        if (event.isCancelled()) {
            return;
        }

        Sign sign = event.getSign();

        if (isRestrictedShop(sign) && !canAccess(sign, event.getClient())) {
            event.setCancelled(SHOP_IS_RESTRICTED);
        }
    }

    @Override
    public Sign getRestrictedSign(Location location) {
        Block currentBlock = location.getBlock();

        if (BlockUtil.isSign(currentBlock)) {
            Sign sign = (Sign) getState(currentBlock, false);

            if (isRestricted(sign)) {
                return sign;
            } else {
                return null;
            }
        }

        for (BlockFace face : SIGN_CONNECTION_FACES) {
            Block relative = currentBlock.getRelative(face);

            if (!BlockUtil.isSign(relative)) {
                continue;
            }

            Sign sign = (Sign) getState(relative, false);

            if (!BlockUtil.getAttachedBlock(sign).equals(currentBlock)) {
                continue;
            }

            if (isRestricted(sign)) {
                return sign;
            }
        }

        return null; //No sign found
    }

    public static boolean isRestrictedShop(Sign sign) {
        Block blockUp = sign.getBlock().getRelative(BlockFace.UP);
        return BlockUtil.isSign(blockUp) && isRestricted(((Sign) getState(blockUp, false)).getLines());
    }

    public static boolean isRestricted(String[] lines) {
        return lines[0].equalsIgnoreCase("[restricted]");
    }

    public static boolean isRestricted(Sign sign) {
        return isRestricted(sign.getLines());
    }

    @Override
    public boolean canAccess(Sign sign, Player player) {
        Block blockUp = sign.getBlock().getRelative(BlockFace.UP);
        return !BlockUtil.isSign(blockUp) || hasPermission(player, ((Sign) getState(blockUp, false)).getLines());
    }

    @Override
    public boolean canDestroy(Player player, Sign sign) {
        Sign shopSign = getAssociatedSign(sign);
        return accounts.hasPermission(player, Permissions.OTHER_NAME_DESTROY, shopSign);
    }

    public static Sign getAssociatedSign(Sign restricted) {
        Block down = restricted.getBlock().getRelative(BlockFace.DOWN);
        return BlockUtil.isSign(down) ? (Sign) getState(down, false) : null;
    }

    @Override
    public boolean hasPermission(Player p, String[] lines) {
        if (adminBypass.has(p, ADMIN)) {
            return true;
        }

        for (String line : lines) {
            if (adminBypass.has(p, Permissions.GROUP + line)) {
                return true;
            }
        }
        return false;
    }
}
