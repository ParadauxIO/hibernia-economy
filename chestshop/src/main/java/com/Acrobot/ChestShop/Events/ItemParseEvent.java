package com.Acrobot.ChestShop.Events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Compatibility event for the installed ChestShop 3/4 add-ons. */
public class ItemParseEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    private final String itemString;
    private org.bukkit.inventory.ItemStack item;

    public ItemParseEvent(String value) {
        this.itemString = value;
    }

    public String getItemString() {
        return itemString;
    }

    public org.bukkit.inventory.ItemStack getItem() {
        return item;
    }

    public void setItem(org.bukkit.inventory.ItemStack value) {
        this.item = value;
    }
}
