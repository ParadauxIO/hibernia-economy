package com.Acrobot.ChestShop.Utils;

import io.paradaux.chestshop.integration.LegacyCompatibility;
import io.paradaux.chestshop.services.ShopBlockService;

public final class uBlock {
    private uBlock() {}

    public static boolean couldBeShopContainer(org.bukkit.block.Block block) {
        return LegacyCompatibility.service(ShopBlockService.class).couldBeShopContainer(block);
    }

    public static org.bukkit.block.Container findConnectedContainer(org.bukkit.block.Block block) {
        return LegacyCompatibility.service(ShopBlockService.class).findConnectedContainer(block);
    }

    public static org.bukkit.block.Container findConnectedContainer(org.bukkit.block.Sign sign) {
        return LegacyCompatibility.service(ShopBlockService.class).findConnectedContainer(sign);
    }

    public static java.util.List<org.bukkit.block.Sign> findConnectedShopSigns(org.bukkit.block.Block block) {
        return LegacyCompatibility.service(ShopBlockService.class).findConnectedShopSigns(block);
    }

    public static java.util.List<org.bukkit.block.Sign> findConnectedShopSigns(
            org.bukkit.inventory.InventoryHolder holder) {
        return LegacyCompatibility.service(ShopBlockService.class).findConnectedShopSigns(holder);
    }
}
