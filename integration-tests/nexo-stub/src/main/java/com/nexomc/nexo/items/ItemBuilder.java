package com.nexomc.nexo.items;

import org.bukkit.inventory.ItemStack;

/**
 * Minimal stand-in for Nexo's {@code com.nexomc.nexo.items.ItemBuilder} — only the
 * {@code build()} method ChestShop's integration ({@code plugins/Nexo.java}) calls.
 * Signature-compatible with the real API ({@code NexoItems.itemFromId(String)} returns
 * this; ChestShop invokes {@code build()} → {@link ItemStack}).
 */
public class ItemBuilder {

    private final ItemStack template;

    public ItemBuilder(ItemStack template) {
        this.template = template;
    }

    public ItemStack build() {
        return template.clone();
    }
}
