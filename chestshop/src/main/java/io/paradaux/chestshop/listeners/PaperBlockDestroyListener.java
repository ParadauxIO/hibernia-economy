package io.paradaux.chestshop.listeners;

import com.destroystokyo.paper.event.block.BlockDestroyEvent;
import com.google.inject.Inject;
import io.paradaux.chestshop.services.SignBreakService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Removes a shop when its sign is destroyed by physics via Paper's
 * {@link BlockDestroyEvent} (the Paper-native counterpart to the Spigot
 * {@code BlockPhysicsEvent} path handled by {@link PhysicsBreakListener}). Was the
 * version-named {@code adapter/Paper_1_13_2}.
 */
public class PaperBlockDestroyListener implements Listener {

    private final SignBreakService signBreak;

    @Inject
    public PaperBlockDestroyListener(SignBreakService signBreak) {
        this.signBreak = signBreak;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onSign(BlockDestroyEvent event) {
        signBreak.handlePhysicsBreak(event.getBlock());
    }
}
