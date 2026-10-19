package io.paradaux.chestshop.commands;

import com.google.inject.Inject;
import io.paradaux.chestshop.services.LegacyShopImportService;
import io.paradaux.hibernia.framework.commander.annotations.Command;
import io.paradaux.hibernia.framework.commander.annotations.Description;
import io.paradaux.hibernia.framework.commander.annotations.Permission;
import io.paradaux.hibernia.framework.commander.annotations.Route;
import io.paradaux.hibernia.framework.commander.annotations.Sender;
import io.paradaux.hibernia.framework.commander.spi.CommandHandler;
import org.bukkit.command.CommandSender;

@Command({"chestshop", "cs"})
@Permission("ChestShop.admin")
public final class ImportShopsCommand implements CommandHandler {
    private final LegacyShopImportService importer;
    private final io.paradaux.chestshop.services.LegacyPreferenceImportService preferences;

    @Inject
    public ImportShopsCommand(
            LegacyShopImportService importer,
            io.paradaux.chestshop.services.LegacyPreferenceImportService preferences) {
        this.preferences = preferences;
        this.importer = importer;
    }

    @Route("importlegacy")
    @Description("Backfill missing registry shops from legacy-shops.jsonl")
    public void importLegacy(@Sender CommandSender sender) {
        importer.importShops(sender);
    }

    @Route("importpreferences")
    @Description("Import missing legacy player preview preferences")
    public void importPreferences(@Sender CommandSender sender) {
        preferences.importPreferences(sender);
    }
}
