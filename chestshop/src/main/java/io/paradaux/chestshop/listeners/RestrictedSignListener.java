package io.paradaux.chestshop.listeners;

import io.paradaux.chestshop.services.RestrictedSignService;
import io.paradaux.chestshop.services.SignService;
import io.paradaux.chestshop.services.impl.RestrictedSignServiceImpl;
import com.google.inject.Inject;
import io.paradaux.chestshop.utils.Permissions;
import io.paradaux.chestshop.services.AccountService;
import io.paradaux.chestshop.services.AdminBypassService;
import io.paradaux.hibernia.framework.i18n.Message;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.SignChangeEvent;

import static io.paradaux.chestshop.utils.BlockUtil.getState;
import static io.paradaux.chestshop.utils.Permissions.ADMIN;

/**
 * Bukkit entrypoint for {@code [restricted]} access signs: protects a restricted sign from being
 * destroyed and validates the creation of one. The access decisions live in
 * {@link RestrictedSignService} (chestshop/structure/0002); this class stays a thin listener.
 *
 * @author Acrobot
 */
public class RestrictedSignListener implements Listener {

    private final Message message;
    private final AccountService accounts;
    private final SignService signService;
    private final AdminBypassService adminBypass;
    private final RestrictedSignService restrictedSigns;

    @Inject
    public RestrictedSignListener(Message message, AccountService accounts, SignService signService,
                                  AdminBypassService adminBypass, RestrictedSignService restrictedSigns) {
        this.adminBypass = adminBypass;
        this.message = message;
        this.accounts = accounts;
        this.signService = signService;
        this.restrictedSigns = restrictedSigns;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockDestroy(BlockBreakEvent event) {
        Block destroyed = event.getBlock();
        Sign attachedRestrictedSign = restrictedSigns.getRestrictedSign(destroyed.getLocation());

        if (attachedRestrictedSign == null) {
            return;
        }

        if (!restrictedSigns.canDestroy(event.getPlayer(), attachedRestrictedSign)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        String[] lines = event.getLines();
        Player player = event.getPlayer();

        if (RestrictedSignServiceImpl.isRestricted(lines)) {
            if (!restrictedSigns.hasPermission(player, lines)) {
                message.send(player, "chestshop.ACCESS_DENIED");
                dropSignAndCancelEvent(event);
                return;
            }
            Block connectedSign = event.getBlock().getRelative(BlockFace.DOWN);

            if (!adminBypass.has(player, ADMIN) || !signService.isValid(connectedSign)) {
                dropSignAndCancelEvent(event);
                return;
            }

            Sign sign = (Sign) getState(connectedSign, false);

            if (!accounts.hasPermission(player, Permissions.OTHER_NAME_DESTROY, sign)) {
                dropSignAndCancelEvent(event);
                return;
            }

            message.send(player, "chestshop.RESTRICTED_SIGN_CREATED");
        }
    }

    private static void dropSignAndCancelEvent(SignChangeEvent event) {
        event.getBlock().breakNaturally();
        event.setCancelled(true);
    }
}
