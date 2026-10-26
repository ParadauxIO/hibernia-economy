package io.paradaux.chestshop.listeners;

import io.paradaux.chestshop.services.ShopBlockService;
import com.google.inject.Inject;
import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import io.paradaux.chestshop.utils.Permissions;
import io.paradaux.chestshop.services.AccountService;
import io.paradaux.hibernia.framework.i18n.Message;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

/**
 * @author Acrobot
 */
public class ChestBreakListener implements Listener {

    private final Message message;
    private final AccountService accounts;
    private final ChestShopConfiguration config;
    private final ShopBlockService shopBlockService;

    @Inject
    public ChestBreakListener(Message message, AccountService accounts, ChestShopConfiguration config, ShopBlockService shopBlockService) {
        this.message = message;
        this.accounts = accounts;
        this.config = config;
        this.shopBlockService = shopBlockService;
    }

    @EventHandler(ignoreCancelled = true)
    public void onChestBreak(BlockBreakEvent event) {
        if (!canBeBroken(event.getBlock(), event.getPlayer())) {
            event.setCancelled(true);
            message.send(event.getPlayer(), "chestshop.ACCESS_DENIED");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplosion(EntityExplodeEvent event) {
        if (event.blockList() == null || !config.isUseBuiltInProtection()) {
            return;
        }

        for (Block block : event.blockList()) {
            if (!canBeBroken(block, null)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (!canBeBroken(event.getBlock(), null)) {
            event.setCancelled(true);
        }
    }

    private boolean canBeBroken(Block block, Player breaker) {
        if (!shopBlockService.couldBeShopContainer(block) || !config.isUseBuiltInProtection()) {
            return true;
        }

        Sign shopSign = shopBlockService.getConnectedSign(block);
        if (breaker != null) {
            return accounts.hasPermission(breaker, Permissions.OTHER_NAME_DESTROY, shopSign);
        }
        return shopSign == null;
    }
}
