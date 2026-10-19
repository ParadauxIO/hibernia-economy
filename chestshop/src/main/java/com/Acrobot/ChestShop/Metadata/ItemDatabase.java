package com.Acrobot.ChestShop.Metadata;

import io.paradaux.chestshop.integration.LegacyCompatibility;

public final class ItemDatabase {
    public String getItemCode(org.bukkit.inventory.ItemStack item) {
        return LegacyCompatibility.service(io.paradaux.chestshop.services.ItemCodeService.class)
                .getItemCode(item);
    }

    public org.bukkit.inventory.ItemStack getFromCode(String code) {
        return LegacyCompatibility.service(io.paradaux.chestshop.services.ItemCodeService.class)
                .getFromCode(code);
    }
}
