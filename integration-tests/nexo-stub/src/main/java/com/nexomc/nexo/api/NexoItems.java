package com.nexomc.nexo.api;

import com.nexomc.nexo.items.ItemBuilder;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal stand-in for Nexo's {@code com.nexomc.nexo.api.NexoItems}. Provides the exact
 * static surface ChestShop's integration links against — {@code itemFromId(String)} →
 * {@link ItemBuilder} and {@code idFromItem(ItemStack)} → the nexo id — backed by a
 * registry the stub plugin populates with test items on enable.
 */
public final class NexoItems {

    private static final NamespacedKey NEXO_KEY = NamespacedKey.fromString("nexo:id");
    private static final Map<String, ItemStack> REGISTRY = new ConcurrentHashMap<>();

    private NexoItems() {
    }

    /** Test-plugin entry point: register the base ItemStack for a nexo id. */
    public static void register(String id, ItemStack item) {
        REGISTRY.put(id, item.clone());
    }

    public static ItemBuilder itemFromId(String id) {
        ItemStack base = REGISTRY.get(id);
        return base == null ? null : new ItemBuilder(base);
    }

    public static boolean exists(String id) {
        return REGISTRY.containsKey(id);
    }

    public static String idFromItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(NEXO_KEY, PersistentDataType.STRING);
    }
}
