package com.Acrobot.ChestShop.Events.Protection;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Compatibility event for the installed ChestShop 3/4 add-ons. */
public class ProtectBlockEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    private final org.bukkit.block.Block block;
    private final org.bukkit.entity.Player player;
    private final java.util.UUID owner;
    private final com.Acrobot.ChestShop.Security.Type type;
    private boolean protectedBlock;

    public ProtectBlockEvent(
            org.bukkit.block.Block block,
            org.bukkit.entity.Player player,
            java.util.UUID owner,
            com.Acrobot.ChestShop.Security.Type type) {
        this.block = block;
        this.player = player;
        this.owner = owner;
        this.type = type;
    }

    public org.bukkit.block.Block getBlock() {
        return block;
    }

    public org.bukkit.entity.Player getPlayer() {
        return player;
    }

    public java.util.UUID getProtectionOwner() {
        return owner;
    }

    public com.Acrobot.ChestShop.Security.Type getType() {
        return type;
    }

    public boolean isProtected() {
        return protectedBlock;
    }

    public void setProtected(boolean value) {
        protectedBlock = value;
    }
}
