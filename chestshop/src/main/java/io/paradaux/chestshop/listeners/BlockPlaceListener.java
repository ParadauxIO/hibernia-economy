package io.paradaux.chestshop.listeners;

import io.paradaux.chestshop.utils.BlockUtil;
import io.paradaux.chestshop.services.AdminBypassService;
import io.paradaux.chestshop.services.ShopBlockService;
import com.google.inject.Inject;
import io.paradaux.chestshop.utils.Permissions;
import io.paradaux.chestshop.services.ProtectionService;
import io.paradaux.chestshop.services.SignService;
import io.paradaux.hibernia.framework.i18n.Message;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Acrobot
 */
public class BlockPlaceListener implements Listener {

    private final Message message;
    private final ProtectionService protection;
    private final SignService signService;
    private final ShopBlockService shopBlockService;

    private final AdminBypassService adminBypass;

    @Inject
    public BlockPlaceListener(Message message, ProtectionService protection, SignService signService, ShopBlockService shopBlockService, AdminBypassService adminBypass) {
        this.adminBypass = adminBypass;
        this.message = message;
        this.protection = protection;
        this.signService = signService;
        this.shopBlockService = shopBlockService;
    }

    @EventHandler(ignoreCancelled = true)
    public void onContainerPlace(BlockPlaceEvent event) {
        Block placed = event.getBlockPlaced();

        if (!shopBlockService.couldBeShopContainer(placed)) {
            return;
        }

        Player player = event.getPlayer();

        if (adminBypass.has(player, Permissions.ADMIN)) {
            return;
        }

        if (!protection.canAccess(player, placed)) {
            message.send(event.getPlayer(), "chestshop.ACCESS_DENIED");
            event.setCancelled(true);
            return;
        }

        Block neighbor = BlockUtil.findNeighbor(placed);

        if (neighbor != null && !protection.canAccess(event.getPlayer(), neighbor)) {
            message.send(event.getPlayer(), "chestshop.ACCESS_DENIED");
            event.setCancelled(true);
        }

    }

    @EventHandler(ignoreCancelled = true)
    public void onPlaceAgainstSign(BlockPlaceEvent event) {
        Block against = event.getBlockAgainst();

        if (!signService.isValid(against)) {
            return;
        }

        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onHopperDropperPlace(BlockPlaceEvent event) {
        Block placed = event.getBlockPlaced();

        List<BlockFace> searchDirections = new ArrayList<>();
        switch (placed.getType()) {
            case HOPPER:
                searchDirections.add(BlockFace.UP);
                searchDirections.add(((Directional) placed.getBlockData()).getFacing());
                break;
            case DROPPER:
                searchDirections.add(((Directional) placed.getBlockData()).getFacing());
                break;
            default:
                return;
        }

        for (BlockFace face : searchDirections) {
            Block relative = placed.getRelative(face);

            if (!shopBlockService.couldBeShopContainer(relative)) {
                continue;
            }

            if (!protection.canAccess(event.getPlayer(), relative)) {
                message.send(event.getPlayer(), "chestshop.ACCESS_DENIED");
                event.setCancelled(true);
                return;
            }
        }
    }
}
