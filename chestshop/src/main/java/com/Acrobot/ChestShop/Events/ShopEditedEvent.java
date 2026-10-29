package com.Acrobot.ChestShop.Events;

import com.Acrobot.ChestShop.Database.Account;
import javax.annotation.Nullable;
import org.bukkit.block.Container;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Completed edit notification, between destruction of the old shop and creation of the new one. */
public class ShopEditedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player modifier;
    private final Sign sign;
    private final @Nullable Container container;
    private final String[] oldLines;
    private final String[] newLines;
    private final @Nullable Account ownerAccount;

    public ShopEditedEvent(
            Player modifier,
            Sign sign,
            @Nullable Container container,
            String[] oldLines,
            String[] newLines,
            @Nullable Account ownerAccount) {
        this.modifier = modifier;
        this.sign = sign;
        this.container = container;
        this.oldLines = oldLines.clone();
        this.newLines = newLines.clone();
        this.ownerAccount = ownerAccount;
    }

    public Player getModifier() {
        return modifier;
    }

    public Sign getSign() {
        return sign;
    }

    public @Nullable Container getContainer() {
        return container;
    }

    public String[] getOldLines() {
        return oldLines.clone();
    }

    public String[] getNewLines() {
        return newLines.clone();
    }

    public @Nullable Account getOwnerAccount() {
        return ownerAccount;
    }

    public boolean modifiedByOwner() {
        return ownerAccount == null || ownerAccount.getUuid().equals(modifier.getUniqueId());
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
