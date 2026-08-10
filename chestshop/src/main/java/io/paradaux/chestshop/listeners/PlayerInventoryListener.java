package io.paradaux.chestshop.listeners;

import io.paradaux.chestshop.utils.InventoryUtil;
import io.paradaux.chestshop.services.AdminBypassService;
import io.paradaux.chestshop.services.ShopBlockService;
import com.google.inject.Inject;
import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import io.paradaux.chestshop.utils.Permissions;
import io.paradaux.chestshop.services.ProtectionService;
import io.paradaux.chestshop.services.InfoService;
import io.paradaux.chestshop.services.SignService;
import io.paradaux.hibernia.framework.i18n.Message;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.InventoryHolder;

import java.util.ArrayList;
import java.util.List;

import static io.paradaux.chestshop.utils.InventoryUtil.getHolder;
import static io.paradaux.chestshop.utils.InventoryUtil.getLeftSide;
import static io.paradaux.chestshop.utils.InventoryUtil.getRightSide;

/**
 * @author Acrobot
 */
public class PlayerInventoryListener implements Listener {

    private final InfoService info;
    private final Message message;
    private final ProtectionService protection;
    private final ChestShopConfiguration config;
    private final SignService signService;
    private final ShopBlockService shopBlockService;

    private final AdminBypassService adminBypass;

    @Inject
    public PlayerInventoryListener(InfoService info, Message message, ProtectionService protection,
                           ChestShopConfiguration config, SignService signService, ShopBlockService shopBlockService, AdminBypassService adminBypass) {
        this.adminBypass = adminBypass;
        this.info = info;
        this.message = message;
        this.protection = protection;
        this.config = config;
        this.signService = signService;
        this.shopBlockService = shopBlockService;
    }

    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!config.isTurnOffDefaultProtectionWhenProtectedExternally()) {
            return;
        }

        if (!(event.getPlayer() instanceof Player)) {
            return;
        }

        InventoryHolder holder = getHolder(event.getInventory(), false);
        if (!(holder instanceof BlockState) && !(holder instanceof DoubleChest)) {
            return;
        }

        Player player = (Player) event.getPlayer();
        List<Block> containers = new ArrayList<>();

        if (holder instanceof DoubleChest) {
            InventoryHolder leftSide = getLeftSide((DoubleChest) holder, false);
            if (leftSide instanceof BlockState) {
                containers.add(((BlockState) leftSide).getBlock());
            }
            InventoryHolder rightSide = getRightSide((DoubleChest) holder, false);
            if (rightSide instanceof BlockState) {
                containers.add(((BlockState) rightSide).getBlock());
            }
        } else {
            containers.add(((BlockState) holder).getBlock());
        }

        boolean canAccess = false;
        for (Block container : containers) {
            if (shopBlockService.isShopBlock(container)) {
                if (protection.canView(container, player, false)) {
                    canAccess = true;
                }
            } else {
                canAccess = true;
            }
        }

        if (!canAccess) {
            if (adminBypass.has(player, Permissions.SHOPINFO)) {
                for (Block container : containers) {
                    Sign sign = shopBlockService.getConnectedSign(container);
                    if (sign != null) {
                        info.showShopInfo((Player) event.getPlayer(), sign);
                    }
                }
            } else {
                message.send(event.getPlayer(), "chestshop.ACCESS_DENIED");
            }
            event.setCancelled(true);
        }
    }
}
