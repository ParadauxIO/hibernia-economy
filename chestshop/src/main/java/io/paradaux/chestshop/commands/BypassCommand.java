package io.paradaux.chestshop.commands;

import com.google.inject.Inject;
import io.paradaux.chestshop.services.AdminBypassService;
import io.paradaux.hibernia.framework.commander.annotations.Command;
import io.paradaux.hibernia.framework.commander.annotations.Description;
import io.paradaux.hibernia.framework.commander.annotations.Route;
import io.paradaux.hibernia.framework.commander.annotations.Sender;
import io.paradaux.hibernia.framework.commander.spi.CommandHandler;
import io.paradaux.hibernia.framework.i18n.Message;
import org.bukkit.entity.Player;

/**
 * {@code /chestshop bypass} — toggle your own admin bypass. Its own handler (not a
 * route on {@link ChestShopCommand}) so the class-level {@code @Permission} filters
 * the subcommand out of tab-completion for non-admins — the tree builder only
 * applies the <em>class</em> permission to Brigadier's {@code requires} predicate;
 * a route-level one would still execute-gate but would leak into completion.
 */
@Command({"chestshop", "cs"})
@io.paradaux.hibernia.framework.commander.annotations.Permission("ChestShop.admin")
public class BypassCommand implements CommandHandler {

    private final Message message;
    private final AdminBypassService adminBypass;

    @Inject
    public BypassCommand(Message message, AdminBypassService adminBypass) {
        this.message = message;
        this.adminBypass = adminBypass;
    }

    @Route("bypass")
    @Description("Toggle your own admin bypass — off lets you play as a normal customer")
    public void bypass(@Sender Player player) {
        if (adminBypass.toggle(player)) {
            message.send(player, "chestshop.BYPASS_OFF");
        } else {
            message.send(player, "chestshop.BYPASS_ON");
        }
    }
}
