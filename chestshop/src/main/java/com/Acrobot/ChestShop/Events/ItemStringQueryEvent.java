package com.Acrobot.ChestShop.Events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Compatibility event for the installed ChestShop 3/4 add-ons. */
public class ItemStringQueryEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    private final org.bukkit.inventory.ItemStack item;
    private String itemString;

    public ItemStringQueryEvent(org.bukkit.inventory.ItemStack value) {
        this.item = value;
    }

    public org.bukkit.inventory.ItemStack getItem() {
        return item;
    }

    public String getItemString() {
        return itemString;
    }

    public void setItemString(String value) {
        this.itemString = value;
    }
}
