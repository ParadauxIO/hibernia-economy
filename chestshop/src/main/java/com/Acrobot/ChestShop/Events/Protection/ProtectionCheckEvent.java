package com.Acrobot.ChestShop.Events.Protection;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Compatibility event for the installed ChestShop 3/4 add-ons. */
public class ProtectionCheckEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    private final io.paradaux.chestshop.model.ProtectionCheck check;

    public ProtectionCheckEvent(io.paradaux.chestshop.model.ProtectionCheck check) {
        this.check = check;
    }

    public org.bukkit.block.Block getBlock() {
        return check.getBlock();
    }

    public org.bukkit.entity.Player getPlayer() {
        return check.getPlayer();
    }

    public Result getResult() {
        return check.getResult();
    }

    public void setResult(Result result) {
        check.setResult(result);
    }
}
