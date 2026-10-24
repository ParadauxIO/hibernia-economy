package com.Acrobot.ChestShop.Signs;

import io.paradaux.chestshop.integration.LegacyCompatibility;
import io.paradaux.chestshop.services.SignService;

public final class ChestShopSign {
    private ChestShopSign() {}

    public static final java.util.regex.Pattern[][] SHOP_SIGN_PATTERN = SignService.SHOP_SIGN_PATTERN;

    public static boolean isValid(String[] lines) {
        return LegacyCompatibility.service(SignService.class).isValid(lines);
    }

    public static boolean isValid(org.bukkit.block.Sign sign) {
        return LegacyCompatibility.service(SignService.class).isValid(sign);
    }

    public static boolean isValid(org.bukkit.block.Block block) {
        return block.getState() instanceof org.bukkit.block.Sign sign && isValid(sign);
    }

    public static boolean isAdminShop(String name) {
        return LegacyCompatibility.service(SignService.class).isAdminShop(name);
    }

    public static boolean canAccess(org.bukkit.entity.Player player, org.bukkit.block.Sign sign) {
        return LegacyCompatibility.service(io.paradaux.chestshop.services.AccountService.class)
                .canAccess(player, sign);
    }

    public static String getOwner(String[] lines) {
        return SignService.getOwner(lines);
    }

    public static String getItem(String[] lines) {
        return SignService.getItem(lines);
    }

    public static String getPrice(String[] lines) {
        return SignService.getPrice(lines);
    }

    public static int getQuantity(String[] lines) {
        return SignService.getQuantity(lines);
    }
}
