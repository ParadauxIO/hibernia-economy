package io.paradaux.chestshop.commands;

import com.google.inject.Inject;
import io.paradaux.chestshop.services.MarketResyncService;
import io.paradaux.hibernia.framework.commander.annotations.Arg;
import io.paradaux.hibernia.framework.commander.annotations.Command;
import io.paradaux.hibernia.framework.commander.annotations.Description;
import io.paradaux.hibernia.framework.commander.annotations.Permission;
import io.paradaux.hibernia.framework.commander.annotations.Route;
import io.paradaux.hibernia.framework.commander.annotations.Sender;
import io.paradaux.hibernia.framework.commander.spi.CommandHandler;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /chestshop resync <chunksPerTick>} — rebuild the live shop registry from
 * the loaded world (discovery + stock refresh + dead-shop cleanup). Split off
 * {@code /find} (now item-search only) onto the {@code /chestshop} root. Admin only:
 * the class-level {@code @Permission} filters it out of tab-completion for non-admins
 * and gates execution via Brigadier — so, like {@link BypassCommand}, no manual
 * permission check is needed. (PAR-322)
 */
@Command({"chestshop", "cs"})
@Permission("ChestShop.admin")
public final class ResyncCommand implements CommandHandler {

    private final MarketResyncService resync;

    @Inject
    public ResyncCommand(MarketResyncService resync) {
        this.resync = resync;
    }

    @Route("resync <chunks>")
    @Description("Rebuild the /find shop registry from the loaded world")
    public void resync(@Sender CommandSender sender, @Arg("chunks") int chunks) {
        if (!(sender instanceof Player player)) {
            return;
        }
        resync.resync(player, chunks);
    }
}
