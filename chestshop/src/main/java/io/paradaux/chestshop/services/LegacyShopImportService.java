package io.paradaux.chestshop.services;

import org.bukkit.command.CommandSender;

public interface LegacyShopImportService {
    /** Read legacy-shops.jsonl from the plugin data folder, reporting every rejected line. */
    void importShops(CommandSender sender);
}
