package io.paradaux.chestshop.listeners;

import io.paradaux.chestshop.utils.InventoryUtil;
import com.google.inject.Inject;
import io.paradaux.chestshop.services.ShopBlockService;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.DoubleChest;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.InventoryHolder;

import static io.paradaux.chestshop.utils.InventoryUtil.getHolder;

/**
 * A fix for a CraftBukkit bug.
 *
 * @author Acrobot
 */
public class PlayerTeleportListener implements Listener {

    private final ShopBlockService shopBlockService;

    @Inject
    public PlayerTeleportListener(ShopBlockService shopBlockService) {
        this.shopBlockService = shopBlockService;
    }

    @EventHandler
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        InventoryHolder holder = getHolder(event.getPlayer().getOpenInventory().getTopInventory(), false);
        if (!(holder instanceof BlockState)) {
            return;
        }

        Block container;
        if (holder instanceof DoubleChest) {
            container = ((DoubleChest) holder).getLocation().getBlock();
        } else {
            container = ((BlockState) holder).getBlock();
        }

        if (shopBlockService.isShopBlock(container)) {
            event.getPlayer().closeInventory();
        }
    }
}
