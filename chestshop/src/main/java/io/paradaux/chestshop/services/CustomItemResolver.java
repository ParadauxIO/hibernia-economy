package io.paradaux.chestshop.services;

import org.bukkit.inventory.ItemStack;

/**
 * A custom-item provider that {@link ItemService} consults before the alias/vanilla fallbacks —
 * the seam a soft-dependency integration (e.g. Nexo/ItemsAdder) registers itself through, so the
 * service layer never references the integration's classes directly. Registered via
 * {@link ItemService#registerCustomItemResolver}; absent by default (vanilla only).
 */
public interface CustomItemResolver {

    /** Resolve a sign item string to a custom {@link ItemStack}, or {@code null} if not a custom item. */
    ItemStack parseItem(String raw);

    /** The sign string for a custom item within {@code maxWidth}, or {@code null} if not a custom item. */
    String queryString(ItemStack stack, int maxWidth);

    /** Reload the provider's own config (on {@code /chestshop reload}). */
    void reload();
}
