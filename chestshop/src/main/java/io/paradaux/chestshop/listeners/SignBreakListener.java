package io.paradaux.chestshop.listeners;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import io.paradaux.chestshop.services.SignBreakService;
import io.paradaux.chestshop.services.SignService;
import io.paradaux.hibernia.framework.i18n.Message;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

import static io.paradaux.chestshop.utils.BlockUtil.isSign;

/**
 * Bukkit entrypoint for shop-sign protection and removal: it cancels disallowed breaks (players,
 * pistons, explosions, fire, entities) and fires shop removal once a sign is genuinely broken. The
 * protection/removal decisions live in {@link SignBreakService} (chestshop/structure/0002); this
 * class stays a thin listener.
 *
 * @author Acrobot
 */
@Singleton
public class SignBreakListener implements Listener {

    private final Message message;
    private final ChestShopConfiguration config;
    private final SignService signService;
    private final SignBreakService signBreak;

    @Inject
    public SignBreakListener(Message message, ChestShopConfiguration config, SignService signService, SignBreakService signBreak) {
        this.message = message;
        this.config = config;
        this.signService = signService;
        this.signBreak = signBreak;
    }

    @EventHandler(ignoreCancelled = true)
    public void onSignBreak(BlockBreakEvent event) {
        if (!signBreak.canBlockBeBroken(event.getBlock(), event.getPlayer())) {
            event.setCancelled(true);
            message.send(event.getPlayer(), "chestshop.ACCESS_DENIED");
            if (isSign(event.getBlock())) {
                event.getBlock().getState().update();
            }
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBrokenSign(BlockBreakEvent event) {
        if (signService.isValid(event.getBlock())) {
            signBreak.sendShopDestroyed((Sign) event.getBlock().getState(), event.getPlayer());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPistonExtend(BlockPistonExtendEvent event) {
        for (Block block : event.getBlocks()) {
            if (!signBreak.canBlockBeBroken(block, null)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPistonRetract(BlockPistonRetractEvent event) {
        for (Block block : event.getBlocks()) {
            if (!signBreak.canBlockBeBroken(block, null)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplosion(EntityExplodeEvent event) {
        if (event.blockList() == null || !config.isUseBuiltInProtection()) {
            return;
        }

        for (Block block : event.blockList()) {
            if (!signBreak.canBlockBeBroken(block, null)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onIgnite(BlockBurnEvent event) {
        if (!signBreak.canBlockBeBroken(event.getBlock(), null)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (!signBreak.canBlockBeBroken(event.getBlock(), null)) {
            event.setCancelled(true);
        }
    }
}
