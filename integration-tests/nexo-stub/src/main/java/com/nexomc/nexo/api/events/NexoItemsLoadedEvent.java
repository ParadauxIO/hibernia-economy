package com.nexomc.nexo.api.events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Stand-in for Nexo's "item definitions (re)loaded" event. ChestShop listens for it
 * to drop its cached base items; the stub never fires it, the class only has to
 * exist so that listener registers.
 */
public class NexoItemsLoadedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
