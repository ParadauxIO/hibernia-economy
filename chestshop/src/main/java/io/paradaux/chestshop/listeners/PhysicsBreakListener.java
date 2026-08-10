package io.paradaux.chestshop.listeners;

import com.google.inject.Inject;
import io.paradaux.chestshop.services.SignBreakService;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPhysicsEvent;

public class PhysicsBreakListener implements Listener {

    private final SignBreakService signBreak;

    @Inject
    public PhysicsBreakListener(SignBreakService signBreak) {
        this.signBreak = signBreak;
    }

    /**
     * {@link BlockPhysicsEvent} is a hot event: fired for essentially every block update in a
     * loaded chunk. The guard below MUST stay O(1) and allocation-free so the overwhelming
     * majority of non-shop updates cost only an enum read + a tag {@code Set} lookup, and never
     * reach the sign resolution in {@link SignBreakService#handlePhysicsBreak(Block)} (which
     * materialises {@code BlockData}, reads block state and walks neighbours).
     *
     * <p>{@link Tag#SIGNS} covers both standing and wall signs — the only blocks whose physics
     * break can drop a shop sign. {@link Block#getType()} is a plain enum field read (no
     * {@code BlockData} allocation, no chunk-load probe), and {@code isTagged} is a hashed
     * {@code Set} membership test, so this prefix is O(1) with zero allocation.
     */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onSign(BlockPhysicsEvent event) {
        Block block = event.getBlock();
        if (!Tag.SIGNS.isTagged(block.getType())) {
            return;
        }
        signBreak.handlePhysicsBreak(block);
    }
}
