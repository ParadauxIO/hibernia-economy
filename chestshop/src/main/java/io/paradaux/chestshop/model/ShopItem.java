package io.paradaux.chestshop.model;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

/** Reconstructs registry items for dialogs and previews on the server thread. */
public final class ShopItem {
    private ShopItem() {}

    /** Stored YAML takes precedence; old or damaged rows fall back to material. */
    public static ItemStack decode(String material, String itemData) {
        if (itemData != null) {
            try {
                YamlConfiguration yc = new YamlConfiguration();
                yc.loadFromString(itemData);
                ItemStack item = yc.getItemStack("item");
                if (item != null) {
                    return item;
                }
            } catch (Exception ignored) {
                // fall back to the base material below
            }
        }
        try {
            return new ItemStack(Material.valueOf(material));
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }
}
