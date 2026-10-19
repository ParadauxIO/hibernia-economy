package com.Acrobot.ChestShop.Events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Compatibility event for the installed ChestShop 3/4 add-ons. */
public class ShopCreatedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    private final org.bukkit.entity.Player player;
    private final org.bukkit.block.Sign sign;
    private final org.bukkit.block.Container container;
    private final String[] lines;

    public String[] getSignLines() {
        return lines.clone();
    }

    public ShopCreatedEvent(
            org.bukkit.entity.Player player,
            org.bukkit.block.Sign sign,
            org.bukkit.block.Container container,
            String[] lines) {
        this.player = player;
        this.sign = sign;
        this.container = container;
        this.lines = lines.clone();
    }

    public org.bukkit.entity.Player getPlayer() {
        return player;
    }

    public org.bukkit.entity.Player getDestroyer() {
        return player;
    }

    public org.bukkit.block.Sign getSign() {
        return sign;
    }

    public org.bukkit.block.Container getContainer() {
        return container;
    }
}
