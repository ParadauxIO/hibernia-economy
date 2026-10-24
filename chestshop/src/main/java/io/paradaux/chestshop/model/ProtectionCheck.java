package io.paradaux.chestshop.model;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;

/**
 * Mutable carrier for a protection/access check, run by
 * {@link io.paradaux.chestshop.services.ProtectionService}. The {@code result} starts at
 * {@link Event.Result#DEFAULT} (allow) and handlers set {@link Event.Result#DENY}.
 * Formerly a Bukkit event.
 *
 * @author Acrobot
 */
public class ProtectionCheck {

    private Event.Result result = Event.Result.DEFAULT;
    private boolean ignoreBuiltInProtection = false;
    private boolean checkManagement = true;
    private Block block;
    private Player player;

    public ProtectionCheck(Block block, Player player) {
        this.block = block;
        this.player = player;
    }

    public ProtectionCheck(Block block, Player player, boolean ignoreBuiltInProtection) {
        this.block = block;
        this.player = player;
        this.ignoreBuiltInProtection = ignoreBuiltInProtection;
    }

    public ProtectionCheck(Block block, Player player, boolean ignoreBuiltInProtection, boolean checkManagement) {
        this.block = block;
        this.player = player;
        this.ignoreBuiltInProtection = ignoreBuiltInProtection;
        this.checkManagement = checkManagement;
    }

    public boolean isBuiltInProtectionIgnored() {
        return ignoreBuiltInProtection;
    }

    public boolean checkCanManage() {
        return checkManagement;
    }

    public Event.Result getResult() {
        return result;
    }

    public void setResult(Event.Result result) {
        this.result = result;
    }

    public Player getPlayer() {
        return player;
    }

    public Block getBlock() {
        return block;
    }
}
