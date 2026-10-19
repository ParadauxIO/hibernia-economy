package io.paradaux.chestshop.commands;

import com.google.inject.Inject;
import io.paradaux.hibernia.framework.commander.annotations.*;
import io.paradaux.hibernia.framework.commander.spi.CommandHandler;
import org.bukkit.command.CommandSender;

/** Retains the old add-on command paths after ChestShopDatabase is retired. */
@Command("csdb")
@Permission("ChestShop.find")
public final class LegacyFindCommand implements CommandHandler {
    private final FindCommand find;
    private final ShopToggleCommand toggles;
    private final VersionCommand version;
    private final ResyncCommand resync;

    @Inject
    public LegacyFindCommand(
            FindCommand find, ShopToggleCommand toggles, VersionCommand version, ResyncCommand resync) {
        this.find = find;
        this.toggles = toggles;
        this.version = version;
        this.resync = resync;
    }

    @Route("find")
    public void held(@Sender CommandSender sender) {
        find.heldItem(sender);
    }

    @Route("find <item>")
    public void code(@Sender CommandSender sender, @GreedyArg(value = "item", sanitize = false) String item) {
        find.byCode(sender, item);
    }

    @Route("find toggle preview <visible>")
    public void preview(@Sender CommandSender sender, @Arg("visible") boolean visible) {
        toggles.togglePreview(sender, visible);
    }

    @Route("find toggle visibility <visible>")
    public void visibility(@Sender CommandSender sender, @Arg("visible") boolean visible) {
        toggles.toggleVisibility(sender, visible);
    }

    @Route("find toggle hologram <visible>")
    public void hologram(@Sender CommandSender sender, @Arg("visible") boolean visible) {
        toggles.toggleHologram(sender, visible);
    }

    @Route("reload")
    @Permission("ChestShop.admin")
    public void reload(@Sender CommandSender sender) {
        version.reload(sender);
    }

    @Route("resync <chunks>")
    @Permission("ChestShop.admin")
    public void resync(@Sender CommandSender sender, @Arg("chunks") int chunks) {
        resync.resync(sender, chunks);
    }
}
