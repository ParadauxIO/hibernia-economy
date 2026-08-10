package io.paradaux.chestshop.listeners;

import com.google.inject.Inject;
import io.paradaux.chestshop.services.SignService;
import io.paradaux.chestshop.utils.BlockUtil;
import io.paradaux.hibernia.framework.i18n.Message;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;

/**
 * Blocks edits to the back side of a shop sign so the validated front-face
 * payload can't be circumvented. Was the sign half of the version-named
 * {@code adapter/Spigot_1_20}.
 */
public class SignBacksideProtectorListener implements Listener {

    private final Message message;
    private final SignService signService;

    @Inject
    public SignBacksideProtectorListener(Message message, SignService signService) {
        this.message = message;
        this.signService = signService;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOW)
    public void onSignChange(SignChangeEvent event) {
        Block signBlock = event.getBlock();

        if (!BlockUtil.isSign(signBlock)) {
            return;
        }

        if (event.getSide() != Side.FRONT) {
            Sign sign = (Sign) BlockUtil.getState(signBlock, false);
            if (signService.isValid(sign)) {
                event.setCancelled(true);
                message.send(event.getPlayer(), "chestshop.CANNOT_CHANGE_SIGN_BACKSIDE");
            }
        }
    }
}
